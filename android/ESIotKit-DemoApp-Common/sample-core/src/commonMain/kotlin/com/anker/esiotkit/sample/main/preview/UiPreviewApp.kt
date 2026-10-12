package com.anker.esiotkit.sample.main.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import com.anker.esiotkit.sample.demo.ui.HackathonDemoScreen
import com.anker.esiotkit.sample.main.mega.LocalDrawerState
import com.anker.esiotkit.sample.main.mega.MegaMainScreen
import com.anker.esiotkit.sample.main.mega.MegaMainUiState
import com.anker.esiotkit.sample.main.mega.thing.MegaThingScreen2
import com.anker.esiotkit.sample.ui.component.DevicesTabIcon
import com.anker.esiotkit.sample.ui.component.EmptyStateView
import com.anker.esiotkit.sample.ui.component.EventsTabIcon
import com.anker.esiotkit.sample.ui.component.ThingsTabIcon
import com.anker.esiotkit.sample.ui.composable.PlatformNavigatorContent
import kotlinx.coroutines.launch

/** Renders official UI components without constructing SDK-backed screen models. */
@Composable
fun UiPreviewApp(onExitPreview: () -> Unit) {
  MaterialTheme(
    colorScheme = MaterialTheme.colorScheme.copy(primary = Color(0xFF005D8E))
  ) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
      Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFFE8F1F7))
          .statusBarsPadding().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text("本地界面预览 · 暂无设备数据", fontSize = 12.sp,
          color = Color(0xFF005D8E), modifier = Modifier.padding(vertical = 16.dp))
        TextButton(onClick = onExitPreview) { Text("返回登录") }
      }
      Box(Modifier.weight(1f)) {
        Navigator(UiPreviewScreen, onBackPressed = { true }) { navigator ->
          PlatformNavigatorContent(navigator)
        }
      }
    }
  }
}

private object UiPreviewScreen : Screen {
  @Composable
  override fun Content() {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var previewNotice by remember { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val tabs = remember {
      listOf(
        MegaMainScreen.BottomNavItem(DevicesTabIcon, "Devices", "Device_screen"),
        MegaMainScreen.BottomNavItem(EventsTabIcon, "Events", "Events_screen"),
        MegaMainScreen.BottomNavItem(Icons.Default.AutoAwesome, "AI", "AI_demo_screen"),
        MegaMainScreen.BottomNavItem(ThingsTabIcon, "Things", "Thing_screen")
      )
    }
    ModalNavigationDrawer(
      drawerState = drawer,
      drawerContent = {
        CompositionLocalProvider(LocalDrawerState provides drawer) {
          Column(
            modifier = Modifier.fillMaxHeight().fillMaxWidth(0.7f)
              .background(Color.White).statusBarsPadding()
          ) {
            IconButton(onClick = { scope.launch { drawer.close() } }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close Drawer")
            }
            listOf(
              Icons.Default.Home to "Reset Password",
              Icons.Default.Mail to "Log",
              Icons.Default.CameraAlt to "Auto Capture",
              Icons.Default.Build to "Device Capabilities",
              Icons.Default.Translate to "Language"
            ).forEach { (icon, label) ->
              MegaMainScreen.DrawerItem(icon = icon, label = label) {
                previewNotice = "$label：当前为本地界面预览。"
              }
            }
          }
        }
      }
    ) {
      Scaffold(
        containerColor = Color.White,
        topBar = {
          MegaMainScreen.TopBarContent(
            selectedItem = selectedTab,
            onSearchQueryChange = {},
            onDrawerOpen = { scope.launch { drawer.open() } },
            onAddDeviceClick = { previewNotice = "配网和添加设备需要登录后使用。" }
          )
        },
        bottomBar = {
          MegaMainScreen.BottomBarContent(tabs, selectedTab) { selectedTab = it }
        }
      ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
          when (selectedTab) {
            0 -> MegaMainScreen.DevicePage(
              uiState = MegaMainUiState(emptyList(), false, "", emptyList()),
              deviceList = emptyList(),
              onHouseSelected = {},
              onRefresh = {},
              onDeviceClick = { _, _ -> },
              onSettingsClick = {}
            )
            1 -> UiPreviewEvents()
            2 -> HackathonDemoScreen.EntryContent(
              onVoiceQuery = { previewNotice = "语音问答需要登录、事件数据和 AI 服务配置。" },
              onDailyReport = { previewNotice = "自动日报需要登录、事件数据和 AI 服务配置。" }
            )
            3 -> Column(Modifier.fillMaxSize()) {
              MegaThingScreen2.SearchBarSection(onSearchQueryChange = {})
              MegaThingScreen2.SearchBarThing(onSearchQueryChange = {})
              Spacer(Modifier.weight(1f))
              EmptyStateView(message = "No devices found")
              Spacer(Modifier.weight(1f))
            }
          }
        }
      }
    }
    previewNotice?.let { message ->
      AlertDialog(
        onDismissRequest = { previewNotice = null },
        title = { Text("界面预览") },
        text = { Text(message) },
        confirmButton = {
          TextButton(onClick = { previewNotice = null }) { Text("知道了") }
        }
      )
    }
  }
}
