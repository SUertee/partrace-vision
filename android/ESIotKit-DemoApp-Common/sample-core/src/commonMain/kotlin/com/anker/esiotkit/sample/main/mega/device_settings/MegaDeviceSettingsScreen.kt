package com.anker.esiotkit.sample.main.mega.device_settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.anker.esiotkit.core.constants.isStation
import com.anker.esiotkit.core.constants.isStationNVR
import com.anker.esiotkit.core.constants.ServiceConstants
import com.anker.esiotkit.core.iotmodel.default_service.SpotlightSettingService
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.sample.data.ProductInfoManager
import com.anker.esiotkit.sample.main.mega.MegaMainScreen
import com.anker.esiotkit.sample.main.mega.device_settings.audio_settings.AudioSettingsScreen
import com.anker.esiotkit.sample.main.mega.device_settings.general.GeneralScreen
import com.anker.esiotkit.sample.main.mega.device_settings.notification.NotificationScreen
import com.anker.esiotkit.sample.main.mega.device_settings.power_manager.PowerManagerScreen
import com.anker.esiotkit.sample.main.mega.device_settings.settings_composable.SubDeviceIconsComposable
import com.anker.esiotkit.sample.main.mega.device_settings.video_settings.VideoSettingsScreen
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.support.PropertyThingsContainer
import com.anker.esiotkit.sample.support.rememberBooleanProperty
import com.anker.esiotkit.sample.support.rememberIntProperty
import com.anker.esiotkit.sample.support.rememberOnlineStatus
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.component.BatteryIndicator
import com.anker.esiotkit.sample.ui.component.ButtonDialog
import com.anker.esiotkit.sample.ui.component.DisableOverscrollEffect
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.component.WifiSignalIcon
import com.anker.esiotkit.sample.ui.component.rssiToLevel
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.sample.view.CommonRowItem
import com.anker.esiotkit.sample.view.currentNavigator
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.sample.view.toastNotSupport
import com.anker.esiotkit.security_device.p2p.bindDevice
import com.anker.esiotkit.security_device.p2p.cmd
import com.anker.esiotkit.support.log.appLogger
import org.koin.compose.koinInject
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_title

/**
 * @version 2024/6/13
 */
class MegaDeviceSettingsScreen(
  private val deviceSn: String
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_title

  @OptIn(ExperimentalFoundationApi::class)
  @Composable
  override fun Content() = BaseColumn(paddingHorizontal = 0.dp) {
    val megaDevice = remember(deviceSn) {
      MegaDeviceManager.getDeviceBy(deviceSn)
    } ?: run {
      emitToast("Device not found")
      currentNavigator?.pop()
      return@BaseColumn
    }
    val deviceManager = koinInject<com.anker.esiotkit.domain_thing.api.ESIotDeviceManager>()
    val viewModel = rememberScreenModel { MegaDeviceSettingsViewModel(megaDevice, deviceManager) }
    val navigator = LocalNavigator.currentOrThrow
    val isOnline by megaDevice.rememberOnlineStatus()
    MegaLoading(viewModel.loading)

    // 禁用 overscroll 效果，解决滚动到边界时点击坐标错位的问题
    DisableOverscrollEffect {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFFF5F5F5)),  // 浅灰色背景
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
      item {
        DeviceHeader(
          megaDevice = megaDevice,
          isOnline = isOnline,
          onCheckChange = { checked ->
            viewModel.setCameraOnOffState(checked)
          }
        )
      }
      if (megaDevice.devicePn == "T8020") {
        item {
          CommonButton("Add sub device") {
            viewModel.launchWithLoading {
              megaDevice.iotDevice.cmd.bindDevice(60_000)
            }
          }
        }
      }
      if (isOnline) {

        item {
          val showOnlineContent by remember {
            derivedStateOf {
              viewModel.cameraOnOffState || megaDevice.deviceSn.isStation()
            }
          }
          OnlineContent(
            megaDevice = megaDevice,
            showOnlineContent,
            onRestartDevice = viewModel::restartDeviceNew,
            onRemoveDevice = {
              viewModel.removeDeviceNew(onSuccess = { navigator.popUntil { it is MegaMainScreen } })
            },
            onNavigateToVideoSettings = {
              navigator.push(VideoSettingsScreen(viewModel = viewModel.videoSettings))
            },
            onNavigateToAudioSettings = { navigator.push(AudioSettingsScreen(viewModel = viewModel.audioSettings)) },
            onNavigateToNotificationSettings = { navigator.push(NotificationScreen(viewModel = viewModel.notificationSettings)) },
            onNavigateToGeneralSettings = { navigator.push(GeneralScreen(viewModel = viewModel.generalSettings)) },
            onNavigateToPowerManager = { navigator.push(PowerManagerScreen(viewModel = viewModel)) },
            onNavigateToMotionDetection = { navigator.push(MotionDetectionScreen(viewModel = viewModel)) },
            onNavigateToHomeBaseConnection = {
              navigator.push(EdgeConnectionScreen(viewModel = viewModel))
            },
            onNavigateToLightSettings = {
              navigator.push(
                com.anker.esiotkit.sample.main.mega.device_settings.video_settings.LightSettingsScreen(
                  viewModel = viewModel.videoSettings
                )
              )
            },
            onNavigateToDashboard = {
              navigator.push(DashboardScreen(viewModel = viewModel))
            },
            onNavigateToPTZSettings = {
              navigator.push(
                com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings.PTZSettingsScreen(
                  viewModel = viewModel.ptzSettings
                )
              )
            },
            onNavigateToPackageDetection = {
              navigator.push(
                com.anker.esiotkit.sample.main.mega.device_settings.package_detection.PackageDetectionScreen(
                  deviceSn = megaDevice.deviceSn
                )
              )
            },
            navigator = navigator,
            viewModel = viewModel
          )
        }
      } else {
        item {
          OfflineContent(
            isOnline,
            onRestartDevice = viewModel::restartDeviceNew,
            onRemoveDevice = {
              viewModel.removeDeviceNew(onSuccess = { navigator.popUntil { it is MegaMainScreen } })
            },
            onNavigateToGeneralSettings = {
              navigator.push(GeneralScreen(viewModel = viewModel.generalSettings))
            }
          )
        }
      }
    }
    }
  }

  // 功能卡片数据类
  data class FeatureCard(
    val title: String,
    val subtitle: String? = null,
    val onClick: () -> Unit
  )

  @Composable
  fun OnlineContent(
    megaDevice: MegaDevice,
    cameraOnOffState: Boolean,
    onRestartDevice: () -> Unit,
    onRemoveDevice: () -> Unit,
    onNavigateToVideoSettings: () -> Unit,
    onNavigateToAudioSettings: () -> Unit,
    onNavigateToNotificationSettings: () -> Unit,
    onNavigateToGeneralSettings: () -> Unit,
    onNavigateToPowerManager: () -> Unit,
    onNavigateToMotionDetection: () -> Unit,
    onNavigateToHomeBaseConnection: () -> Unit,
    onNavigateToLightSettings: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    onNavigateToPTZSettings: () -> Unit,
    onNavigateToPackageDetection: () -> Unit,
    navigator: cafe.adriel.voyager.navigator.Navigator,
    viewModel: MegaDeviceSettingsViewModel
  ) {
    val deviceManager = koinInject<com.anker.esiotkit.domain_thing.api.ESIotDeviceManager>()
    Column(
      modifier = Modifier
        .fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      if (cameraOnOffState) {
        // 获取设备特定配置
        val settingsConfig = remember { DeviceSettingsConfigFactory.getConfig(megaDevice) }

        // 检查各功能是否支持 - 结合物模型和设备配置
        val homebaseEnabled = remember {
          settingsConfig.isHomeBaseVisible() &&
            (megaDevice.getProperty("homebase_connection") != null || megaDevice.deviceSn.isStation())
        }
        val motionEnabled = remember {
          settingsConfig.isMotionDetectionVisible() &&
            (megaDevice.getProperty(ThingIdentifier.MOTION_DETECTION_STATUS) != null ||
              megaDevice.getProperty(ThingIdentifier.NVR_MOTION_DETECTION) != null ||
              megaDevice.getProperty(ThingIdentifier.DEVICE_MOTION_DETECTION) != null)
        }
        val powerManagerEnabled = remember {
          settingsConfig.isPowerManagerVisible() &&
            megaDevice.getProperty(ThingIdentifier.POWER_MANAGER_MODE) != null
        }
        val lightEnabled = remember {
          val hasThingModelLight = megaDevice.hasProperty("light_switch") ||
            megaDevice.hasProperty(ThingIdentifier.SPOTLIGHT_SWITCH)
          val hasThingModelBrightness = megaDevice.hasProperty("spotlight_brightness") ||
            megaDevice.hasProperty("spotlight_brightness_level")
          val configVisible = settingsConfig.isLightSettingsVisible()
          val hasP2PSpotlight = megaDevice.iotDevice
            .getService<SpotlightSettingService>(ServiceConstants.SPOTLIGHT_SETTING) != null

          appLogger.i("LightSettings visibility check - configVisible=$configVisible, hasThingLight=$hasThingModelLight, hasThingBright=$hasThingModelBrightness, hasP2P=$hasP2PSpotlight, deviceSn=${megaDevice.iotDevice.deviceSn}")

          configVisible && (hasThingModelLight || hasThingModelBrightness || hasP2PSpotlight)
        }
        appLogger.i("LightSettings visibility result: lightEnabled=$lightEnabled, deviceSn=${megaDevice.iotDevice.deviceSn}")
        val dashboardEnabled = remember { settingsConfig.isDashboardVisible() }

        val packageDetectionEnabled = remember {
          deviceManager.packageDetection(megaDevice.deviceSn).isSupportPackageDetection()
        }
        val guardSwitch by megaDevice.rememberBooleanProperty("device_delivered_guard_switch")
        val packageDetectionText = if (guardSwitch) "On" else "Off"

        // 获取动态状态值
        val powerMode by megaDevice.rememberStringProperty(ThingIdentifier.POWER_MANAGER_MODE)
        val powerModeText = remember(powerMode) {
          when (powerMode) {
            "0" -> "Optimal Battery Life"
            "1" -> "Optimal Surveillance"
            "2" -> "Custom Recording"
            else -> "Optimal Battery Life"
          }
        }

        val lightSwitch = viewModel.videoSettings.lightSwitchState
        appLogger.i("MegaDeviceSettings/cardText: lightSwitch=$lightSwitch")
        val lightText = if (lightSwitch) "On" else "Off"

        // 动态构建功能卡片列表 - 只包含支持的功能
        val featureCards = remember(
          homebaseEnabled, motionEnabled, powerManagerEnabled,
          lightEnabled, dashboardEnabled, packageDetectionEnabled,
          powerModeText, lightText, packageDetectionText
        ) {
          buildList {
            if (homebaseEnabled) {
              add(FeatureCard(
                title = "Edge Connection",
                subtitle = "Powered by Edge E...",
                onClick = onNavigateToHomeBaseConnection
              ))
            }
            if (motionEnabled) {
              add(FeatureCard(
                title = "Motion Detection",
                onClick = onNavigateToMotionDetection
              ))
            }
            if (powerManagerEnabled) {
              add(FeatureCard(
                title = "Power Manager",
                subtitle = powerModeText,
                onClick = onNavigateToPowerManager
              ))
            }
            if (dashboardEnabled) {
              add(FeatureCard(
                title = "Dashboard",
                onClick = onNavigateToDashboard
              ))
            }
            if (lightEnabled) {
              add(FeatureCard(
                title = "Spotlight Settings",
                subtitle = lightText,
                onClick = onNavigateToLightSettings
              ))
            }
            if (packageDetectionEnabled) {
              add(FeatureCard(
                title = "Package Detection",
                subtitle = packageDetectionText,
                onClick = onNavigateToPackageDetection
              ))
            }
          }
        }

        // 动态网格布局 - 自动适应卡片数量
        FeatureCardsGridDynamic(featureCards)
      }

      // 设置列表
      SettingsColumn(
        megaDevice = megaDevice,
        cameraOnOffState,
        onNavigateToVideoSettings = onNavigateToVideoSettings,
        onNavigateToAudioSettings = onNavigateToAudioSettings,
        onNavigateToNotificationSettings = onNavigateToNotificationSettings,
        onNavigateToGeneralSettings = onNavigateToGeneralSettings,
        onNavigateToPTZSettings = onNavigateToPTZSettings
      )

      // 分享区域
      ShareSection()

      // 底部按钮
      BottomButtons(
        onRestartDevice = onRestartDevice,
        onRemoveDevice = onRemoveDevice,
        isChecked = cameraOnOffState
      )
    }
  }

  @Composable
  fun FeatureCardsGridDynamic(featureCards: List<FeatureCard>) {
    if (featureCards.isEmpty()) return

    // 每行2个，动态计算行数
    val rows = (featureCards.size + 1) / 2

    Column(
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      for (rowIndex in 0 until rows) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          // 第一个卡片
          val firstIndex = rowIndex * 2
          if (firstIndex < featureCards.size) {
            FeatureCardItem(
              card = featureCards[firstIndex],
              modifier = Modifier.weight(1f)
            )
          }

          // 第二个卡片
          val secondIndex = firstIndex + 1
          if (secondIndex < featureCards.size) {
            FeatureCardItem(
              card = featureCards[secondIndex],
              modifier = Modifier.weight(1f)
            )
          } else {
            // 占位，保持对齐
            Spacer(modifier = Modifier.weight(1f))
          }
        }
      }
    }
  }

  @Composable
  fun FeatureCardItem(
    card: FeatureCard,
    modifier: Modifier = Modifier
  ) {
    Column(
      modifier = modifier
        .clip(RoundedCornerShape(12.dp))
        .background(Color.White)
        .clickable { card.onClick() }
        .padding(16.dp)
        .heightIn(min = 80.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(
        text = card.title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = Color.Black,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (card.subtitle != null) {
        Text(
          text = card.subtitle,
          fontSize = 12.sp,
          color = Color.Gray,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }

  @Composable
  fun ShareSection() {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(Color.White)
    ) {
      SettingItemInGroup(
        title = "Share Device",
        onClick = { toastNotSupport() }
      )
      HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
      SettingItemInGroup(
        title = "Share Your Thoughts",
        onClick = { toastNotSupport() }
      )
    }
  }

  @Composable
  fun OfflineContent(
    isOnline: Boolean,
    onRestartDevice: () -> Unit,
    onRemoveDevice: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToGeneralSettings: () -> Unit
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    val deviceManager = koinInject<com.anker.esiotkit.domain_thing.api.ESIotDeviceManager>()
    Column(
        modifier = modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(Color.White)
      ) {
        SettingItemInGroup(title = "General", onClick = onNavigateToGeneralSettings)
      }
      BottomButtons(
        onRestartDevice = onRestartDevice,
        onRemoveDevice = onRemoveDevice,
        isChecked = isOnline
      )
    }
  }

  @Composable
  fun DeviceHeader(
    megaDevice: MegaDevice,
    modifier: Modifier = Modifier,
    isOnline: Boolean,
    onCheckChange: (Boolean) -> Unit
  ) {
    var showDialog by remember { mutableStateOf(false) }
    if (showDialog) {
      ButtonDialog(
        onDismissRequest = {
          showDialog = false
        },
        onCancel = { showDialog = false },
        onConfirm = {
          onCheckChange(false)
          showDialog = false
        },
        dialogTitle = "Do you want to turn off the camera?",
        dialogText = "The camera will be disabled and it won't record or notify you anymore. Your camera can't be set either.",
        buttonText = "Turn Off"
      )
    }
    Column(
      modifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(Color.White)
        .padding(16.dp)
    ) {
      // 第一行：设备名称 + 开关（通过 Flow 订阅 device_name 属性变化，修改名称后实时刷新）
      val deviceName by megaDevice.rememberStringProperty(ThingIdentifier.DEVICE_NAME)
      val displayName = deviceName.ifEmpty { megaDevice.iotDevice.deviceSn }
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = displayName,
          fontSize = 18.sp,
          fontWeight = FontWeight.Medium
        )
        if (isOnline) {
          PropertyThingContainer(megaDevice = megaDevice, identifier = ThingIdentifier.CAMERA_ON_OFF) {
            val cameraOnOffState by megaDevice.rememberBooleanProperty(ThingIdentifier.CAMERA_ON_OFF)
            Switch(
              checked = cameraOnOffState,
              onCheckedChange = {
                if (it) {
                  onCheckChange(it)
                } else {
                  showDialog = true
                }
              }
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // 第二行：状态图标（灯、电池、WiFi）
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        // 灯光状态 — getLightSwitch() 读取 FLOODLIGHT_MANUAL_SWITCH
        val lightService = megaDevice.iotDevice.getService<SpotlightSettingService>(ServiceConstants.SPOTLIGHT_SETTING)
        if (lightService != null) {
          val lightOn = lightService.getLightSwitch()
          StatusIcon(
            icon = Icons.Default.Lightbulb,
            isActive = lightOn,
            activeColor = Color(0xFFFFD700)
          )
        }

        // 电池状态
        PropertyThingContainer(megaDevice = megaDevice, identifier = ThingIdentifier.BATTERY_VALUE) {
          val batteryValue by megaDevice.rememberIntProperty(ThingIdentifier.BATTERY_VALUE)
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.BatteryFull,
              contentDescription = "Battery",
              tint = when {
                batteryValue > 50 -> Color(0xFF4CAF50)
                batteryValue > 20 -> Color(0xFFFFC107)
                else -> Color.Red
              },
              modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "$batteryValue%",
              fontSize = 12.sp,
              color = Color.Gray
            )
          }
        }

        // WiFi信号强度
        PropertyThingContainer(megaDevice = megaDevice, identifier = ThingIdentifier.DEVICE_WIFI_SIGNAL_STRENGTH) {
          val wifiStrength by megaDevice.rememberStringProperty(ThingIdentifier.DEVICE_WIFI_SIGNAL_STRENGTH)
          val rssi = wifiStrength.toIntOrNull() ?: 0
          Row(verticalAlignment = Alignment.CenterVertically) {
            WifiSignalIcon(
              level = rssiToLevel(rssi),
              modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "${rssi} dBm",
              fontSize = 12.sp,
              color = Color.Gray
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      // 设备图片
      val productDescription by remember {
        derivedStateOf {
          ProductInfoManager.securityProductInfo.find { it.categoryCode == megaDevice.devicePn || it.code == megaDevice.devicePn }
        }
      }
      productDescription?.let {
        AsyncImage(
          model = it.icon,
          contentDescription = it.name,
          modifier = Modifier
            .size(120.dp)
            .align(Alignment.CenterHorizontally)
            .clip(RoundedCornerShape(8.dp)),
          contentScale = ContentScale.Fit
        )
      }

      // 子设备图标
      SubDeviceIconsComposable(megaDevice, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
    }
  }

  @Composable
  fun StatusIcon(
    icon: ImageVector,
    isActive: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = if (isActive) activeColor else Color.LightGray,
      modifier = modifier.size(20.dp)
    )
  }

  @Composable
  fun SettingItem(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
  ) {
    val titleColor = if (enabled) Color.Black else Color.LightGray
    val subtitleColor = if (enabled) Color.Gray else Color.LightGray
    Column(
      modifier = modifier
        .fillMaxWidth()
        .clickable(enabled = enabled) { onClick() }
        .background(Color.White, shape = RoundedCornerShape(8.dp))
        .padding(16.dp)
        .heightIn(min = 64.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(
        text = title, fontSize = 16.sp, color = titleColor
      )
      if (subtitle != null) {
        Text(
          text = subtitle, fontSize = 14.sp, color = subtitleColor
        )
      }
    }
  }

  // 用于在 Column 组内的设置项，不带独立背景
  @Composable
  fun SettingItemInGroup(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showArrow: Boolean = true,
    onClick: () -> Unit
  ) {
    val titleColor = if (enabled) Color.Black else Color.LightGray
    val subtitleColor = if (enabled) Color.Gray else Color.LightGray
    val iconTint = if (enabled) Color.Gray else Color.LightGray
    Row(
      modifier = modifier
        .fillMaxWidth()
        .clickable(enabled = enabled) { onClick() }
        .padding(horizontal = 16.dp, vertical = 14.dp)
        .heightIn(min = 48.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        text = title,
        fontSize = 16.sp,
        color = titleColor,
        modifier = Modifier.weight(1f)
      )
      if (showArrow) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowForward,
          contentDescription = null,
          tint = iconTint,
          modifier = Modifier.size(18.dp)
        )
      }
    }
  }

  @Composable
  fun BottomButtons(onRestartDevice: () -> Unit, onRemoveDevice: () -> Unit, isChecked: Boolean) {
    var showRemoveDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    if (showRemoveDialog) {
      ButtonDialog(
        onDismissRequest = {
          showRemoveDialog = false
        },
        onCancel = { showRemoveDialog = false },
        onConfirm = {
          onRemoveDevice()
          showRemoveDialog = false
        },
        dialogText = "Are you sure you want to remove this device?",
        buttonText = "Remove"
      )
    }
    if (showRestartDialog) {
      ButtonDialog(
        onDismissRequest = {
          showRestartDialog = false
        },
        onCancel = { showRestartDialog = false },
        onConfirm = {
          onRestartDevice()
          showRestartDialog = false
        },
        dialogText = "Are you sure you want to restart this device?",
        buttonText = "Confirm"
      )
    }
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      if (isChecked) {
        // Restart Device - 蓝色边框
        OutlinedButton(
          onClick = { showRestartDialog = true },
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
          shape = RoundedCornerShape(24.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF005D8E))
        ) {
          Text(text = "Restart Device", color = Color(0xFF005D8E), fontSize = 16.sp)
        }
      }
      // Remove Device - 红色边框
      OutlinedButton(
        onClick = { showRemoveDialog = true },
        modifier = Modifier
          .fillMaxWidth()
          .height(48.dp),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red)
      ) {
        Text(text = "Remove Device", color = Color.Red, fontSize = 16.sp)
      }
    }
  }

  @Composable
  fun SettingsColumn(
    megaDevice: MegaDevice,
    isChecked: Boolean,
    onNavigateToVideoSettings: () -> Unit,
    onNavigateToAudioSettings: () -> Unit,
    onNavigateToNotificationSettings: () -> Unit,
    onNavigateToGeneralSettings: () -> Unit,
    onNavigateToPTZSettings: () -> Unit
  ) {
    val isNotNVR = !megaDevice.deviceSn.isStationNVR()
    val settingsConfig = remember { DeviceSettingsConfigFactory.getConfig(megaDevice) }

    // 检查是否支持 PTZ 功能
    val supportsPTZ = remember(megaDevice) {
      settingsConfig.isPTZSettingsVisible() &&
      (megaDevice.hasAction(ThingIdentifier.PTZ_ACTION_CONTROL) ||
       megaDevice.hasAction(ThingIdentifier.PTZ_CALIBRATE) ||
       megaDevice.hasProperty(ThingIdentifier.PTZ_TURN_SPEED) ||
       megaDevice.hasProperty(ThingIdentifier.VIDEO_AUTO_CRUISE_SWITCH))
    }
    appLogger.i("MegaDeviceSettings/visibility: supportsPTZ=$supportsPTZ, " +
      "hasPtzSpeed=${megaDevice.getProperty("ptz_speed") != null}, " +
      "hasAutoCruise=${megaDevice.getProperty("auto_cruise") != null}, " +
      "hasPtzAngleH=${megaDevice.getProperty("ptz_angle_h") != null}, " +
      "hasPtzTurnSpeed=${megaDevice.getProperty(ThingIdentifier.PTZ_TURN_SPEED) != null}, " +
      "hasVideoAutoCruise=${megaDevice.getProperty(ThingIdentifier.VIDEO_AUTO_CRUISE_SWITCH) != null}, " +
      "hasPtzCalibrate=${megaDevice.hasAction(ThingIdentifier.PTZ_CALIBRATE)}, " +
      "deviceSn=${megaDevice.deviceSn}")

    // 根据设备型号和状态决定显示哪些设置项
    // 参考 BatteryCam DefaultConfig.java: 摄像头关闭时，Video/Audio/Notification 应该完全隐藏
    val visibleSettings = remember(megaDevice, isChecked, isNotNVR, supportsPTZ) {
      buildList {
        // Video Settings, Audio Settings, Notification: 只在摄像头开启时显示
        if (isChecked) {
          if (isNotNVR && settingsConfig.isVideoSettingsVisible()) {
            add(Triple("Video Settings", true, onNavigateToVideoSettings))
          }
          if (isNotNVR && settingsConfig.isAudioSettingsVisible()) {
            add(Triple("Audio Settings", true, onNavigateToAudioSettings))
          }
          // Angle Settings (PTZ): 只在摄像头开启且支持 PTZ 时显示
          if (supportsPTZ) {
            add(Triple("Angle Settings", true, onNavigateToPTZSettings))
          }
          add(Triple("Notification", true, onNavigateToNotificationSettings))
        }
        // General 总是显示且可用
        add(Triple("General", true, onNavigateToGeneralSettings))
      }
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(Color.White)
    ) {
      visibleSettings.forEachIndexed { index, (title, enabled, onClick) ->
        SettingItemInGroup(
          title = title,
          enabled = enabled,
          onClick = onClick
        )
        if (index < visibleSettings.size - 1) {
          HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
      }
    }
  }
}