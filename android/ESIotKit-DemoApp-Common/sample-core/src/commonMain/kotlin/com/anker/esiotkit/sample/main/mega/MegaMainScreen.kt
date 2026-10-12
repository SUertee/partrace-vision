@file:OptIn(InternalIotApi::class)

package com.anker.esiotkit.sample.main.mega

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.core.ESIotManager
import com.anker.esiotkit.core.constants.isStation
import com.anker.esiotkit.compat.ESIotCompatSwitch
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.sample.demo.ui.HackathonDemoScreen
import com.anker.esiotkit.sample.main.SampleAppEvent
import com.anker.esiotkit.sample.main.mega.add_device.MegaAddDeviceScreen
import com.anker.esiotkit.sample.main.mega.app_settings.logs.LogManagementScreen
import com.anker.esiotkit.sample.main.mega.device_settings.MegaDeviceSettingsScreen
import com.anker.esiotkit.sample.main.mega.event.AutoCaptureScreen
import com.anker.esiotkit.sample.main.mega.device_settings.device_capability.DeviceCapabilityScreen
import com.anker.esiotkit.sample.main.mega.event.MegaEventScreen
import com.anker.esiotkit.sample.main.mega.live_video.MegaLiveVideoScreen
import com.anker.esiotkit.sample.main.mega.login.MegaLoginScreen
import com.anker.esiotkit.sample.main.mega.thing.MegaThingScreen2
import com.anker.esiotkit.sample.main.sampleAppEvent
import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.sample.support.i18n.AppLanguage
import com.anker.esiotkit.sample.support.i18n.LanguageManager
import com.anker.esiotkit.sample.main.mega.language.LanguageScreen
import com.anker.esiotkit.sample.support.model.MegaPlayType
import com.anker.esiotkit.sample.ui.composable.BackHandler
import com.anker.esiotkit.sample.ui.composable.debugLog
import com.anker.esiotkit.sample.ui.composable.state
import com.anker.esiotkit.sample.ui.component.DefaultAvatar
import com.anker.esiotkit.sample.ui.component.DeviceCardList
import com.anker.esiotkit.sample.ui.component.DevicesTabIcon
import com.anker.esiotkit.sample.ui.component.EmptyStateView
import com.anker.esiotkit.sample.ui.component.EventsTabIcon
import com.anker.esiotkit.sample.ui.component.ThingsTabIcon
import com.anker.esiotkit.sample.view.DeviceList
import com.anker.esiotkit.sample.view.HouseList
import com.anker.esiotkit.sample.view.SearchBar
import com.anker.esiotkit.sample.view.TitleBar
import com.anker.esiotkit.sample.view.currentNavigator
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.InternalIotApi
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.userConfig
import com.anker.esiotkit.sample.support.platform.getAppVersionInfo
import kotlinx.coroutines.launch
import kotlin.time.Clock
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_language_mode_en
import com.anker.esiotkit.demo.generated.resources.eslot_language_mode_system
import com.anker.esiotkit.demo.generated.resources.eslot_language_mode_zh
import com.anker.esiotkit.demo.generated.resources.eslot_sidebar_language_title
import org.jetbrains.compose.resources.stringResource
import com.anker.esiotkit.demo.generated.resources.eslot_main_mega_event_title
import com.anker.esiotkit.demo.generated.resources.eslot_main_thing_title

/**
 * @version 2024/5/1
 */
object MegaMainScreen : Screen {
  @Composable
  override fun Content() {
    val viewModel = rememberScreenModel { MegaMainViewModel() }
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()

    LaunchedEffect(drawerState.currentValue) {
      debugLog("DrawerDebug: [drawerState changed] " +
        "currentValue=${drawerState.currentValue}, " +
        "currentOffset=${drawerState.currentOffset}, " +
        "isOpen=${drawerState.isOpen}")
    }
    LaunchedEffect(Unit) {
      drawerState.snapTo(DrawerValue.Closed)
      debugLog("DrawerDebug: [snapTo Closed] after snap, " +
        "currentOffset=${drawerState.currentOffset}, " +
        "currentValue=${drawerState.currentValue}")
    }
    var selectedItem by rememberSaveable { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredDeviceList by remember(uiState.deviceList, searchQuery) {
      derivedStateOf {
        if (searchQuery.isBlank()) {
          uiState.deviceList
        } else {
          // 先找出所有匹配的设备
          val matchedDevices = uiState.deviceList.filter { device ->
            device.deviceSn.contains(searchQuery, ignoreCase = true) ||
            device.iotDevice.deviceName.contains(searchQuery, ignoreCase = true)
          }
          // 收集匹配子设备的父设备SN
          val parentSnsOfMatchedChildren = matchedDevices
            .filter { it.deviceSn != it.parentSn } // 子设备
            .map { it.parentSn }
            .toSet()
          // 最终结果：匹配的设备 + 有匹配子设备的父设备
          uiState.deviceList.filter { device ->
            device.deviceSn.contains(searchQuery, ignoreCase = true) ||
            device.iotDevice.deviceName.contains(searchQuery, ignoreCase = true) ||
            (device.deviceSn == device.parentSn && device.deviceSn in parentSnsOfMatchedChildren)
          }
        }
      }
    }
    LaunchedEffect(Unit) {
      sampleAppEvent.collect {
        when (it) {
          is SampleAppEvent.Logout -> {
            appLogger.w("MegaMainScreen/onLogout/401: auto logout triggered")
            navigator.popUntilRoot()
            ESIotManager.setUserConfig(userConfig.apply { onHttpFailed = null })
            emitToast("401 Unauthorized, please login again")
          }
          is SampleAppEvent.ShowDeviceEvents -> {
            viewModel.filterDeviceSn = it.deviceSn
            selectedItem = 1
            SampleAppEvent.emit(SampleAppEvent.Idle)
          }
          else -> {}
        }
      }
    }
    if (currentNavigator !== LocalNavigator.current) {
      currentNavigator = LocalNavigator.current
    }

    val items = listOf(
      BottomNavItem(
        icon = DevicesTabIcon,
        label = "Devices",
        route = "Device_screen"
      ),
      BottomNavItem(
        icon = EventsTabIcon,
        label = "Events",
        route = "Events_screen"
      ),
      BottomNavItem(
        icon = Icons.Default.AutoAwesome,
        label = "AI",
        route = "AI_demo_screen"
      ),
      BottomNavItem(
        icon = ThingsTabIcon,
        label = "Things",
        route = "Thing_screen"
      )
    )

    ModalNavigationDrawer(
      modifier = Modifier.clipToBounds().navigationBarsPadding(),
      drawerState = drawerState,
      drawerContent = {
        CompositionLocalProvider(LocalDrawerState provides drawerState) {
          DrawerContent(
            onCloseDrawer = { scope.launch { drawerState.close() } },
            onNavigateToLog = { navigator.push(LogManagementScreen()) },
            onNavigateToAutoCapture = { navigator.push(AutoCaptureScreen) },
            onNavigateToDeviceCapability = { navigator.push(DeviceCapabilityScreen()) },
            onNavigateToLanguage = { navigator.push(LanguageScreen) },
            onLogout = {
              appLogger.i("MegaMainScreen/onLogout/start: tokenValid=${InfoCache.tokenValid}, loginType=${InfoCache.loginType}")
              InfoCache.tokenValid = false
              scope.launch {
                drawerState.close()
                navigator.push(MegaLoginScreen)
              }
            }
          )
        }
      },
      content = {
        Scaffold(
          modifier = Modifier.background(Color.White),
          topBar = {
            TopBarContent(selectedItem, onSearchQueryChange = { query ->
              searchQuery = query
            },             onDrawerOpen = {
              debugLog("DrawerDebug: [onDrawerOpen] called, drawerState.isOpen=${drawerState.isOpen}, " +
                "currentValue=${drawerState.currentValue}, currentOffset=${drawerState.currentOffset}")
              scope.launch {
                debugLog("DrawerDebug: [onDrawerOpen] calling drawerState.open()")
                drawerState.open()
                debugLog("DrawerDebug: [onDrawerOpen] open() returned, " +
                  "isOpen=${drawerState.isOpen}, currentValue=${drawerState.currentValue}, " +
                  "currentOffset=${drawerState.currentOffset}")
              }
            }, onAddDeviceClick = {
              navigator.push(MegaAddDeviceScreen)
            })
          },
          bottomBar = {
            BottomBarContent(items, selectedItem, onItemSelected = { index ->
              debugLog("DrawerDebug: [tab click] index=$index, selectedItem=$selectedItem, " +
                "drawerState.isOpen=${drawerState.isOpen}")
              if (index != 1) {
                viewModel.filterDeviceSn = null
              }
              selectedItem = index
            })
          },
          content = { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
              when (selectedItem) {
                0 ->
                  DevicePage(
                    uiState = uiState,
                    deviceList = filteredDeviceList,
                    onHouseSelected = viewModel::toggleHouse,
                    onRefresh = viewModel::refreshDevice,
                    onDeviceClick = { device, playType ->
                      if (device.deviceSn.isStation()) {
                        navigator.push(MegaDeviceSettingsScreen(device.deviceSn))
                      } else {
                        navigator.push(MegaLiveVideoScreen(device.deviceSn, playType))
                      }
                    },
                    onSettingsClick = { device ->
                      navigator.push(MegaDeviceSettingsScreen(device.deviceSn))
                    },
                  )
                1 -> {
                  MegaEventScreen(filterDeviceSn = viewModel.filterDeviceSn).Content()
                }
                2 -> {
                  HackathonDemoScreen.Content()
                }
                3 -> {
                  MegaThingScreen2.Content()
                }
              }
            }
          }
        )
      }
    )

    // 抽屉打开时，返回关闭抽屉
    BackHandler(drawerState.isOpen) {
      debugLog("DrawerDebug: [BackHandler] drawer is open, closing")
      scope.launch {
        drawerState.close()
      }
    }

    // 正常返回时，双击退出应用
    var backPressTime by remember { mutableStateOf(0L) }
    BackHandler(!drawerState.isOpen) {
      debugLog("DrawerDebug: [BackHandler] drawer is closed, handling exit")
      val currentTime = Clock.System.now().toEpochMilliseconds()
      if (currentTime - backPressTime < 2000) {
        debugLog("DrawerDebug: [BackHandler] double press, emitting ExitApp")
        SampleAppEvent.emit(SampleAppEvent.ExitApp)
      } else {
        backPressTime = currentTime
        emitToast("Press back again to exit")
      }
    }
  }

  @Composable
  fun BottomBarContent(
    items: List<BottomNavItem>,
    selectedItem: Int,
    onItemSelected: (Int) -> Unit
  ) {
    // 自定义底部 Tab，匹配 Security-Devices-Page 设计稿：
    //   白底 + 顶部阴影分层
    //   紧凑纵向布局（图标贴近文字），整体高度较低
    //   选中色 #005D8E，未选中 #999999
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .shadow(elevation = 3.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
        .background(Color.White),
      verticalAlignment = Alignment.CenterVertically
    ) {
      items.forEachIndexed { index, item ->
        val color = if (selectedItem == index) Color(0xFF005D8E) else Color(0xFF999999)
        val iconSize = if (item.label == "Events") 22.dp else 20.dp
        Column(
          modifier = Modifier
            .weight(1f)
            .clickable { onItemSelected(index) }
            .padding(vertical = 4.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = color,
            modifier = Modifier.size(iconSize)
          )
          Text(
            text = item.label,
            fontSize = 10.sp,
            color = color
          )
        }
      }
    }
  }

  @Composable
  fun TopBarContent(
    selectedItem: Int,
    onSearchQueryChange: (String) -> Unit,
    onDrawerOpen: () -> Unit,
    onAddDeviceClick: () -> Unit
  ) {
    when (selectedItem) {
      1 -> TitleBar(title = Res.string.eslot_main_mega_event_title, showBack = false)
      2 -> TitleBar(title = "AI Demo", showBack = false)
      3 -> TitleBar(title = Res.string.eslot_main_thing_title, showBack = false)
      else -> DevicesTopBar(
        onSearchQueryChange = onSearchQueryChange,
        onAvatarClick = onDrawerOpen,
        onAddDeviceClick = onAddDeviceClick,
      )
    }
  }

  /**
   * 匹配 HTML 设计稿的 Devices 顶栏：
   *  [avatar 32dp] [圆角浅灰搜索框 "Search name, SN..."] [圆形加号 28dp]
   */
  @Composable
  private fun DevicesTopBar(
    onSearchQueryChange: (String) -> Unit,
    onAvatarClick: () -> Unit,
    onAddDeviceClick: () -> Unit,
  ) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { onSearchQueryChange("") }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .shadow(elevation = 3.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
        .background(Color.White)
        .statusBarsPadding()
        .padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier.size(32.dp).clickable(onClick = {
          debugLog("DrawerDebug: [avatar click] triggered, calling onAvatarClick")
          onAvatarClick()
        })
      ) {
        DefaultAvatar(modifier = Modifier.fillMaxSize())
      }

      Spacer(modifier = Modifier.size(12.dp))

      Box(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(12.dp))
          .background(Color(0xFFF2F4F7))
          .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = Color(0xFFC2C2C2),
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.size(8.dp))
          Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
              Text(
                text = "Search name, SN...",
                color = Color(0xFF999999),
                fontSize = 14.sp
              )
            }
            BasicTextField(
              value = query,
              onValueChange = {
                query = it
                onSearchQueryChange(it)
              },
              singleLine = true,
              textStyle = TextStyle(color = Color(0xFF1F1F1F), fontSize = 14.sp),
              keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
              keyboardActions = KeyboardActions(onSearch = { }),
              modifier = Modifier.fillMaxWidth(),
              cursorBrush = SolidColor(Color(0xFF005D8E))
            )
          }
        }
      }

      Spacer(modifier = Modifier.size(8.dp))

      Icon(
        imageVector = AddDeviceIcon,
        contentDescription = "Add Device",
        tint = Color.Unspecified,
        modifier = Modifier.size(28.dp).clickable(onClick = onAddDeviceClick)
      )
    }
  }

  @Composable
  fun DrawerContent(
    onCloseDrawer: () -> Unit,
    onNavigateToLog: () -> Unit,
    onNavigateToAutoCapture: () -> Unit,
    onNavigateToDeviceCapability: () -> Unit,
    onNavigateToLanguage: () -> Unit,
    onLogout: () -> Unit
  ) {
    val drawerState = LocalDrawerState.current
    val scope = rememberCoroutineScope()
    val isOpen = drawerState?.isOpen == true
    Column(
      modifier = Modifier.fillMaxHeight()
        .fillMaxSize(fraction = 0.7f)
        .graphicsLayer { alpha = if (isOpen) 1f else 0f }
        .background(Color.White)
        .statusBarsPadding()
        .verticalScroll(rememberScrollState())
    ) {
      IconButton(onClick = onCloseDrawer, enabled = isOpen) {
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Drawer")
      }
      // Spacer(modifier = Modifier.height(16.dp))
      DrawerItem(
        icon = Icons.Default.Home,
        label = "Reset Password",
      ) {
        // Handle navigation or action
      }
      DrawerItem(icon = Icons.Default.Mail, label = "Log") {
        onNavigateToLog()
      }
      DrawerItem(icon = Icons.Default.CameraAlt, label = "Auto Capture") {
        onNavigateToAutoCapture()
      }
      DrawerItem(icon = Icons.Default.Build, label = "Device Capabilities") {
        onNavigateToDeviceCapability()
      }
      if (InfoCache.i18nEntryEnabled) {
        val currentMode by LanguageManager.currentMode.collectAsState()
        val modeRes = when (currentMode) {
          AppLanguage.SYSTEM -> Res.string.eslot_language_mode_system
          AppLanguage.EN -> Res.string.eslot_language_mode_en
          AppLanguage.ZH -> Res.string.eslot_language_mode_zh
        }
        DrawerItem(
          icon = Icons.Default.Translate,
          label = stringResource(Res.string.eslot_sidebar_language_title),
          subtitle = stringResource(modeRes),
        ) {
          onNavigateToLanguage()
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

      // API Mode Toggle
      var useNewApi by remember {
        val cached = InfoCache.useNewApi
        if (cached) ESIotCompatSwitch.enableNewApiCompat() else ESIotCompatSwitch.enableLegacyApiCompat()
        mutableStateOf(cached)
      }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "New API Mode",
            fontSize = 14.sp,
            color = Color(0xFF1F1F1F)
          )
          Text(
            text = if (useNewApi) "ESIotDeviceManager (encapsulation)" else "ThingProperty direct call",
            fontSize = 11.sp,
            color = if (useNewApi) Color(0xFF005D8E) else Color(0xFF999999)
          )
        }
        androidx.compose.material3.Switch(
          checked = useNewApi,
          onCheckedChange = {
            useNewApi = it
            InfoCache.useNewApi = it
            if (it) {
              ESIotCompatSwitch.enableNewApiCompat()
            } else {
              ESIotCompatSwitch.enableLegacyApiCompat()
            }
          },
          colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color(0xFF005D8E)
          )
        )
      }

      // Local JS Replace Toggle
      var localJsReplace by remember { mutableStateOf(InfoCache.localJsReplaceEnabled) }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Local JS Replace",
            fontSize = 14.sp,
            color = Color(0xFF1F1F1F)
          )
          Text(
            text = if (localJsReplace) "Use JS from LocalJs/ directory" else "Download JS from server",
            fontSize = 11.sp,
            color = if (localJsReplace) Color(0xFF005D8E) else Color(0xFF999999)
          )
        }
        androidx.compose.material3.Switch(
          checked = localJsReplace,
          onCheckedChange = { enabled ->
            localJsReplace = enabled
            InfoCache.localJsReplaceEnabled = enabled
            if (enabled) {
              scope.launch {
                LocalJsReplacer.apply()
              }
            }
          },
          colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color(0xFF005D8E)
          )
        )
      }

      // IJKPlayer Log Silent Toggle
      var ijkLogSilent by remember { mutableStateOf(InfoCache.ijkLogSilent) }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "IJKPlayer Log Silent",
            fontSize = 14.sp,
            color = Color(0xFF1F1F1F)
          )
          Text(
            text = "重启后生效",
            fontSize = 11.sp,
            color = Color(0xFF999999)
          )
        }
        androidx.compose.material3.Switch(
          checked = ijkLogSilent,
          onCheckedChange = {
            ijkLogSilent = it
            InfoCache.ijkLogSilent = it
          },
          colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color(0xFF005D8E)
          )
        )
      }

      Spacer(modifier = Modifier.weight(1f))

      // App Version Info
      val versionInfo = remember { getAppVersionInfo() }
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "Version: ${versionInfo.versionName} (${versionInfo.versionCode})",
          fontSize = 12.sp,
          color = Color.Gray
        )
      }

      OutlinedButton(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.Red),
        enabled = isOpen,
        onClick = onLogout
      ) {
        Text(
          "Logout", color = Color.Red,
          fontSize = 16.sp,
        )
      }
    }
  }

  @Composable
  fun DrawerItem(icon: ImageVector, label: String, subtitle: String? = null, onClick: () -> Unit) {
    val drawerState = LocalDrawerState.current
    val scope = rememberCoroutineScope()
      Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .clickable(
          enabled = drawerState?.isOpen == true,
          onClick = {
            debugLog("DrawerDebug: [DrawerItem click] label=$label")
            scope.launch { drawerState?.close() }
            onClick()
          }
        )
        .padding(vertical = 12.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        modifier = Modifier.padding(horizontal = 16.dp)
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(text = label, fontSize = 16.sp)
        if (subtitle != null) {
          Text(
            text = subtitle,
            fontSize = 12.sp,
            color = Color(0xFF999999)
          )
        }
      }
    }
  }

  @OptIn(ExperimentalMaterialApi::class)
  @Composable
  fun DevicePage(
    uiState: MegaMainUiState,
    deviceList: List<MegaDevice>,
    onHouseSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onDeviceClick: (MegaDevice, MegaPlayType) -> Unit,
    onSettingsClick: (MegaDevice) -> Unit,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      val refreshState = rememberPullRefreshState(
        refreshing = uiState.loading,
        onRefresh = onRefresh
      )

      DeviceCardList(
        modifier = Modifier.weight(1f),
        refreshing = uiState.loading,
        refreshState = refreshState,
        deviceList = deviceList,
        onDeviceClick = { device -> onDeviceClick(device, MegaPlayType.Normal) },
        onSettingsClick = onSettingsClick,
        onEventClick = { deviceSn ->
          SampleAppEvent.emit(SampleAppEvent.ShowDeviceEvents(deviceSn))
        },
      )
    }
  }

  data class BottomNavItem(
    val icon: ImageVector,
    val label: String,
    val route: String
  )
}

internal val LocalDrawerState: ProvidableCompositionLocal<DrawerState?> =
  staticCompositionLocalOf { null }
val LocalDevice = staticCompositionLocalOf<MegaDevice> {
  error("CompositionLocal LocalDevice not present")
}

// @Preview
// @Composable
// internal fun MainScreenPreview() {
//   KoinApplicationPreview(application = {
//     modules(IotDiModule.modules + MegaKitStartup.iotKoinModules)
//   }) {
//     MegaMainScreen.Content()
//   }
// }

private val AddDeviceIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "AddDevice",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 1028f,
    viewportHeight = 1024f
  ).apply {
    path(fill = SolidColor(Color(0xFF666666))) {
      // Circle
      moveTo(513.6f, 1024f)
      curveTo(379.2f, 1024f, 251.2f, 972.8f, 148.8f, 876.8f)
      curveTo(-49.6f, 678.4f, -49.6f, 352f, 148.8f, 153.6f)
      curveTo(251.2f, 51.2f, 379.2f, 0f, 513.6f, 0f)
      curveTo(648f, 0f, 776f, 51.2f, 878.4f, 147.2f)
      curveTo(1076.8f, 345.6f, 1076.8f, 672f, 878.4f, 870.4f)
      curveTo(776f, 972.8f, 648f, 1024f, 513.6f, 1024f)
      close()
      moveTo(513.6f, 64f)
      curveTo(392f, 64f, 283.2f, 108.8f, 200f, 198.4f)
      curveTo(27.2f, 371.2f, 27.2f, 659.2f, 200f, 832f)
      curveTo(283.2f, 915.2f, 392f, 960f, 513.6f, 960f)
      curveTo(635.2f, 960f, 744f, 915.2f, 827.2f, 825.6f)
      curveTo(1000f, 652.8f, 1000f, 364.8f, 827.2f, 192f)
      curveTo(744f, 108.8f, 635.2f, 64f, 513.6f, 64f)
      close()
    }
    path(fill = SolidColor(Color(0xFF666666))) {
      // Vertical line
      moveTo(513.6f, 780.8f)
      curveTo(494.4f, 780.8f, 481.6f, 768f, 481.6f, 748.8f)
      lineTo(481.6f, 268.8f)
      curveTo(481.6f, 256f, 494.4f, 243.2f, 513.6f, 243.2f)
      curveTo(532.8f, 243.2f, 545.6f, 256f, 545.6f, 268.8f)
      lineTo(545.6f, 748.8f)
      curveTo(545.6f, 768f, 532.8f, 780.8f, 513.6f, 780.8f)
      close()
    }
    path(fill = SolidColor(Color(0xFF666666))) {
      // Horizontal line
      moveTo(756.8f, 544f)
      lineTo(270.4f, 544f)
      curveTo(257.6f, 544f, 244.8f, 531.2f, 244.8f, 512f)
      curveTo(244.8f, 492.8f, 257.6f, 480f, 270.4f, 480f)
      lineTo(750.4f, 480f)
      curveTo(769.6f, 480f, 782.4f, 492.8f, 782.4f, 512f)
      curveTo(782.4f, 531.2f, 769.6f, 544f, 756.8f, 544f)
      close()
    }
  }.build()
}
