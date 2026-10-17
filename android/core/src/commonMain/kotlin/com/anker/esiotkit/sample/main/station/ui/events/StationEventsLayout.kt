package com.anker.esiotkit.sample.main.station.ui.events

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.station.events.StationEventCamera
import com.anker.esiotkit.sample.main.station.events.StationEventType
import com.anker.esiotkit.sample.main.station.ui.components.StationBottomBar
import com.anker.esiotkit.sample.main.station.ui.components.StationIcon
import com.anker.esiotkit.sample.main.station.ui.components.StationSymbol

internal data class StationEventDisplayItem(
  val id: String,
  val deviceName: String,
  val timeLabel: String,
  val typeLabel: String,
  val durationLabel: String,
  val canPlay: Boolean
)

internal data class StationEventsDisplayState(
  val events: List<StationEventDisplayItem> = emptyList(),
  val loading: Boolean = false,
  val errorMessage: String? = null,
  val needsLogin: Boolean = false,
  val devicesLoading: Boolean = false,
  val devicesError: String? = null,
  val noCameras: Boolean = false,
  val limitReached: Boolean = false
)

private val EventsBlue = Color(0xFF50A7FA)
private val EventsInk = Color(0xFF333333)
private val EventsMuted = Color(0xFF808995)

/** Presentation only: the host supplies the camera records, thumbnails and playback actions. */
@Composable
internal fun StationEventsLayout(
  dateLabel: String,
  cameras: List<StationEventCamera>,
  selectedCameraSn: String?,
  selectedType: StationEventType,
  state: StationEventsDisplayState,
  onSelectDate: () -> Unit,
  onSelectCamera: (String?) -> Unit,
  onSelectType: (StationEventType) -> Unit,
  onRefresh: () -> Unit,
  onLogin: () -> Unit,
  onTab: (String) -> Unit,
  onEvent: (String) -> Unit,
  thumbnail: @Composable (StationEventDisplayItem, Modifier) -> Unit,
  previewLabel: String? = null
) {
  var cameraPickerVisible by rememberSaveable { mutableStateOf(false) }
  val selectedCamera = cameras.firstOrNull { it.sn == selectedCameraSn }
  val cameraLabel = when {
    selectedCameraSn == null -> "全部设备"
    selectedCamera != null -> selectedCamera.name
    else -> "所选设备已不可用"
  }
  val canSelectCamera = !state.needsLogin && !state.devicesLoading &&
    state.devicesError == null && cameras.isNotEmpty()
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      Spacer(Modifier.height(28.dp))
      Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("所有事件", color = Color(0xFF555555), fontSize = 30.sp, lineHeight = 40.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
          Text("摄像头检测记录", color = Color(0xFF536D82), fontSize = 14.sp)
        }
        TextButton(onClick = onRefresh,
          enabled = !state.loading && !state.devicesLoading && !state.needsLogin,
          modifier = Modifier.heightIn(min = 44.dp).background(Color.White, CircleShape)) {
          Text(if (state.loading) "刷新中" else "刷新",
            color = if (state.loading || state.devicesLoading || state.needsLogin) EventsMuted else EventsBlue)
        }
      }
      Spacer(Modifier.height(24.dp))
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StationEventSelector(dateLabel, onSelectDate, Modifier.weight(1f))
        StationEventSelector(cameraLabel, { cameraPickerVisible = true }, Modifier.weight(1.15f), canSelectCamera)
      }
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StationEventType.entries.forEach { type ->
          val selected = selectedType == type
          Text(if (type == StationEventType.All) "全部" else type.label,
            color = if (selected) Color.White else EventsInk, fontSize = 14.sp,
            modifier = Modifier.clip(CircleShape)
              .background(if (selected) EventsBlue else Color.White)
              .heightIn(min = 44.dp).selectable(selected, role = Role.RadioButton, onClick = { onSelectType(type) })
              .padding(horizontal = 16.dp, vertical = 11.dp))
        }
      }
      previewLabel?.let {
        Text(it, color = Color(0xFF9A691D), fontSize = 12.sp,
          modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp))
      }
      // Keep refresh/error feedback outside the event list. Adding an item ahead
      // of retained cards would shift the saved index when returning from playback.
      val retainedError = state.errorMessage.takeIf { state.events.isNotEmpty() }
      Text(retainedError ?: "来自摄像头的检测记录，可能包含缓存。",
        color = if (retainedError != null) Color(0xFFB54835) else EventsMuted, fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp))
      LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
          state.needsLogin -> item("sign-in") {
            StationEventsNotice("登录后查看事件", "使用摄像头设备账号登录，读取当前账号的检测记录。", "登录设备账号", onLogin)
          }
          state.devicesLoading -> item("devices-loading") {
            StationEventsNotice("正在读取设备", "设备列表就绪后即可查询事件。", loading = true)
          }
          state.devicesError != null -> item("devices-error") {
            StationEventsNotice("设备列表加载失败", state.devicesError, "重试", onRefresh)
          }
          state.noCameras -> item("no-cameras") {
            StationEventsNotice("暂无可查询的摄像头", "当前设备账号下没有可查询事件的摄像头。", "刷新设备", onRefresh)
          }
          else -> {
            state.errorMessage?.takeIf { state.events.isEmpty() }?.let { message -> item("query-error") {
              StationEventsNotice("事件加载失败", message, "重试", onRefresh)
            } }
            if (state.loading && state.events.isEmpty()) item("events-loading") {
              StationEventsNotice("正在加载事件", "正在读取所选日期的摄像头检测记录。", loading = true)
            }
            if (!state.loading && state.errorMessage == null && state.events.isEmpty()) item("empty-events") {
              StationEventsNotice("暂未返回事件", "当前日期和筛选条件未返回检测记录，可切换日期或设备查看。")
            }
            items(state.events, key = { "event:${it.id}" }) { event ->
              StationEventCard(event, { onEvent(event.id) }, thumbnail)
            }
            if (state.limitReached) item("query-limit") {
              Text("已达到本次 200 条查询上限，结果可能不完整。",
                color = EventsMuted, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp))
            }
          }
        }
      }
    }
    StationBottomBar("events", onTab, Modifier.align(Alignment.BottomCenter))
  }
  if (cameraPickerVisible) AlertDialog(
    onDismissRequest = { cameraPickerVisible = false },
    title = { Text("选择设备") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        TextButton(onClick = { onSelectCamera(null); cameraPickerVisible = false }, modifier = Modifier.fillMaxWidth()) {
          Text(if (selectedCameraSn == null) "全部设备 · 已选" else "全部设备")
        }
        cameras.forEach { camera ->
          TextButton(onClick = { onSelectCamera(camera.sn); cameraPickerVisible = false }, modifier = Modifier.fillMaxWidth()) {
            Text(camera.name + if (selectedCameraSn == camera.sn) " · 已选" else "")
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = { cameraPickerVisible = false }) { Text("完成") } }
  )
}

@Composable
private fun StationEventSelector(label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true) {
  Row(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
    .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = if (enabled) EventsInk else EventsMuted, fontSize = 14.sp,
      maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Spacer(Modifier.width(6.dp))
    StationIcon(StationSymbol.Chevron, modifier = Modifier.size(13.dp), color = EventsMuted)
  }
}

@Composable
private fun StationEventsNotice(title: String, message: String, actionLabel: String? = null,
  onAction: () -> Unit = {}, loading: Boolean = false) {
  Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(22.dp)).padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally) {
    if (loading) {
      CircularProgressIndicator(Modifier.size(26.dp), color = EventsBlue, strokeWidth = 2.dp)
      Spacer(Modifier.height(16.dp))
    }
    Text(title, color = EventsInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    Text(message, color = EventsMuted, fontSize = 13.sp, lineHeight = 20.sp)
    actionLabel?.let {
      Spacer(Modifier.height(10.dp))
      TextButton(onClick = onAction) { Text(it, color = EventsBlue) }
    }
  }
}

@Composable
private fun StationEventCard(event: StationEventDisplayItem, onClick: () -> Unit,
  thumbnail: @Composable (StationEventDisplayItem, Modifier) -> Unit) {
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
    .background(Color.White).border(BorderStroke(1.dp, Color(0xFFEDF1F5)), RoundedCornerShape(22.dp))
    .clickable(onClick = onClick).padding(14.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.width(108.dp).height(78.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEDF1F5)),
        contentAlignment = Alignment.Center) {
        thumbnail(event, Modifier.fillMaxSize())
        if (event.canPlay) Box(Modifier.size(30.dp).background(Color.White.copy(alpha = .9f), CircleShape),
          contentAlignment = Alignment.Center) {
          StationIcon(StationSymbol.Play, modifier = Modifier.size(20.dp), color = EventsBlue)
        }
      }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        Text(event.deviceName, color = EventsInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(5.dp))
        Text(event.timeLabel, color = EventsMuted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text(event.typeLabel, color = EventsBlue, fontSize = 12.sp,
          modifier = Modifier.background(EventsBlue.copy(alpha = .08f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp))
      }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("时长 ${event.durationLabel}", color = EventsMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
      TextButton(onClick = onClick) {
        Text(if (event.canPlay) "查看片段" else "查看记录", color = if (event.canPlay) EventsBlue else EventsMuted, fontSize = 13.sp)
      }
    }
  }
}
