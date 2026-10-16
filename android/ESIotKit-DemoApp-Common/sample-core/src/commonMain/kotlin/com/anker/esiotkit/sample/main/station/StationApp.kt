package com.anker.esiotkit.sample.main.station

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.ui.composable.BackHandler
import kotlinx.coroutines.delay

private val Ink = Color(0xFF333333)
private val PageWhite = Color(0xFFFAFAFA)
private val Sky = Color(0xFF79BDF7)
private enum class StationOverlay { Sites, Mute, DeviceMenu, DeviceStatus, MultiPreview }

/** Station interface backed by the authenticated SDK device state supplied by its host. */
@Composable
fun StationApp(
  deviceState: StationDeviceState,
  onRefreshDevices: () -> Unit,
  onOpenDevice: (String) -> Unit,
  onDeviceSettings: (String) -> Unit,
  devicePreview: @Composable (StationDeviceCard, Modifier) -> Unit,
  onSdkTools: () -> Unit,
  onNotificationSettings: () -> Unit,
  nowMillis: () -> Long,
  initialPage: String = "home",
  initialCampusMute: Long = 0L,
  initialVenueMute: Long = 0L,
  onScreenChanged: (String) -> Unit = {},
  onMuteChanged: (station: String, until: Long) -> Unit = { _, _ -> },
  onPeopleSettings: () -> Unit = {}
) {
  var page by rememberSaveable { mutableStateOf(initialPage) }
  var messageOrigin by rememberSaveable { mutableStateOf("home") }
  var reviewOrigin by rememberSaveable { mutableStateOf("profile") }
  var station by rememberSaveable { mutableStateOf("校园驿站") }
  var campusMute by rememberSaveable { mutableStateOf(initialCampusMute) }
  var venueMute by rememberSaveable { mutableStateOf(initialVenueMute) }
  var overlay by remember { mutableStateOf<StationOverlay?>(null) }
  var assistantSettingsRequested by remember { mutableStateOf(false) }
  val pageState = rememberSaveableStateHolder()
  fun openMessages() {
    messageOrigin = page
    page = "messages"
  }
  fun openReviews() {
    reviewOrigin = page
    page = "reviews"
  }
  val mutedUntil = if (station == "校园驿站") campusMute else venueMute
  fun updateMute(until: Long) {
    if (station == "校园驿站") campusMute = until else venueMute = until
    onMuteChanged(station, until)
  }
  val muted = mutedUntil > nowMillis()
  SideEffect { onScreenChanged(page) }
  LaunchedEffect(station, mutedUntil) {
    if (mutedUntil != 0L && mutedUntil != Long.MAX_VALUE) {
      delay((mutedUntil - nowMillis()).coerceAtLeast(0L))
      updateMute(0L)
    }
  }
  BackHandler(enabled = overlay != null || page != "home") {
    if (overlay != null) overlay = null else page = when (page) {
      "messages" -> messageOrigin
      "reviews" -> reviewOrigin
      else -> "home"
    }
  }
  MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF50A7FA), background = PageWhite, surface = Color.White)) {
    Box(Modifier.fillMaxSize().background(if (page == "assistant") Color(0xFFEDFCFE) else if (page in setOf("messages", "reviews")) Color(0xFFF6F6F6) else PageWhite)) {
      if (page == "home") {
        Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(
          0f to Color(0xFF9CCFF8), 0.42f to Sky, 1f to PageWhite)))
      }
      Box(Modifier.fillMaxSize().systemBarsPadding().then(if (overlay != null) Modifier.clearAndSetSemantics {} else Modifier)) {
        pageState.SaveableStateProvider(page) {
        when (page) {
          "home" -> HomeScreen(
            station = station,
            muted = muted,
            onMute = {
              if (muted) updateMute(0L) else overlay = StationOverlay.Mute
            },
            onStation = { overlay = StationOverlay.Sites },
            onMessages = ::openMessages,
            onMenu = { overlay = StationOverlay.DeviceMenu },
            onPreview = { overlay = StationOverlay.MultiPreview },
            deviceState = deviceState,
            onRefreshDevices = onRefreshDevices,
            onSdkTools = onSdkTools,
            onOpenDevice = onOpenDevice,
            onDeviceSettings = onDeviceSettings,
            devicePreview = devicePreview,
            onTab = { page = it }
          )
          "assistant" -> TraceAssistantScreen(onBack = { page = "home" },
            openServiceSettings = assistantSettingsRequested, onServiceSettingsOpened = { assistantSettingsRequested = false })
          "messages" -> MessageCenterScreen(onBack = { page = messageOrigin }, onNotificationSettings = onNotificationSettings, onPeopleSettings = onPeopleSettings)
          "reviews" -> ReviewRecordsScreen(onBack = { page = reviewOrigin },
            onNotificationSettings = onNotificationSettings,
            onConfigureService = { assistantSettingsRequested = true; page = "assistant" })
          "profile" -> ProfileScreen(onTab = { page = it }, onSdkTools = onSdkTools, onMessages = ::openMessages,
            onReviews = ::openReviews, onPeopleSettings = onPeopleSettings)
        }
        }
      }
      overlay?.let { shown ->
        val dismiss = { overlay = null }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f))
          .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = dismiss))
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
          when (shown) {
            StationOverlay.Sites -> {
              Column(Modifier.padding(start = 16.dp, top = 87.dp, end = 16.dp).widthIn(max = 320.dp)
                .fillMaxWidth().background(Color(0xFFF6F6F6), RoundedCornerShape(15.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .semantics { paneTitle = "选择站点" }.padding(horizontal = 10.dp, vertical = 15.dp)) {
                Text(if (station == "校园驿站") "安防赛场" else "校园驿站", color = Color.Black, fontSize = 20.sp,
                  modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp).background(Color.White)
                    .clickable { station = if (station == "校园驿站") "安防赛场" else "校园驿站"; overlay = null }
                    .padding(horizontal = 20.dp, vertical = 18.dp))
                // The alternate site is the only item in the supplied design.
              }
            }
            StationOverlay.Mute -> MuteSheet(
              station = station,
              modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
              onSelect = { duration ->
                updateMute(if (duration == Long.MAX_VALUE) duration else nowMillis() + duration)
                overlay = null
              }, onDismiss = dismiss
            )
            StationOverlay.DeviceMenu -> SimpleOverlay("设备列表", dismiss) {
              OverlayButton("人物录像自动解析", onClick = { overlay = null; onPeopleSettings() })
              OverlayButton("设备连接状态", onClick = { overlay = StationOverlay.DeviceStatus })
              OverlayButton("刷新设备", onClick = { overlay = null; onRefreshDevices() })
              OverlayButton("查看多画面", onClick = { overlay = StationOverlay.MultiPreview })
              OverlayButton("消息中心", onClick = { overlay = null; openMessages() })
              OverlayButton("审核记录", onClick = { overlay = null; openReviews() })
              OverlayButton("原工程 SDK 调试", onClick = { overlay = null; onSdkTools() })
            }
            StationOverlay.DeviceStatus -> SimpleOverlay("设备连接状态", dismiss) {
              Text(when {
                deviceState.needsLogin -> "设备账号尚未登录"
                deviceState.loading -> "正在刷新设备列表"
                deviceState.errorMessage != null -> deviceState.errorMessage
                deviceState.devices.isEmpty() -> "当前账号暂无设备"
                else -> "已读取 ${deviceState.devices.size} 台设备，画面为最近预览。"
              }, color = Color.Gray)
              OverlayButton(if (deviceState.needsLogin) "登录设备账号" else "刷新设备",
                onClick = { overlay = null; if (deviceState.needsLogin) onSdkTools() else onRefreshDevices() })
            }
            StationOverlay.MultiPreview -> SimpleOverlay("多画面预览", dismiss) {
              Text("设备最近预览 · 点击进入单路直播", color = Color.Gray, fontSize = 14.sp)
              Spacer(Modifier.height(16.dp))
              if (deviceState.devices.isEmpty()) {
                Text(if (deviceState.needsLogin) "请先登录设备账号" else "暂无可预览的设备", color = Color.Gray)
              }
              deviceState.devices.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  row.forEach { device ->
                    key(device.sn) {
                      Box(Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                        .background(Ink).clickable { overlay = null; onOpenDevice(device.sn) }) {
                        devicePreview(device, Modifier.fillMaxSize())
                        Text(device.name, color = Color.White, fontSize = 12.sp, maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                          modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.45f)).padding(6.dp))
                      }
                    }
                  }
                  if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun HomeScreen(
  station: String, muted: Boolean, onMute: () -> Unit, onStation: () -> Unit,
  onMessages: () -> Unit, onMenu: () -> Unit, onPreview: () -> Unit,
  deviceState: StationDeviceState, onRefreshDevices: () -> Unit, onSdkTools: () -> Unit,
  onOpenDevice: (String) -> Unit, onDeviceSettings: (String) -> Unit,
  devicePreview: @Composable (StationDeviceCard, Modifier) -> Unit, onTab: (String) -> Unit
) {
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 100.dp)) {
      Spacer(Modifier.height(28.dp))
      Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMute, modifier = Modifier.size(40.dp).semantics {
          contentDescription = if (muted) "恢复${station}的通知" else "关闭${station}的通知"
        }) {
          Box(Modifier.size(30.dp).background(Color.White, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            StationIcon(StationSymbol.Bell, modifier = Modifier.size(28.dp), color = if (muted) Color(0xFFCC812E) else Color(0xFF50A7FA))
          }
        }
        Row(Modifier.weight(1f).clickable(onClick = onStation).padding(start = 7.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(station, color = Color(0xFF555555), fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          Spacer(Modifier.width(12.dp))
          StationIcon(StationSymbol.Chevron, modifier = Modifier.size(24.dp), color = Color(0xFF555555))
        }
        IconButton(onClick = onMessages, modifier = Modifier.size(44.dp).semantics { contentDescription = "消息中心" }) {
          StationIcon(StationSymbol.Message, modifier = Modifier.size(25.dp), color = Color.White)
        }
      }
      Spacer(Modifier.height(12.dp))
      Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("所有设备", color = Color(0xFF555555), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(onClick = onMenu, modifier = Modifier.size(44.dp).semantics { contentDescription = "设备列表菜单" }) {
          StationIcon(StationSymbol.Menu, modifier = Modifier.size(25.dp), color = Color.White)
        }
      }
      Spacer(Modifier.height(20.dp))
      Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(64.dp).background(Color.White, RoundedCornerShape(11.dp))
        .clickable(onClick = onPreview).padding(start = 16.dp, end = 25.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("多画面预览", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Box(Modifier.size(24.dp).background(Color(0xFFFFC0C9), CircleShape), contentAlignment = Alignment.Center) {
          StationIcon(StationSymbol.Play, modifier = Modifier.size(20.dp), color = Color(0xFFFF2F49))
        }
      }
      Spacer(Modifier.height(16.dp))
      if (deviceState.devices.isEmpty()) {
        DeviceListStatus(deviceState, onRefreshDevices, onSdkTools)
        Spacer(Modifier.height(16.dp))
      }
      deviceState.devices.forEach { device ->
        key(device.sn) {
          DeviceTile(device, onOpenDevice, onDeviceSettings, devicePreview)
          Spacer(Modifier.height(16.dp))
        }
      }
    }
    StationBottomBar("home", onTab, Modifier.align(Alignment.BottomCenter))
  }
}

@Composable
private fun DeviceListStatus(state: StationDeviceState, onRefresh: () -> Unit, onLogin: () -> Unit) {
  Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()
    .background(Color.White, RoundedCornerShape(11.dp)).padding(22.dp),
    horizontalAlignment = Alignment.CenterHorizontally) {
    if (state.loading) {
      CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
      Spacer(Modifier.height(12.dp))
    }
    Text(
      when {
        state.needsLogin -> "登录设备账号后查看摄像头"
        state.loading && state.devices.isEmpty() -> "正在加载设备…"
        state.loading -> "正在刷新设备…"
        state.errorMessage != null -> state.errorMessage
        else -> "当前账号暂无设备"
      }, color = Color.Gray, fontSize = 14.sp
    )
    if (!state.loading) {
      TextButton(onClick = if (state.needsLogin) onLogin else onRefresh) {
        Text(if (state.needsLogin) "登录设备账号" else "刷新重试")
      }
    }
  }
}

@Composable
private fun DeviceTile(
  device: StationDeviceCard,
  onOpen: (String) -> Unit,
  onSettings: (String) -> Unit,
  preview: @Composable (StationDeviceCard, Modifier) -> Unit
) {
  val status = when (device.online) { true -> "在线"; false -> "离线"; null -> "未知" }
  val statusColor = when (device.online) {
    true -> Color(0xFF20B884)
    false -> Color(0xFF8C939B)
    null -> Color(0xFFCA933F)
  }
  Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(Color.White, RoundedCornerShape(11.dp))
    .padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 14.dp)) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(device.name, color = Ink, fontSize = 20.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      Spacer(Modifier.width(12.dp))
      Row(Modifier.padding(end = 4.dp).heightIn(min = 24.dp).clip(CircleShape).background(statusColor)
        .clickable { onSettings(device.sn) }.padding(horizontal = 8.dp, vertical = 2.dp)
        .semantics { contentDescription = "${device.name}，$status，打开设备设置" },
        verticalAlignment = Alignment.CenterVertically) {
        Text(status, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.width(4.dp))
        StationIcon(StationSymbol.Chevron, modifier = Modifier.size(13.dp), color = Color.White)
      }
    }
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().aspectRatio(353f / 193f).clip(RoundedCornerShape(10.dp)).background(Ink)
      .clickable { onOpen(device.sn) }.semantics {
        contentDescription = "${device.name}，${if (device.isStation) "打开基站设置" else "查看实时画面"}"
      }) {
      preview(device, Modifier.fillMaxSize())
      Box(Modifier.fillMaxWidth().height(54.dp).background(
        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))))
      Text(if (device.isStation) "基站" else "最近预览", color = Color.White, fontSize = 19.sp,
        modifier = Modifier.padding(start = 13.dp, top = 12.dp))
      IconButton(onClick = { onSettings(device.sn) }, modifier = Modifier.align(Alignment.TopEnd)
        .size(42.dp).semantics { contentDescription = "${device.name}设备设置" }) {
        StationIcon(StationSymbol.More, modifier = Modifier.size(25.dp), color = Color.White)
      }
    }
  }
}

@Composable
private fun StationBottomBar(selected: String, onTab: (String) -> Unit, modifier: Modifier = Modifier) {
  Row(modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp).fillMaxWidth().height(68.dp)
    .background(Color.White, CircleShape), verticalAlignment = Alignment.CenterVertically) {
    listOf(Triple("home", "首页", StationSymbol.Home), Triple("assistant", "AI追溯", StationSymbol.Chat), Triple("profile", "我的", StationSymbol.Person)).forEach { (route, label, symbol) ->
      Box(Modifier.weight(1f).fillMaxHeight().selectable(selected == route, role = Role.Tab, onClick = { onTab(route) })
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        StationIcon(symbol, modifier = Modifier.size(28.dp))
      }
    }
  }
}

@Composable
private fun MuteSheet(station: String, modifier: Modifier, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
  Column(modifier.fillMaxWidth().heightIn(max = 600.dp).background(Color.White, RoundedCornerShape(25.dp))
    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    .semantics { paneTitle = "关闭${station}的通知" }.verticalScroll(rememberScrollState())
    .padding(start = 20.dp, end = 20.dp, top = 36.dp, bottom = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text("关闭${station}的通知", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    Spacer(Modifier.height(20.dp))
    OverlayButton("30分钟", onClick = { onSelect(30 * 60 * 1000L) })
    Spacer(Modifier.height(18.dp))
    OverlayButton("1小时", onClick = { onSelect(60 * 60 * 1000L) })
    Spacer(Modifier.height(18.dp))
    OverlayButton("直到我重新打开它", onClick = { onSelect(Long.MAX_VALUE) })
    Spacer(Modifier.height(18.dp))
    OverlayButton("取消", filled = true, onClick = onDismiss)
  }
}

@Composable
private fun OverlayButton(label: String, filled: Boolean = false, onClick: () -> Unit) {
  Box(Modifier.fillMaxWidth().heightIn(min = 66.dp).background(if (filled) Color(0xFFF4F4F4) else Color.White, CircleShape)
    .then(if (!filled) Modifier.border(BorderStroke(1.dp, Color(0xFFF2F2F2)), CircleShape) else Modifier)
    .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
    Text(label, fontSize = 20.sp, lineHeight = 28.sp, color = Color.Black)
  }
}

@Composable
private fun BoxScope.SimpleOverlay(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  Column(Modifier.align(Alignment.Center).padding(20.dp).fillMaxWidth().heightIn(max = 640.dp)
    .background(Color.White, RoundedCornerShape(24.dp))
    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    .semantics { paneTitle = title }.verticalScroll(rememberScrollState()).padding(24.dp)) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
    Spacer(Modifier.height(20.dp))
    content()
    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
  }
}

@Composable
private fun ProfileScreen(onTab: (String) -> Unit, onSdkTools: () -> Unit, onMessages: () -> Unit,
  onReviews: () -> Unit = {}, onPeopleSettings: () -> Unit = {}) {
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 35.dp, bottom = 100.dp)) {
      Text("我的", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
      Spacer(Modifier.height(36.dp))
      Text("ParTrace Vision", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
      Text("驿站追迹", fontSize = 18.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
      Spacer(Modifier.height(32.dp))
      OverlayButton("消息中心", onClick = onMessages)
      Spacer(Modifier.height(16.dp))
      OverlayButton("审核记录", onClick = onReviews)
      Spacer(Modifier.height(16.dp))
      OverlayButton("人物录像自动解析", onClick = onPeopleSettings)
      Spacer(Modifier.height(16.dp))
      OverlayButton("SDK 调试", onClick = onSdkTools)
      Spacer(Modifier.height(28.dp))
      Text("设备连接与功能状态", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
      Text("首页设备、在线状态与最近预览来自当前登录的设备账号。点击画面可打开原工程的直播，更多菜单可进入设备设置。AI 追溯使用独立后端服务，可在 AI 页面设置与查看运行。报警消息尚未接入；通知静音仅保存本地偏好。", color = Color.Gray, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 12.dp))
    }
    StationBottomBar("profile", onTab, Modifier.align(Alignment.BottomCenter))
  }
}
