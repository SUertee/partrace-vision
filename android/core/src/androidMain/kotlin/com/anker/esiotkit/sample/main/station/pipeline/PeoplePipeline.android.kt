@file:OptIn(com.anker.esiotkit.support.InternalIotApi::class)

package com.anker.esiotkit.sample.main.station.pipeline

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import android.util.Log
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.event.model.EventCondition
import com.anker.esiotkit.manager.MegaEventManager
import com.anker.esiotkit.media.VideoInfo
import com.anker.esiotkit.media.event_stream.EventDownloadState
import com.anker.esiotkit.media.event_stream.EventMediaPlayer
import com.anker.esiotkit.media.event_stream.EventPlayListener
import com.anker.esiotkit.media.event_stream.EventPlayerState
import com.anker.esiotkit.sample.main.mega.media.stopAndRelease
import com.anker.esiotkit.sample.support.applicationContext
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PeoplePlayerRequest(val taskId: String, val event: MegaEvent, val device: MegaDevice, val ready: CompletableDeferred<EventMediaPlayer>, var createdPlayer: EventMediaPlayer? = null)

/** Foreground coordinator. Only durable metadata/files survive process death; SDK players never do. */
actual object PeoplePipeline {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val lock = Mutex()
  private val workerLock = Mutex()
  private val queryLock = Mutex()
  private var snapshot = PeopleSnapshot()
  private var initialized = false
  private var storageFailed = false
  private var foreground = false
  private var sdkAuthenticated = false
  private var authPaused = false
  private var workers: Job? = null
  private val _state = MutableStateFlow(PeoplePipelineState())
  actual val state: StateFlow<PeoplePipelineState> = _state
  private val _completions = MutableSharedFlow<PeoplePipelineCompletion>(extraBufferCapacity = 16)
  actual val completions: SharedFlow<PeoplePipelineCompletion> = _completions
  internal val playerRequest = MutableStateFlow<PeoplePlayerRequest?>(null)
  private val context get() = applicationContext as Context
  private val storeFile get() = AtomicFile(File(context.noBackupFilesDir, "people-pipeline.json"))
  private val mediaDir get() = File(context.noBackupFilesDir, "people-recordings").apply { mkdirs() }
  private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()

  internal suspend fun initialize() = lock.withLock {
    if (initialized) return@withLock
    try {
      snapshot = withContext(Dispatchers.IO) {
        if (!storeFile.baseFile.exists()) PeopleSnapshot()
        else storeFile.openRead().bufferedReader().use { json.decodeFromString<PeopleSnapshot>(it.readText()) }
      }
      snapshot = snapshot.copy(tasks = snapshot.tasks.map(::recoverPeopleTask))
    } catch (_: Exception) {
      storageFailed = true
      _state.value = _state.value.copy(error = "本地任务记录无法读取，自动上传已停止。请重新保存配置。")
    }
    initialized = true
    publish()
  }

  internal suspend fun settings(): PeopleSettings { initialize(); return snapshot.settings }

  internal fun setActive(isForeground: Boolean, authenticated: Boolean) {
    foreground = isForeground
    sdkAuthenticated = authenticated
    scope.launch { initialize(); restartWorkers() }
  }

  internal suspend fun saveSettings(settings: PeopleSettings): String? {
    initialize()
    val normalized = normalizePeopleBaseUrl(settings.baseUrl)
      ?: return "请填写 HTTPS 服务地址；开发 HTTP 仅允许模拟器或本机回环地址，不含 /api/v1。"
    if (settings.bearerToken.isBlank() || settings.bearerToken.any { it == '\r' || it == '\n' }) return "请输入有效的后端 Bearer Token。"
    val id = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}")
    if (!settings.configVersion.matches(id)) return "请填写有效的 config_version（最多 100 个字符）。"
    if (settings.cameraMapping.isEmpty() || settings.cameraMapping.values.any { !it.matches(id) }) return "请至少为一个 SDK 摄像头填写后端 camera_id。"
    if (settings.cameraMapping.values.distinct().size != settings.cameraMapping.size) return "每台摄像头需要独立的 camera_id。"
    workerLock.withLock {
      workers?.cancelAndJoin()
      workers = null
      val old = snapshot.settings
      val clean = settings.copy(baseUrl = normalized, bearerToken = settings.bearerToken.trim())
      val newBoundary = (!old.enabled && clean.enabled) || clean.targetKey() != old.targetKey()
      val now = System.currentTimeMillis()
      val saved = clean.copy(enabledAtMs = if (newBoundary) now else old.enabledAtMs,
        baselineReady = true, lastScanMs = if (newBoundary) now else old.lastScanMs)
      storageFailed = false
      authPaused = false
      update { it.copy(settings = saved, seen = if (newBoundary) emptyList() else it.seen) }
      publish(null)
      startWorkers()
    }
    return null
  }

  /** Explicit verification path. It never changes the automatic discovery watermark. */
  internal suspend fun importLatestPeople(deviceSn: String): String {
    initialize()
    val settings = snapshot.settings
    if (!settings.configured || !settings.enabled || !foreground || !sdkAuthenticated || authPaused) return "请先保存并启用有效配置，保持 App 前台与 SDK 登录。"
    if (snapshot.tasks.size >= MaxPeopleTasks) return "本地任务达到安全限额，请先处理已有任务。"
    val cameraId = settings.cameraMapping[deviceSn] ?: return "请先保存该摄像头的 camera_id 映射。"
    val device = MegaDeviceManager.getDeviceBy(deviceSn) ?: return "SDK 摄像头信息尚未加载。"
    val now = System.currentTimeMillis()
    val known = snapshot.tasks.map { it.jobId }.toSet() + snapshot.seen
    val event = queryEvents(listOf(device), now - 24L * 60 * 60 * 1000, now)
      .filter { it.deviceSn == deviceSn && it.videoPath.isNotBlank() && it.endTimestamp > it.startTimestamp &&
        isSdkPersonDetection(it.pictures.map { picture -> picture.detectionType } + it.thumbnail.detectionType) }
      .filterNot { peopleJobId(deviceSn, it.megaEventId) in known }
      .maxByOrNull { it.startTimestamp } ?: return "最近 24 小时没有尚未处理、可下载的真实人物录像（人脸或人形）。"
    val id = peopleJobId(deviceSn, event.megaEventId)
    if (snapshot.tasks.any { it.jobId == id } || id in snapshot.seen) return "这条真实事件已有任务，请在下方查看其状态。"
    val task = PeopleTask(id, event.megaEventId, deviceSn, cameraId, settings.configVersion,
      settings.targetKey(deviceSn), json.encodeToString(MegaEvent.serializer(), event),
      event.startTimestamp.takeIf { it >= 0 }, now, nextAttemptMs = now + 20_000, origin = "manual_history")
    update { state ->
      if (state.tasks.any { it.jobId == id }) state else state.copy(tasks = state.tasks + task)
    }
    return "已加入一条真实历史录像，来源标记为“历史手动”，不会冒充新事件自动采集。"
  }

  actual fun retry(jobId: String) {
    scope.launch {
      initialize()
      update { state -> state.copy(tasks = state.tasks.map { task ->
        if (task.jobId == jobId) retryPeopleTask(task) else task
      }) }
    }
  }

  private suspend fun restartWorkers() = workerLock.withLock {
    workers?.cancelAndJoin()
    workers = null
    startWorkers()
  }

  private fun startWorkers() {
    publish()
    if (!foreground || !sdkAuthenticated || !snapshot.settings.enabled || !snapshot.settings.configured || storageFailed || authPaused) return
    workers = scope.launch {
      launch { while (true) { discoverSafely(); delay(20_000) } }
      launch { while (true) { processOne(); delay(1_000) } }
      launch { while (true) { pollRemote(); delay(5_000) } }
    }
  }

  private suspend fun update(transform: (PeopleSnapshot) -> PeopleSnapshot) = lock.withLock {
    val next = transform(snapshot)
    try {
      withContext(kotlinx.coroutines.NonCancellable) {
        withContext(Dispatchers.IO) {
          val atomic = storeFile
          val output = atomic.startWrite()
          try { output.write(json.encodeToString(next).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
          catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        }
        snapshot = next
        publish()
      }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
      storageFailed = true
      _state.value = _state.value.copy(error = "本地任务保存失败，已停止上传，请检查存储空间。")
      throw IOException("private_task_store_failed")
    }
  }

  private fun publish(error: String? = _state.value.error) {
    _state.value = PeoplePipelineState(
      configured = snapshot.settings.configured,
      enabled = snapshot.settings.enabled,
      foreground = foreground && sdkAuthenticated && !authPaused && !storageFailed,
      error = error,
      tasks = snapshot.tasks.sortedByDescending { it.createdAtMs }.map {
        val targetChanged = !eligibleTask(it) && it.status != "completed"
        PeopleTaskView(it.jobId, it.cameraId, (if (it.origin == "manual_history") "历史手动 · " else "自动 · ") + it.sdkEventId.takeLast(12), if (targetChanged) "paused_config" else it.status,
          if (targetChanged) "任务目标配置已变更，恢复对应服务与映射后继续。" else it.error,
          it.eventCount, it.evidenceCount, it.retryable && it.status == "failed",
          eligibleTask(it) && canRedownloadPeopleTask(it))
      }
    )
  }

  private suspend fun discoverSafely() {
    if (authPaused || storageFailed) return
    try {
      val settings = snapshot.settings
      val devices = MegaDeviceManager.deviceList.value.filter { it.deviceSn in settings.cameraMapping }
      if (devices.size != settings.cameraMapping.size) { publish("等待已配置摄像头的 SDK 设备信息。 "); return }
      val now = System.currentTimeMillis()
      val waitingStart = snapshot.tasks.filter { eligibleTask(it) && it.status == "waiting_clip" }.mapNotNull { it.sdkTimestampMs }.minOrNull()
      val since = maxOf(settings.enabledAtMs, minOf(settings.lastScanMs - 600_000, waitingStart ?: Long.MAX_VALUE))
      val events = queryEvents(devices, since, now)
      if (events.size >= 200) { publish("事件数量超过单次安全上限，已暂停本轮发现，未跳过未处理事件。 "); return }
      val eligible = events.filter { event ->
        event.deviceSn in settings.cameraMapping && canDiscoverPeopleEvent(
          event.startTimestamp, settings.enabledAtMs,
          event.pictures.map { it.detectionType } + event.thumbnail.detectionType
        )
      }.distinctBy { peopleJobId(it.deviceSn, it.megaEventId) }
      val existing = snapshot.tasks.map { it.jobId }.toSet() + snapshot.seen
      val fresh = eligible.filter { peopleJobId(it.deviceSn, it.megaEventId) !in existing }
      val finished = snapshot.tasks.filter { it.status == "completed" }.sortedByDescending { it.createdAtMs }.drop(100)
      if (finished.isNotEmpty()) {
        val retired = finished.map { it.jobId }.toSet()
        update { it.copy(tasks = it.tasks.filter { task -> task.jobId !in retired }, seen = (it.seen + retired).distinct().takeLast(20_000)) }
      }
      val available = (MaxPeopleTasks - snapshot.tasks.size).coerceAtLeast(0)
      val accepted = fresh.take(available)
      update { state ->
        val tasks = accepted.map { event -> PeopleTask(
          jobId = peopleJobId(event.deviceSn, event.megaEventId), sdkEventId = event.megaEventId,
          deviceSn = event.deviceSn, cameraId = settings.cameraMapping.getValue(event.deviceSn),
          configVersion = settings.configVersion, targetKey = settings.targetKey(event.deviceSn),
          eventJson = json.encodeToString(MegaEvent.serializer(), event), sdkTimestampMs = event.startTimestamp.takeIf { it >= 0 },
          createdAtMs = now, nextAttemptMs = now + 20_000,
          status = if (event.videoPath.isNotBlank() && event.endTimestamp > event.startTimestamp) "discovered" else "waiting_clip"
        ) }
        val refreshed = state.tasks.map { task ->
          val event = eligible.firstOrNull { peopleJobId(it.deviceSn, it.megaEventId) == task.jobId }
          if (event != null && eligibleTask(task) && !task.remoteAccepted && task.localPath == null && task.status in setOf("waiting_clip", "discovered")) {
            val previous = json.decodeFromString(MegaEvent.serializer(), task.eventJson)
            val ready = event.videoPath.isNotBlank() && event.endTimestamp > event.startTimestamp
            task.copy(eventJson = json.encodeToString(MegaEvent.serializer(), event),
              status = if (ready) "discovered" else "waiting_clip",
              nextAttemptMs = if (previous.endTimestamp != event.endTimestamp || previous.videoPath != event.videoPath) now + 20_000 else task.nextAttemptMs)
          } else task
        }
        state.copy(tasks = refreshed + tasks,
          settings = state.settings.copy(lastScanMs = if (fresh.size > available) settings.lastScanMs else now))
      }
      publish(if (fresh.size > available) "本地任务已达到 200 条限额；请先处理已有任务。" else null)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: PipelineStageFailure) { publish("SDK 人物事件查询失败（${failure.code}），稍后重试。") }
    catch (_: Exception) { publish("SDK 人物事件查询失败，稍后重试；不会上传样例或跳过本轮事件。") }
  }

  private suspend fun queryEvents(devices: List<MegaDevice>, startMs: Long, endMs: Long): List<MegaEvent> = queryLock.withLock { stage("discovery", "query") { withTimeout(25_000) {
    val condition = EventCondition(_parentSnList = devices.map { it.parentSn.ifBlank { it.deviceSn } }.distinct(), _startTime = startMs, _endTime = endMs)
      .setDeviceSnList(devices.map { it.deviceSn }).setDetectionTypeList(PeopleDetectionTypes).setCount(200)
    suspendCancellableCoroutine { continuation ->
      val request = MegaEventManager.query(condition, refresh = true, lastEventTime = 0,
        onSuccess = { events ->
          if (continuation.isActive) {
            try { continuation.resume(events.toList()) }
            catch (_: Exception) { continuation.resumeWithException(IOException("event_snapshot_failed")) }
          }
        }, onFailure = { _, _ -> if (continuation.isActive) continuation.resumeWithException(IOException("sdk_event_query_failed")) })
      continuation.invokeOnCancellation { request.cancel() }
    }
  } } }

  private fun eligibleTask(task: PeopleTask): Boolean = task.targetKey == snapshot.settings.targetKey(task.deviceSn)

  private suspend fun processOne() {
    if (authPaused || storageFailed) return
    val task = snapshot.tasks.firstOrNull {
      eligibleTask(it) && !it.remoteAccepted && it.status !in setOf("completed", "failed", "waiting_clip") && it.nextAttemptMs <= System.currentTimeMillis()
    } ?: return
    try {
      var prepared = task
      val local = task.localPath?.let(::File)
      if (local == null || !local.exists()) {
        patchTask(task.jobId) { it.copy(status = "downloading", error = null) }
        val event = stage(task.jobId, "event_decode") { json.decodeFromString(MegaEvent.serializer(), task.eventJson) }
        val file = download(task, event)
        val duration = stage(task.jobId, "mp4_probe") { probeVideo(file) }
        prepared = task.copy(localPath = file.absolutePath, durationMs = duration, attempts = 0, status = "ready")
        patchTask(task.jobId) { prepared }
      } else {
        val actualDuration = stage(task.jobId, "restore_mp4_probe") { probeVideo(local) }
        if (actualDuration != task.durationMs) throw InvalidPeopleVideo("本地录像与已登记任务不一致，停止重复提交。")
      }
      patchTask(task.jobId) { it.copy(status = "uploading", error = null) }
      val manifest = buildJsonObject {
        put("job_id", prepared.jobId); put("camera_id", prepared.cameraId)
        put("config_version", prepared.configVersion); put("sdk_event_id", prepared.sdkEventId)
        put("event_type", "people"); put("duration_ms", prepared.durationMs)
        prepared.sdkTimestampMs?.let { put("sdk_timestamp_ms", it) }
      }
      val request = peopleSourceUploadRequest(snapshot.settings.baseUrl, snapshot.settings.bearerToken,
        manifest.toString(), File(prepared.localPath!!))
      val response = stage(task.jobId, "backend_upload") { requestJson(request) }
      stage(task.jobId, "upload_status_apply") { applyRemote(task.jobId, response.getValue("job").jsonObject) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: PipelineHttpFailure) { handleFailure(task, failure) }
    catch (failure: InvalidPeopleVideo) { patchTask(task.jobId) { it.copy(status = "failed", error = failure.message, retryable = false) } }
    catch (failure: PipelineStageFailure) { retryLater(task.jobId, "录像处理阶段失败（${failure.code}），请检查设备与网络。") }
    catch (_: Exception) { retryLater(task.jobId, "录像准备或上传失败，请检查设备与网络。") }
  }

  private suspend fun download(task: PeopleTask, event: MegaEvent): File {
    stage(task.jobId, "download_storage") { withContext(Dispatchers.IO) {
      if ((mediaDir.listFiles()?.sumOf { it.length() } ?: 0L) + MaxPeopleVideoBytes > 512L * 1024 * 1024) throw IOException("local_media_budget_exhausted")
      // A killed SDK download may have left an unconfirmed partial file at this private target.
      File(mediaDir, "${task.jobId}.mp4").delete()
      File(mediaDir, "${task.jobId}.part").delete()
    } }
    val device = MegaDeviceManager.getDeviceBy(task.deviceSn) ?: throw PipelineStageFailure("device_missing")
    val ready = CompletableDeferred<EventMediaPlayer>()
    val playerSlot = PeoplePlayerRequest(task.jobId, event, device, ready)
    playerRequest.value = playerSlot
    var player: EventMediaPlayer? = null
    val destination = File(mediaDir, "${task.jobId}.mp4")
    try {
      val activePlayer = stage(task.jobId, "player_ready") { withTimeout(10_000) { ready.await() } }
      player = activePlayer
      val result = CompletableDeferred<String>()
      val playing = CompletableDeferred<Unit>()
      var previousDownloadState: String? = null
      stage(task.jobId, "listener_attach") {
      activePlayer.setPlayListener(object : EventPlayListener {
        override fun onPlayState(status: EventPlayerState, message: String) {
          safeStage(task.jobId, "play_status_${status.name.lowercase()}")
          when (status) {
            EventPlayerState.Playing -> playing.complete(Unit)
            EventPlayerState.Failed, EventPlayerState.Timeout -> {
              val failure = PipelineStageFailure("play_status_${status.name.lowercase()}")
              playing.completeExceptionally(failure)
              result.completeExceptionally(failure)
            }
            else -> Unit
          }
        }
        override fun onDownloadState(status: EventDownloadState, progress: Float, message: String) {
          if (previousDownloadState != status.name) {
            previousDownloadState = status.name
            safeStage(task.jobId, "download_status_${status.name.lowercase()}")
          }
          when (status) {
            EventDownloadState.Success -> result.complete(message.ifBlank { destination.absolutePath })
            EventDownloadState.Failed -> result.completeExceptionally(PipelineStageFailure("download_status_failed"))
            EventDownloadState.Timeout -> result.completeExceptionally(PipelineStageFailure("download_status_timeout"))
            else -> Unit
          }
        }
        override fun onMuteState(isMute: Boolean) = Unit
        override fun onProgress(progress: Float) = Unit
        override fun onVideoInfo(info: VideoInfo) = Unit
      })
      }
      // P2PHistoryPlayer creation inside startPlay initializes JNIBaseMedia for a fresh process.
      // The download helper alone lacks that initialization and otherwise hits its 15 s watchdog.
      // setMute must follow Playing: calling it before startPlay dereferences an absent native player.
      stage(task.jobId, "play_start") { activePlayer.startPlay() }
      stage(task.jobId, "play_ready") { withTimeout(30_000) { playing.await() } }
      stage(task.jobId, "play_mute") { activePlayer.setMute(true) }
      stage(task.jobId, "play_pause") { activePlayer.pause() }
      stage(task.jobId, "download_start") { activePlayer.startDownload(destination.absolutePath) }
      val sdkPath = stage(task.jobId, "download_complete") { withTimeout(120_000) { result.await() } }
      return stage(task.jobId, "download_file_copy") { withContext(Dispatchers.IO) {
        val source = File(sdkPath).takeIf { it.exists() && it.length() > 0 }
          ?: destination.takeIf { it.exists() && it.length() > 0 } ?: throw IOException("download_file_missing")
        if (source.length() > MaxPeopleVideoBytes) throw InvalidPeopleVideo("录像超过 64 MiB 上传限额。")
        if (source.canonicalPath != destination.canonicalPath) {
          val temp = File(mediaDir, "${task.jobId}.part")
          source.inputStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
          if (!temp.renameTo(destination)) throw IOException("private_video_copy_failed")
        }
        destination
      } }
    } finally {
      withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
        val createdPlayer = player ?: playerSlot.createdPlayer
        runCatching { createdPlayer?.stopDownload() }
        runCatching { createdPlayer?.stopAndRelease() }
        playerRequest.value = null
        safeStage(task.jobId, "player_released")
      }
    }
  }

  private suspend fun probeVideo(file: File): Long = withContext(Dispatchers.IO) {
    if (!file.exists() || file.length() !in 1..MaxPeopleVideoBytes) throw InvalidPeopleVideo("录像文件为空或超过 64 MiB。")
    val header = ByteArray(12)
    file.inputStream().use { if (it.read(header) != header.size) throw InvalidPeopleVideo("录像不是完整的 MP4。") }
    if (String(header, 4, 4, Charsets.US_ASCII) != "ftyp") throw InvalidPeopleVideo("录像格式必须为 MP4。")
    val metadata = MediaMetadataRetriever()
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(file.absolutePath)
      val videoTracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        .filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
      if (videoTracks.size != 1) throw InvalidPeopleVideo("录像缺少有效时长或不是单路连续视频。")
      val videoFormat = videoTracks.single()
      // The source contract validates the video stream, not a longer audio/container tail.
      val videoDurationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) videoFormat.getLong(MediaFormat.KEY_DURATION) else null
      val duration = videoDurationUs?.takeIf { it > 0 }?.let { TimeUnit.MICROSECONDS.toMillis(it) }
        ?: run {
          metadata.setDataSource(file.absolutePath)
          metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        }
      if (duration <= 0) throw InvalidPeopleVideo("录像缺少有效时长或不是单路连续视频。")
      duration
    } finally { metadata.release(); extractor.release() }
  }

  private suspend fun pollRemote() {
    if (authPaused || storageFailed) return
    snapshot.tasks.filter { eligibleTask(it) && shouldReadPeopleRemoteStatus(it) && it.nextAttemptMs <= System.currentTimeMillis() }.take(20).forEach { task ->
      if (authPaused || storageFailed) return@forEach
      try {
        val response = stage(task.jobId, "backend_status") { requestJson(authorizedRequest("/api/v1/recording-jobs/${task.jobId}").get().build()) }
        applyRemote(task.jobId, response.getValue("job").jsonObject)
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: PipelineHttpFailure) {
        if (failure.code == 401 || failure.code == 403) handleFailure(task, failure)
        else retryLater(task.jobId, "解析状态暂时无法读取（HTTP ${failure.code}），将自动回查。")
      }
      catch (failure: PipelineStageFailure) { retryLater(task.jobId, "解析状态暂时无法读取（${failure.code}），将自动回查。") }
      catch (_: Exception) { retryLater(task.jobId, "解析状态暂时无法读取，将自动回查。") }
    }
    snapshot.tasks.filter { it.status == "completed" && !it.completionDelivered && _completions.subscriptionCount.value > 0 }.forEach { task ->
      _completions.emit(PeoplePipelineCompletion(task.jobId, task.jobId, task.eventCount, task.evidenceCount))
      patchTask(task.jobId) { it.copy(completionDelivered = true) }
    }
  }

  private suspend fun applyRemote(id: String, remote: JsonObject) {
    if (remote["job_id"]?.jsonPrimitive?.content != id) throw IOException("unexpected_job_identity")
    val status = remote["status"]?.jsonPrimitive?.content
    if (status !in setOf("queued", "processing", "completed", "failed")) throw IOException("unexpected_job_status")
    val previous = snapshot.tasks.firstOrNull { it.jobId == id }
    val errorCode = remote["error_code"]?.jsonPrimitive?.content?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,79}")) && it != "null" }
    patchTask(id) { it.copy(status = status!!, remoteAccepted = true, attempts = 0, nextAttemptMs = 0,
      error = if (status == "failed") "Vision 解析失败${errorCode?.let { code -> "（$code）" }.orEmpty()}，请检查服务端任务与算法配置。" else null,
      retryable = false, eventCount = remote["event_count"]?.jsonPrimitive?.intOrNull ?: 0,
      evidenceCount = remote["evidence_count"]?.jsonPrimitive?.intOrNull ?: 0) }
    if (status == "completed") {
      withContext(Dispatchers.IO) { previous?.localPath?.let { File(it).delete() } }
      patchTask(id) { it.copy(localPath = null) }
    }
  }

  private fun authorizedRequest(path: String): Request.Builder = Request.Builder()
    .url(snapshot.settings.baseUrl + path).header("Authorization", "Bearer ${snapshot.settings.bearerToken}")

  private suspend fun requestJson(request: Request): JsonObject {
    val response = suspendCancellableCoroutine<Response> { continuation ->
      val call = client.newCall(request)
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("backend_unavailable")) }
        override fun onResponse(call: Call, response: Response) {
          if (continuation.isActive) continuation.resume(response) else response.close()
        }
      })
    }
    return withContext(Dispatchers.IO) {
      response.use {
        if (it.code !in 200..299) throw PipelineHttpFailure(it.code)
        val body = it.body ?: throw IOException("backend_response_empty")
        if (body.contentLength() > 256 * 1024) throw IOException("backend_response_too_large")
        val bytes = body.byteStream().use { input ->
          val output = ByteArrayOutputStream()
          val buffer = ByteArray(4096)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > 256 * 1024) throw IOException("backend_response_too_large")
            output.write(buffer, 0, count)
          }
          output.toByteArray()
        }
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
      }
    }
  }

  private suspend fun handleFailure(task: PeopleTask, failure: PipelineHttpFailure) {
    when (failure.code) {
      401, 403 -> {
        authPaused = true
        patchTask(task.jobId) { it.copy(error = "后端凭据或摄像头授权无效，请更新配置。") }
        publish("后端授权失败，自动请求已暂停；请检查独立的 Bearer 凭据与权限。")
      }
      409, 413, 422 -> patchTask(task.jobId) { it.copy(status = "failed", retryable = false,
        error = when (failure.code) { 409 -> "任务标识与已提交内容冲突。"; 413 -> "录像超过服务端上传限额。"; else -> RejectedPeopleSourceMessage }) }
      else -> retryLater(task.jobId, "服务暂时无法处理请求，请稍后重试。")
    }
  }

  private suspend fun retryLater(id: String, message: String) = patchTask(id) { current ->
    deferPeopleTaskAfterFailure(current, message, System.currentTimeMillis())
  }

  private suspend fun patchTask(id: String, transform: (PeopleTask) -> PeopleTask) = update { state ->
    state.copy(tasks = state.tasks.map { if (it.jobId == id) transform(it) else it })
  }

  /** The diagnostic channel contains only fixed stage labels and a shortened hash, never SDK messages. */
  private fun safeStage(id: String, code: String) {
    val label = if (id.matches(Regex("[0-9a-f]{64}"))) id.take(12) else "discovery"
    if (code.matches(Regex("[a-z0-9_]{1,80}"))) Log.i("PeoplePipeline", "task=$label stage=$code")
  }

  private suspend fun <T> stage(id: String, code: String, action: suspend () -> T): T {
    safeStage(id, "${code}_start")
    try {
      val value = action()
      safeStage(id, "${code}_ok")
      return value
    } catch (_: TimeoutCancellationException) {
      safeStage(id, "${code}_timeout")
      throw PipelineStageFailure("${code}_timeout")
    } catch (cancelled: CancellationException) {
      safeStage(id, "${code}_cancelled")
      throw cancelled
    } catch (failure: PipelineStageFailure) {
      safeStage(id, failure.code)
      throw failure
    } catch (failure: PipelineHttpFailure) {
      safeStage(id, "${code}_http_${failure.code}")
      throw failure
    } catch (failure: InvalidPeopleVideo) {
      safeStage(id, "${code}_invalid_media")
      throw failure
    } catch (failure: Exception) {
      val kind = when (failure) {
        is SecurityException -> "security"
        is java.io.FileNotFoundException -> "file_missing"
        is NullPointerException -> "null_state"
        is IllegalStateException -> "illegal_state"
        is IOException -> "io"
        else -> "sdk"
      }
      val stageCode = "${code}_$kind"
      safeStage(id, stageCode)
      throw PipelineStageFailure(stageCode)
    }
  }

  private class PipelineStageFailure(val code: String) : IOException("pipeline_stage_failed")
  private class PipelineHttpFailure(val code: Int) : IOException("backend_http_failure")
  private class InvalidPeopleVideo(message: String) : IOException(message)
}
