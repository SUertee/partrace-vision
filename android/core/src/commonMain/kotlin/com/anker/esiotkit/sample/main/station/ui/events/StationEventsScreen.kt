package com.anker.esiotkit.sample.main.station.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.sample.main.station.StationDeviceState
import com.anker.esiotkit.sample.main.station.events.*
import com.anker.esiotkit.sample.support.rememberEventImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
internal fun StationEventsScreen(
  deviceState: StationDeviceState,
  nowMillis: () -> Long,
  onRefreshDevices: () -> Unit,
  onSdkTools: () -> Unit,
  onTab: (String) -> Unit,
) {
  val navigator = LocalNavigator.currentOrThrow
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  val zone = TimeZone.currentSystemDefault()
  val today = Instant.fromEpochMilliseconds(nowMillis()).toLocalDateTime(zone).date
  var dateText by rememberSaveable { mutableStateOf(today.toString()) }
  var selectedCameraSn by rememberSaveable { mutableStateOf<String?>(null) }
  var typeName by rememberSaveable { mutableStateOf(StationEventType.All.name) }
  var refreshVersion by remember { mutableStateOf(0) }
  var showDatePicker by remember { mutableStateOf(false) }
  var actionError by remember { mutableStateOf<String?>(null) }
  val date = LocalDate.parse(dateText)
  val type = StationEventType.entries.firstOrNull { it.name == typeName } ?: StationEventType.All
  val cameras = if (deviceState.needsLogin) emptyList() else {
    // Observing the SDK snapshot starts no network query and keeps account/device changes visible.
    val sdkDevices by MegaDeviceManager.deviceList.collectAsState()
    remember(sdkDevices, deviceState.devices) {
      val names = deviceState.devices.associate { it.sn to it.name }
      currentStationEventCameras().map { it.copy(name = names[it.sn] ?: it.name) }
    }
  }
  val query = remember(date, zone, selectedCameraSn, type) { StationEventQuery(date, zone, selectedCameraSn, type) }
  // Retain the last in-memory result on the Voyager root while opening playback.
  // Returning can then restore the lazy-list position without a temporary empty list
  // clamping its saved index. SDK objects never enter saveable/navigation state.
  val memory = navigator.items.first().rememberScreenModel(tag = "station-event-results") { StationEventsMemory() }
  var result by memory.result
  var loadedQuery by memory.query
  var loadedCameras by memory.cameras
  var loading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(query, cameras, refreshVersion, deviceState.needsLogin, deviceState.loading, deviceState.errorMessage, foreground) {
    if (loadedQuery != query || !sameCameraScope(loadedCameras, cameras)) result = null
    error = null
    if (deviceState.needsLogin || cameras.isEmpty()) {
      result = null
      loading = false
      return@LaunchedEffect
    }
    // Returning from playback briefly refreshes the parent device state. Keep the
    // same account/camera scope's cached cards so its saved list anchor survives.
    if (deviceState.loading || deviceState.errorMessage != null || !foreground) {
      loading = false
      return@LaunchedEffect
    }
    loadedQuery = query
    loadedCameras = cameras
    loading = true
    try {
      result = loadStationEvents(query, cameras).also { StationDetailSdkCache.retain(it.events) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: StationEventsException) {
      error = failure.message
    } catch (_: Exception) {
      error = "摄像头事件加载失败，请重试。"
    } finally {
      loading = false
    }
  }

  // A changed scope must never briefly render cards from the previous query.
  val currentResult = result.takeIf { loadedQuery == query && sameCameraScope(loadedCameras, cameras) && !deviceState.needsLogin }
  val currentError = error.takeIf { loadedQuery == query && sameCameraScope(loadedCameras, cameras) }
  val hasScopedSnapshot = currentResult != null
  val displayedEvents = currentResult?.events.orEmpty().map { card ->
    StationEventDisplayItem(
      id = card.key,
      deviceName = cameras.firstOrNull { it.sn == card.deviceSn }?.name ?: card.deviceName,
      timeLabel = eventTimeLabel(card.startMillis, zone),
      typeLabel = card.typeLabels.joinToString(" · "),
      durationLabel = if (card.durationMillis > 0) eventDurationLabel(card.durationMillis) else "无录像时长",
      canPlay = card.event.videoPath.isNotBlank() && card.durationMillis > 0,
    )
  }
  StationEventsLayout(
    dateLabel = if (date.year != today.year) "${date.year}/${date.monthNumber}/${date.dayOfMonth}"
      else "${date.monthNumber}月${date.dayOfMonth}日" + if (date == today) " · 今天" else "",
    cameras = cameras,
    selectedCameraSn = selectedCameraSn,
    selectedType = type,
    state = StationEventsDisplayState(
      events = displayedEvents,
      loading = loading || (deviceState.loading && hasScopedSnapshot) ||
        (loadedQuery != query || !sameCameraScope(loadedCameras, cameras)) &&
        !deviceState.needsLogin && !deviceState.loading && deviceState.errorMessage == null && cameras.isNotEmpty(),
      errorMessage = if (deviceState.errorMessage != null && hasScopedSnapshot)
        "设备列表刷新失败，以下为上次读取的记录。${deviceState.errorMessage}" else currentError,
      needsLogin = deviceState.needsLogin,
      devicesLoading = deviceState.loading && !hasScopedSnapshot,
      devicesError = deviceState.errorMessage.takeUnless { hasScopedSnapshot },
      noCameras = cameras.isEmpty(),
      limitReached = currentResult?.limitReached == true,
    ),
    onSelectDate = { showDatePicker = true },
    onSelectCamera = { selectedCameraSn = it },
    onSelectType = { typeName = it.name },
    onRefresh = {
      if (deviceState.errorMessage != null || cameras.isEmpty()) onRefreshDevices()
      else if (!loading) refreshVersion++
    },
    onLogin = onSdkTools,
    onTab = onTab,
    onEvent = { id ->
      currentResult?.events?.firstOrNull { it.key == id }?.let { card ->
        when {
          MegaDeviceManager.getDeviceBy(card.deviceSn) == null -> actionError = "设备信息已变化，请刷新设备后重试。"
          else -> navigator.push(StationEventDetailScreen.sdk(card))
        }
      }
    },
    thumbnail = { item, modifier ->
      val event = currentResult?.events?.firstOrNull { it.key == item.id }?.event
      EventThumbnail(event?.thumbnail, modifier)
    },
  )

  if (showDatePicker) {
    // Material DatePicker represents calendar dates at UTC midnight; convert the chosen
    // date to the phone's zone only when building the actual SDK query boundaries.
    val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
    DatePickerDialog(onDismissRequest = { showDatePicker = false },
      confirmButton = {
        TextButton(onClick = {
          picker.selectedDateMillis?.let { dateText = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date.toString() }
          showDatePicker = false
        }, enabled = picker.selectedDateMillis != null) { Text("确定") }
      },
      dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
    ) { DatePicker(state = picker) }
  }
  actionError?.let { message ->
    AlertDialog(onDismissRequest = { actionError = null }, title = { Text("暂时无法打开") },
      text = { Text(message) }, confirmButton = { TextButton(onClick = { actionError = null }) { Text("知道了") } })
  }
}

@Composable
internal fun EventThumbnail(picture: com.anker.esiotkit.domain.model.MegaPicture?, modifier: Modifier) {
  var failed by remember(picture?.pictureId, picture?.picturePath) { mutableStateOf(false) }
  var ready by remember(picture?.pictureId, picture?.picturePath) { mutableStateOf(false) }
  Box(modifier.background(Color(0xFFECF3F8)), contentAlignment = Alignment.Center) {
    if (!ready) Text(when {
      picture == null || picture.picturePath.isBlank() -> "暂无缩略图"
      failed -> "缩略图不可用"
      else -> "正在加载…"
    }, color = Color(0xFF8796A3), fontSize = 11.sp)
    if (picture != null && picture.picturePath.isNotBlank() && !failed) {
      AsyncImage(model = rememberEventImageRequest(picture), contentDescription = "摄像头检测缩略图",
        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        onError = { failed = true }, onSuccess = { ready = true })
    }
  }
}

private fun eventTimeLabel(millis: Long, zone: TimeZone): String {
  val time = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).time
  return listOf(time.hour, time.minute, time.second).joinToString(":") { it.toString().padStart(2, '0') }
}

private fun eventDurationLabel(millis: Long): String {
  val seconds = (millis / 1000).coerceAtLeast(1)
  return if (seconds >= 60) "${seconds / 60}分${seconds % 60}秒" else "${seconds}秒"
}


private class StationEventsMemory : ScreenModel {
  val result = mutableStateOf<StationEventResult?>(null)
  val query = mutableStateOf<StationEventQuery?>(null)
  val cameras = mutableStateOf<List<StationEventCamera>>(emptyList())
}


private fun sameCameraScope(first: List<StationEventCamera>, second: List<StationEventCamera>): Boolean =
  first.map { it.sn to it.parentSn }.toSet() == second.map { it.sn to it.parentSn }.toSet()
