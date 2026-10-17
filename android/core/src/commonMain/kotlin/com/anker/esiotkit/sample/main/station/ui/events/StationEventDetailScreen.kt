package com.anker.esiotkit.sample.main.station.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.sample.main.mega.event.EventPlayScreen
import com.anker.esiotkit.sample.main.station.agent.TraceEvidenceImage
import com.anker.esiotkit.sample.main.station.events.*
import com.anker.esiotkit.sample.view.BaseScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The navigation object contains identifiers only, never SDK events, media or credentials. */
class StationEventDetailScreen private constructor(
  private val eventId: String? = null,
  private val recordingId: String? = null,
  private val evidenceId: String? = null,
  private val deviceSn: String? = null,
  private val sdkEventId: String? = null,
) : BaseScreen() {
  val isBackendTarget: Boolean get() = eventId != null

  /** A new screen key releases prior media and rechecks backend authorization via source.load. */
  internal fun revalidateBackendSession(): StationEventDetailScreen {
    check(eventId != null && recordingId != null)
    return backend(eventId, recordingId, evidenceId)
  }

  companion object {
    fun backend(eventId: String, recordingId: String, evidenceId: String? = null) =
      StationEventDetailScreen(eventId = eventId, recordingId = recordingId, evidenceId = evidenceId)

    fun sdk(card: StationEventCard): StationEventDetailScreen {
      StationDetailSdkCache.retain(listOf(card))
      return StationEventDetailScreen(deviceSn = card.deviceSn, sdkEventId = card.event.megaEventId)
    }
  }

  @Composable
  override fun Content() {
    val target = remember { if (eventId != null && recordingId != null)
      StationEventDetailTarget.BackendEvent(eventId, recordingId, evidenceId)
    else StationEventDetailTarget.SdkDetection(deviceSn.orEmpty(), sdkEventId.orEmpty()) }
    Box(Modifier.fillMaxSize().background(Color(0xFFF6F8FB)).statusBarsPadding()) {
      StationEventDetailContent(target)
    }
  }
}

/** Bounded account-local SDK memory; cleared by the SDK logout handler. */
internal object StationDetailSdkCache {
  private val cards = linkedMapOf<Pair<String, String>, StationEventCard>()
  fun retain(values: List<StationEventCard>) {
    values.asReversed().forEach { card ->
      val key = card.deviceSn to card.event.megaEventId
      cards.remove(key)
      cards[key] = card
      while (cards.size > 200) cards.remove(cards.keys.first())
    }
  }
  fun find(sn: String?, id: String?): StationEventCard? = if (sn == null || id == null) null else cards[sn to id]
  fun clear() = cards.clear()
}

@Composable
private fun StationEventDetailContent(target: StationEventDetailTarget) {
  val navigator = LocalNavigator.currentOrThrow
  val externalBack = LocalStationBackendEventBack.current
  val source = rememberStationEventDetailSource()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  val scope = rememberCoroutineScope()
  var refreshVersion by remember { mutableStateOf(0) }
  var loading by remember { mutableStateOf(true) }
  var baseResult by remember { mutableStateOf<StationEventDetailsResult?>(null) }
  var selectedResult by remember { mutableStateOf<StationEventDetailsResult?>(null) }
  var selectedItemId by rememberSaveable { mutableStateOf<String?>(null) }
  var selectedFrameId by rememberSaveable { mutableStateOf<String?>(null) }
  var resolvingPlayback by remember { mutableStateOf(false) }
  var playbackJob by remember { mutableStateOf<Job?>(null) }
  var playbackError by remember { mutableStateOf<String?>(null) }
  var recordingPlayback by remember { mutableStateOf<StationRecordingPlayback?>(null) }
  var recordingStartOffset by remember { mutableStateOf(0L) }
  DisposableEffect(recordingPlayback) {
    val opened = recordingPlayback
    onDispose { opened?.release() }
  }
  DisposableEffect(Unit) { onDispose { playbackJob?.cancel(); recordingPlayback?.release() } }
  var resolvedSdk by remember { mutableStateOf<StationEventCard?>(null) }
  val initialSdk = (target as? StationEventDetailTarget.SdkDetection)?.let {
    StationDetailSdkCache.find(it.deviceSn, it.sdkEventId)
  }
  LaunchedEffect(foreground) { if (!foreground) { playbackJob?.cancel(); resolvingPlayback = false } }

  LaunchedEffect(target, source, refreshVersion, foreground) {
    if (!foreground) return@LaunchedEffect
    loading = true
    selectedResult = null
    baseResult = try { withTimeout(45_000) { source.load(target) } }
    catch (_: TimeoutCancellationException) { StationEventDetailsResult.Failed("事件读取超时，请重试。") }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { StationEventDetailsResult.Failed("事件读取失败，请检查服务后重试。") }
    loading = false
  }
  val base = (baseResult as? StationEventDetailsResult.Loaded)?.detail
  val unavailable = baseResult as? StationEventDetailsResult.Unavailable
  val linkedJob = base?.job ?: unavailable?.job
  val sdkTarget = target as? StationEventDetailTarget.SdkDetection
  val sdk = resolvedSdk ?: initialSdk ?: linkedJob?.let { StationDetailSdkCache.find(it.deviceSn, it.sdkEventId) }
  val sdkKey = sdk?.let { "sdk:${it.deviceSn}:${it.event.megaEventId}" }
    ?: sdkTarget?.let { "sdk:${it.deviceSn}:${it.sdkEventId}" }
  // The separately authorized clicked event may be outside a capped sibling
  // response. Keep it as the target instead of silently opening the first event.
  val timeline = (base?.timeline.orEmpty() + listOfNotNull(base?.selectedEvent))
    .distinctBy { it.id }.sortedWith(compareBy<StationDetailEvent> { it.startOffsetMs }.thenBy { it.id })
  val items = buildList {
    if (sdk != null) add(StationEventDetailItem(sdkKey!!, "0:00—${detailOffset(sdk.durationMillis)}",
      "摄像头检测 · ${sdk.typeLabels.joinToString(" · ")}", "SDK 原始检测；尚未校准为录像 UTC 时间。",
      "摄像头原片预览", sdk.deviceSn))
    else if (sdkTarget != null) add(StationEventDetailItem(sdkKey!!, "SDK 原始记录", "摄像头检测记录",
      "原始预览已不在内存中，可重新读取原片或返回事件列表打开。", "SDK 摄像头原片", sdkTarget.deviceSn))
    timeline.forEach { event -> add(StationEventDetailItem("event:${event.id}",
      "${detailOffset(event.startOffsetMs)}—${detailOffset(event.endOffsetMs)}", eventTypeLabel(event.type),
      listOfNotNull(event.zoneId + " 区域", observationLabel(event.observationStatus), event.summary).joinToString(" · "),
      "录像相对时间 · 关键帧", base?.recording?.cameraId)) }
  }
  val initialEventKey = (target as? StationEventDetailTarget.BackendEvent)?.eventId?.let { "event:$it" }
  // If a selected item disappears on refresh, show unavailable instead of
  // silently replacing it with another event's media.
  val activeId = selectedItemId ?: initialEventKey ?: items.firstOrNull()?.id
  val activeEvent = timeline.firstOrNull { "event:${it.id}" == activeId }
  val requiresEventLoad = activeEvent != null && base?.selectedEvent?.id != activeEvent.id
  LaunchedEffect(activeEvent?.id, base?.session?.id, foreground) {
    selectedResult = null
    if (activeEvent != null && requiresEventLoad && base != null && foreground) {
      selectedResult = try { withTimeout(45_000) {
        source.load(StationEventDetailTarget.BackendEvent(activeEvent.id, base.recording.id))
      } } catch (_: TimeoutCancellationException) { StationEventDetailsResult.Failed("片段读取超时，请重试。") }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { StationEventDetailsResult.Failed("片段读取失败，请重试。") }
    }
  }
  val selectedDetail = if (requiresEventLoad) (selectedResult as? StationEventDetailsResult.Loaded)?.detail else base
  LaunchedEffect(activeId, selectedDetail?.session?.id, foreground) {
    playbackJob?.cancel()
    resolvingPlayback = false
    recordingPlayback = null
  }
  val frames = selectedDetail?.evidence.orEmpty().filter { it.eventId == activeEvent?.id }
  val frame = frames.firstOrNull { it.id == selectedFrameId }
    ?: frames.firstOrNull { it.id == selectedDetail?.focusedEvidenceId } ?: frames.firstOrNull()
  var image by remember(selectedDetail?.session?.id, frame?.id) { mutableStateOf<StationEventImageResult?>(null) }
  LaunchedEffect(selectedDetail?.session, frame?.id, foreground) {
    image = null
    if (selectedDetail != null && frame != null && frame.mediaStatus == "available" && foreground) {
      image = source.evidence(selectedDetail.session, frame.id)
    }
  }
  val notices = buildList {
    unavailable?.let { add(it.message) }
    if (base != null && base.timeline.isEmpty()) add("该录像已发布，当前未返回区域动作事件；SDK 检测与算法事件分别记录。")
    if (base?.evidence.orEmpty().any { it.mediaStatus != "available" } || frames.any { it.mediaStatus != "available" })
      add("部分关键帧缺失，无法仅凭这些片段确认连续过程。")
    if (base?.limitReached == true) add("已达到本次事件读取上限，时间线可能不完整。")
    playbackError?.let(::add)
  }.joinToString("\n").ifBlank { null }
  val deviceId = base?.recording?.cameraId ?: sdk?.deviceSn ?: sdkTarget?.deviceSn
  val deviceLabel = sdk?.deviceName ?: base?.recording?.cameraId ?: sdkTarget?.deviceSn
  val error = (baseResult as? StationEventDetailsResult.Failed)?.message
  val selectedError = (selectedResult as? StationEventDetailsResult.Failed)?.message
    ?: (selectedResult as? StationEventDetailsResult.Unavailable)?.message

  fun playSdk() {
    if (resolvingPlayback || !foreground) return
    playbackJob = scope.launch {
      resolvingPlayback = true
      playbackError = null
      try {
        var card = sdk
        // A timestamp only bounds the lookup. Identity must match both exact SDK keys.
        if (card == null && linkedJob?.deviceSn != null && linkedJob.sdkTimestampMs != null) {
          val sn = linkedJob.deviceSn!!
          val zone = TimeZone.currentSystemDefault()
          val day = Instant.fromEpochMilliseconds(linkedJob.sdkTimestampMs!!).toLocalDateTime(zone).date
          val found = loadStationEvents(StationEventQuery(day, zone, sn), currentStationEventCameras())
          card = found.events.firstOrNull { it.deviceSn == sn && it.event.megaEventId == linkedJob.sdkEventId }
          card?.let { StationDetailSdkCache.retain(listOf(it)); resolvedSdk = it }
        }
        when {
          card == null -> playbackError = "SDK 原始记录暂未返回，请从事件列表重新打开该记录。"
          MegaDeviceManager.getDeviceBy(card.deviceSn) == null -> playbackError = "当前设备账号无法打开该摄像头，请返回设备页检查登录。"
          card.event.videoPath.isBlank() || card.durationMillis <= 0 -> playbackError = "该检测记录尚未提供可回放录像。"
          else -> navigator.push(EventPlayScreen(card.event))
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { playbackError = "摄像头录像读取失败，请从事件列表重试。" }
      finally { resolvingPlayback = false }
    }
  }

  fun playRecording() {
    val detail = selectedDetail ?: return
    val event = activeEvent ?: return
    if (resolvingPlayback || !foreground || event.recordingId != detail.recording.id ||
      event.startOffsetMs !in 0 until detail.recording.durationMs) return
    playbackJob = scope.launch {
      resolvingPlayback = true
      playbackError = null
      recordingPlayback = null
      try {
        when (val result = source.recording(detail.session, detail.recording.id)) {
          is StationEventRecordingResult.Loaded -> {
            recordingStartOffset = event.startOffsetMs
            recordingPlayback = result.playback
          }
          is StationEventRecordingResult.Failed -> playbackError = result.message
        }
      } catch (_: TimeoutCancellationException) { playbackError = "读取原片超时，请重试。" }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { playbackError = "原片读取失败，请检查服务后重试。" }
      finally { resolvingPlayback = false }
    }
  }

  StationEventDetailLayout(
    state = StationEventDetailDisplayState(
      devices = if (deviceId != null && deviceLabel != null) listOf(StationEventDetailDevice(deviceId, deviceLabel)) else emptyList(),
      selectedDeviceId = deviceId,
      items = items, selectedItemId = activeId,
      subtitle = base?.let { "${sourceModeLabel(it.recording.sourceMode)} · ${it.recording.cameraId} · 录像相对时间" }
        ?: sdk?.let { "${it.deviceName} · SDK 摄像头检测" },
      coverageNotice = notices, loading = loading, errorMessage = error,
      emptyMessage = unavailable?.message ?: "尚无可查看的事件或关键帧，请返回后选择其他记录。",
    ),
    onBack = { if (externalBack != null) externalBack() else navigator.pop() }, onSelectDevice = {},
    onSelectItem = { playbackJob?.cancel(); recordingPlayback = null; selectedItemId = it; selectedFrameId = null; playbackError = null },
    onRetry = { refreshVersion++ },
    media = { item, modifier ->
      Column(modifier.background(Color.White)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
          if (item.id == sdkKey) {
            if (sdk != null) EventThumbnail(sdk.event.thumbnail, Modifier.fillMaxSize())
            else Text("SDK 原始预览暂不可用", color = Color.Gray, fontSize = 13.sp)
          }
          else when {
            recordingPlayback != null -> StationRecordingPlayer(recordingPlayback!!, recordingStartOffset, Modifier.fillMaxSize()) {
              playbackError = it
              recordingPlayback = null
            }
            selectedError != null -> Text(selectedError, color = Color(0xFFB54B36), fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            requiresEventLoad && selectedResult == null -> Text("正在读取片段…", color = Color.Gray)
            frame == null -> Text("该事件暂无可查看的关键帧", color = Color.Gray, fontSize = 13.sp)
            frame.mediaStatus != "available" -> Text("关键帧媒体缺失，引用仍保留", color = Color.Gray, fontSize = 13.sp)
            image is StationEventImageResult.Loaded -> TraceEvidenceImage((image as StationEventImageResult.Loaded).bytes, Modifier.fillMaxSize())
            image is StationEventImageResult.Failed -> Text((image as StationEventImageResult.Failed).message, color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            else -> Text("正在读取关键帧…", color = Color.Gray, fontSize = 13.sp)
          }
        }
        if (item.id != sdkKey && activeEvent != null && selectedDetail?.recording?.let {
            it.sourceMode in setOf("sdk", "prerecorded") && it.mediaStatus == "available"
          } == true) TextButton(onClick = { if (recordingPlayback != null) recordingPlayback = null else playRecording() }, enabled = !resolvingPlayback && foreground,
          modifier = Modifier.align(Alignment.CenterHorizontally)) {
          Text(when { resolvingPlayback -> "正在读取原片…"; recordingPlayback != null -> "返回关键帧"
            else -> "回看原片 · 从 ${detailOffset(activeEvent.startOffsetMs)} 开始" }, fontSize = 13.sp)
        }
        if (item.id != sdkKey && frames.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          val index = frames.indexOf(frame)
          TextButton(onClick = { recordingPlayback = null; selectedFrameId = frames[index - 1].id }, enabled = index > 0) { Text("上一帧") }
          Text("关键帧 ${index + 1}/${frames.size} · ${detailOffset(frame!!.offsetMs)}", fontSize = 11.sp, modifier = Modifier.weight(1f))
          TextButton(onClick = { recordingPlayback = null; selectedFrameId = frames[index + 1].id }, enabled = index < frames.lastIndex) { Text("下一帧") }
        }
        if (sdk != null || linkedJob?.deviceSn != null || sdkTarget != null) TextButton(onClick = ::playSdk, enabled = !resolvingPlayback,
          modifier = Modifier.align(Alignment.CenterHorizontally)) {
          Text(if (resolvingPlayback) "正在查找原片…" else "播放摄像头原片", fontSize = 13.sp)
        }
      }
    },
  )
}

internal fun detailOffset(value: Long): String = "${value.coerceAtLeast(0) / 60_000}:${(value.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"
internal fun eventTypeLabel(type: String): String = when (type) {
  "enter_zone" -> "进入区域"
  "dwell" -> "区域停留"
  "shelf_interaction" -> "货架交互候选"
  "leave_zone" -> "离开区域"
  else -> "检测事件"
}
private fun observationLabel(value: String): String = when (value) { "candidate" -> "待人工核查"; "observed" -> "画面观测记录"; else -> "状态待核对" }
private fun sourceModeLabel(value: String): String = when (value) { "sdk" -> "SDK 原片"; "prerecorded" -> "真实预录"; "mock" -> "历史模拟数据"; else -> "录像来源" }
