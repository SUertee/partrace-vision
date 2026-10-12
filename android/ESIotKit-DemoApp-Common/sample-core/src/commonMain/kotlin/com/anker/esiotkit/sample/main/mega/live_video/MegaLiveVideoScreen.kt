package com.anker.esiotkit.sample.main.mega.live_video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.ui.graphics.vector.path
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.media.live_stream.LiveMediaPlayer
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.sample.support.applicationContext
import com.anker.esiotkit.sample.support.exitFullScreen
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.support.fullScreen
import com.anker.esiotkit.sample.support.model.MegaPlayType
import com.anker.esiotkit.sample.support.permissions.rememberPermissionController
import com.anker.esiotkit.sample.support.toggleScreenOrientation
import com.anker.esiotkit.sample.ui.composable.BackHandler
import com.anker.esiotkit.sample.ui.composable.appendWhen
import com.anker.esiotkit.sample.ui.composable.rotatingDashedBorder
import com.anker.esiotkit.sample.ui.composable.simpleClickable
import com.anker.esiotkit.sample.ui.composable.state
import com.anker.esiotkit.sample.main.mega.device_settings.MegaDeviceSettingsScreen
import com.anker.esiotkit.sample.ui.component.CardGearIcon
import com.anker.esiotkit.sample.ui.component.ConfirmDialog
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.currentNavigator
import com.anker.esiotkit.sample.view.LiveStreamPlayView
import com.anker.esiotkit.support.ktx.msToDurationString
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.compose.BindEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.dataLogger

/**
 * @version 2024/6/12
 */
class MegaLiveVideoScreen constructor(
  private val deviceSn: String,
  private val playType: MegaPlayType
) : BaseScreen() {
  @Composable
  override fun dynamicTitle(): String {
    val device = MegaDeviceManager.getDeviceBy(deviceSn) ?: return deviceSn
    val deviceName by device.rememberStringProperty(ThingIdentifier.DEVICE_NAME)
    return deviceName.ifEmpty { deviceSn }
  }
  override val key: ScreenKey = uniqueScreenKey

  @Composable
  override fun RowScope.TitleRightIcon() {
    val megaDevice = remember { MegaDeviceManager.getDeviceBy(deviceSn) } ?: return
    val deviceManager = koinInject<com.anker.esiotkit.domain_thing.api.ESIotDeviceManager>()
    val viewModel = rememberScreenModel { MegaLiveStreamViewModel(megaDevice, deviceManager) }
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.supportsAlarm) {
      IconButton(
        onClick = {
          if (uiState.alarmCountdown <= 0) {
            viewModel.showAlarmDialog = true
          }
        }
      ) {
        Icon(
          imageVector = AlarmIcon,
          contentDescription = "Manual Alarm",
          tint = if (uiState.alarmCountdown > 0) Color(0xFFFF6B35) else Color(0xFF333333),
          modifier = Modifier.size(24.dp)
        )
      }
    }

    IconButton(
      onClick = {
        currentNavigator?.push(MegaDeviceSettingsScreen(deviceSn))
      }
    ) {
      Icon(
        imageVector = CardGearIcon,
        contentDescription = "Device Settings",
        tint = Color(0xFF333333),
        modifier = Modifier.size(24.dp)
      )
    }
  }

  @Composable
  override fun Content() {
    val megaDevice = remember { MegaDeviceManager.getDeviceBy(deviceSn) } ?: return
    val deviceManager = koinInject<com.anker.esiotkit.domain_thing.api.ESIotDeviceManager>()
    val viewModel = rememberScreenModel { MegaLiveStreamViewModel(megaDevice, deviceManager) }
    val uiState by viewModel.uiState.collectAsState()
    val fullScreen by fullScreen.collectAsState()
    var showQuickResponseDialog by state(false)
    var showPresetsDialog by state(false)
    var showPTZControlDialog by state(false)
    var showSingleDualSwitchDialog by state(false)
    var presetsList by state(listOf<PresetPosition>())
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(Unit) {
      dataLogger.i("MegaLiveVideoScreen LiveDebug: [SCREEN_COMPOSE]")
      onDispose {
        dataLogger.i("MegaLiveVideoScreen LiveDebug: [DISPOSE] viewModel.hashCode=${viewModel.hashCode()}")
        exitFullScreen()
        viewModel.action(MegaLiveStreamAction.Exit)
      }
    }

    if (showQuickResponseDialog) {
      val quickResponses = uiState.quickResponseList
      QuickResponseDialog(
        responses = quickResponses,
        onDismiss = { showQuickResponseDialog = false },
        onResponseSelected = { response ->
          viewModel.sendQuickResponse(response.id, response.message)
        }
      )
    }

    if (showPresetsDialog) {
      // 打开弹窗时查询一次设备，之后不再主动重查
      // preset.id 存储设备 0-based 索引（与协议一致）
      LaunchedEffect(Unit) {
        com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings.PresetPositionManager.initDevice(deviceSn)
        // 先用本地缓存立即渲染（0-based，共展示 0~3 共4个槽位）
        presetsList = (0..3).map { i ->
          PresetPosition(i.toString(), "Position ${i + 1}",
            isSet = com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings.PresetPositionManager.isPresetSet(deviceSn, i))
        }
        // 向设备查询一次最新状态，parsePresetPositions 返回 0-based 索引列表
        viewModel.queryPresetPositions { setPresets ->
          presetsList = (0..3).map { i ->
            PresetPosition(i.toString(), "Position ${i + 1}", isSet = setPresets.contains(i))
          }
        }
      }

      // 监听其他界面（如预设位管理页）的变更，直接同步 cache，不重查设备
      LaunchedEffect(Unit) {
        com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings.PresetPositionManager.presetChanges.collect { changedDeviceSn ->
          if (changedDeviceSn == deviceSn) {
            presetsList = (0..3).map { i ->
              PresetPosition(i.toString(), "Position ${i + 1}",
                isSet = com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings.PresetPositionManager.isPresetSet(deviceSn, i))
            }
          }
        }
      }

      PresetsDialog(
        presets = presetsList,
        onDismiss = { showPresetsDialog = false },
        onPresetSelected = { preset ->
          // preset.id 是 0-based，直接传给设备
          viewModel.jumpToPreset(preset.id)
        },
        onPresetDeleted = { preset ->
          val index = preset.id.toIntOrNull() ?: return@PresetsDialog
          // 1. 先同步更新本地 UI
          presetsList = presetsList.map { if (it.id == preset.id) it.copy(isSet = false) else it }
          // 2. 异步发删除指令（preset.id 是 0-based，与设备协议一致，不触发 presetChanges 通知）
          viewModel.deletePreset(preset.id)
          coroutineScope.launch {
            com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings.PresetPositionManager.setPresetStatus(deviceSn, index, false)
            com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings.PresetPositionManager.removeThumbnail(deviceSn, index)
          }
        },
        onSaveCurrentPosition = {
          val emptySlot = presetsList.firstOrNull { !it.isSet } ?: return@PresetsDialog
          val index = emptySlot.id.toIntOrNull() ?: return@PresetsDialog
          // 1. 先同步更新本地 UI
          presetsList = presetsList.map { if (it.id == emptySlot.id) it.copy(isSet = true) else it }
          // 2. 异步发保存指令（0-based index，不触发 presetChanges 通知）
          viewModel.saveCurrentPreset(emptySlot.id, "Position ${emptySlot.id}")
          coroutineScope.launch {
            com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings.PresetPositionManager.setPresetStatus(deviceSn, index, true)
          }
        }
      )
    }

    // 对齐 mega: PIR 角度检查拒绝(-502)后弹确认框，确认后以 FORCE_SET 重试
    viewModel.pendingPresetForceConfirm?.let { confirmState ->
      ConfirmDialog(
        title = "Do you want to continue to save?",
        description = "Since the effective area of PIR detection is mainly at the lower half of the screen, it is recommended to increase the angle of the preset position to optimize the monitoring effect.",
        onConfirm = { viewModel.confirmPresetForceSave() },
        onCancel = {
          viewModel.dismissPresetForceConfirm()
          // 该页面保存前乐观更新了 UI，取消时回滚对应槽位
          presetsList = presetsList.map {
            if (it.id == confirmState.index.toString()) it.copy(isSet = false) else it
          }
        }
      )
    }

    if (viewModel.showAlarmDialog) {
      ManualAlarmDialog(
        supportedTypes = viewModel.getAlarmEnumList(),
        onDismiss = { viewModel.showAlarmDialog = false },
        onTypeSelected = { type ->
          viewModel.action(MegaLiveStreamAction.TriggerAlarm(type))
        }
      )
    }

    if (viewModel.nightVisionDialogVisible) {
      appLogger.i("MegaLiveVideoScreen/NightVisionDialog: showing, currentMode=${uiState.nightVisionMode}, options=${viewModel.nightVisionModeOptions.size}")
      NightVisionDialog(
        currentMode = uiState.nightVisionMode,
        supportedModes = viewModel.nightVisionModeOptions,
        onDismiss = {
          appLogger.i("MegaLiveVideoScreen/NightVisionDialog: dismissed")
          viewModel.nightVisionDialogVisible = false
        },
        onModeSelected = { mode ->
          appLogger.i("MegaLiveVideoScreen/NightVisionDialog: mode selected=$mode, dispatching SetNightVisionMode")
          viewModel.action(MegaLiveStreamAction.SetNightVisionMode(mode))
        }
      )
    }

    if (showSingleDualSwitchDialog) {
      androidx.compose.material3.AlertDialog(
        onDismissRequest = { showSingleDualSwitchDialog = false },
        title = { androidx.compose.material3.Text("Camera View Mode") },
        text = {
          androidx.compose.foundation.layout.Column {
            val modes = uiState.supportedVideoModes
            if (modes.isEmpty()) {
              androidx.compose.material3.CircularProgressIndicator()
            } else {
              modes.forEach { option ->
                val isCurrent = uiState.currentVideoMode == option.mode
                androidx.compose.material3.TextButton(
                  onClick = {
                    viewModel.action(MegaLiveStreamAction.SwitchVideoMode(option.mode))
                    showSingleDualSwitchDialog = false
                  }
                ) {
                  androidx.compose.material3.Text(
                    if (isCurrent) "\u2713 ${option.label}" else "    ${option.label}"
                  )
                }
              }
            }
          }
        },
        confirmButton = {
          androidx.compose.material3.TextButton(
            onClick = { showSingleDualSwitchDialog = false }
          ) {
            androidx.compose.material3.Text("Cancel")
          }
        }
      )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
      val localContext = applicationContext
      Column(modifier = Modifier.fillMaxWidth().align(Alignment.TopStart)) {
        if (!fullScreen) {
          BaseTitle(true)
        }
        // Alarm capsule overlay
        if (uiState.alarmCountdown > 0) {
          Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterEnd
          ) {
            AlarmCapsuleOverlay(
              alarmTypeName = uiState.alarmTypeName,
              countdown = uiState.alarmCountdown,
              onStop = { viewModel.action(MegaLiveStreamAction.StopAlarm) }
            )
          }
        }
        PlayerContainer(
          onViewCreated = {
            dataLogger.i("MegaLiveVideoScreen LiveDebug: [ON_VIEW_CREATED] dispatching Play playType=$playType")
            viewModel.action(MegaLiveStreamAction.Play(playType))
          }
        )
      }
      Column(
        modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart)
          .background(Color.White.copy(alpha = 0.5f)).navigationBarsPadding()
      ) {
        val permissionController = rememberPermissionController()
        BindEffect(permissionController)
        val scope = rememberCoroutineScope()
        if (!fullScreen) {
          LiveVideoController(
            rate = uiState.rate,
            isBuffering = uiState.isBuffering,
            isPlaying = uiState.isPlaying,
            onClickPlay = { viewModel.action(MegaLiveStreamAction.Play(playType)) },
            recordStatus = uiState.recordStatus,
            recordTime = uiState.recordTime,
            onClickFullScreen = { toggleScreenOrientation() }
          )
          LiveVideoFunctions(
            megaDevice = megaDevice,
            uiState = uiState,
            onClickCapture = { viewModel.action(MegaLiveStreamAction.Capture) },
            onClickRecord = { viewModel.action(MegaLiveStreamAction.Record) },
            onClickMute = { viewModel.action(MegaLiveStreamAction.Mute) },
            onClickLight = { viewModel.action(MegaLiveStreamAction.Light) },
            onClickAITracking = { viewModel.action(MegaLiveStreamAction.AITracking) },
            onClickCalibration = { viewModel.action(MegaLiveStreamAction.Calibration) },
            onClick360 = { viewModel.action(MegaLiveStreamAction.Round360) },
              onClickPanorama = {
              viewModel.action(MegaLiveStreamAction.Panorama(localContext))
            },
            onClickQuickResponse = {
              showQuickResponseDialog = true
              viewModel.action(MegaLiveStreamAction.LoadQuickResponseList)
            },
            onClickPresets = { showPresetsDialog = true },
            onClickPTZControl = { showPTZControlDialog = true },
            onClickSingleDualSwitch = {
              viewModel.action(MegaLiveStreamAction.LoadSupportedVideoModes)
              showSingleDualSwitchDialog = true
            },
            onClickNightVision = {
              viewModel.action(MegaLiveStreamAction.NightVision)
            },
            onClickTalk = {
              scope.launch {
                if (permissionController.isPermissionGranted(Permission.RECORD_AUDIO)) {
                  viewModel.action(MegaLiveStreamAction.Talk)
                } else {
                  permissionController.providePermission(Permission.RECORD_AUDIO)
                }
              }
            },
          )
        }
      }

      // PTZ control overlay - inside main Box, on top of everything
      if (showPTZControlDialog) {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center
        ) {
          PTZControlPanel(
            onDismiss = { showPTZControlDialog = false },
            onDirectionControl = { direction ->
              // 单击：cmdType=1，单次转动
              viewModel.controlPTZ(direction.name)
            },
            onLongPressStart = { direction ->
              // 长按开始：cmdType=2
              viewModel.startPTZMovement(direction.name)
            },
            onLongPressStop = { direction ->
              // 长按结束：cmdType=3
              viewModel.stopPTZMovement(direction.name)
            }
          )
        }
      }

      val panoPath = uiState.panoramaResultPath
      if (panoPath != null) {
        PanoramaResultOverlay(
          imagePath = panoPath,
          controlStatus = uiState.controlPositionStatus,
          onClose = { viewModel.dismissPanoramaResult() },
          onRefresh = {
            viewModel.dismissPanoramaResult()
            viewModel.action(MegaLiveStreamAction.Panorama(localContext))
          },
          onTap = { xPct, yPct ->
            viewModel.panoramaControlPosition(xPct, yPct)
          }
        )
      }
    }

    BackHandler {
      onBackClick()
    }
  }

  @Composable
  fun PlayerContainer(
    onClick: () -> Unit = {},
    onViewCreated: () -> Unit
  ) = Box(
    modifier = Modifier.background(Color.Black).fillMaxWidth()
  ) {
    val megaDevice = remember { MegaDeviceManager.getDeviceBy(deviceSn) } ?: return
    val deviceManager = koinInject<com.anker.esiotkit.domain_thing.api.ESIotDeviceManager>()
    val viewModel = rememberScreenModel { MegaLiveStreamViewModel(megaDevice, deviceManager) }
    viewModel.recreatePlayerIfNeeded()
    val fullScreen by fullScreen.collectAsState()
    var showController by state(true)
    LaunchedEffect(showController) {
      if (showController && fullScreen) {
        delay(3000)
        showController = false
      }
    }
    AnimatedVisibility(
      visible = showController && fullScreen,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.zIndex(0.99f)
    ) {
      Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f))) {
        Icon(
          painter = rememberVectorPainter(image = Icons.AutoMirrored.Filled.ArrowBack),
          contentDescription = "",
          modifier = Modifier.clickable {
            onBackClick()
          }.padding(16.dp),
          tint = Color.White
        )
      }
    }
    LiveVideoPlayerView(
      modifier = Modifier.aspectRatio(viewModel.aspectRatio).align(Alignment.TopCenter).simpleClickable {
        showController = !showController
        onClick()
      },
      liveMediaPlayer = viewModel.liveMediaPlayer,
      onViewCreated = onViewCreated
    )
  }

  @Composable
  fun PanoramaResultOverlay(
    imagePath: String,
    controlStatus: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle,
    onClose: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onTap: (xPercent: Int, yPercent: Int) -> Unit = { _, _ -> }
  ) {
    val platformContext = LocalPlatformContext.current
    var scale by state(1f)
    var offsetX by state(0f)
    var offsetY by state(0f)
    var crosshairOffset by state<Offset?>(null)
    var markerOffset by state(Offset.Zero)
    var showMorePanel by state(false)
    val scope = rememberCoroutineScope()

    val crosshairColor = when (controlStatus) {
      MegaLiveFunctionStatus.Running -> Color(0xFF005D8E)
      MegaLiveFunctionStatus.Error -> Color(0xFFF44336)
      MegaLiveFunctionStatus.Loading -> Color.White
      else -> Color.White
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.95f))
        .statusBarsPadding()
        .zIndex(2f)
        .pointerInput(Unit) {
          detectTransformGestures { _, pan, zoom, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            if (scale > 1f) {
              offsetX += pan.x
              offsetY += pan.y
            } else {
              offsetX = 0f; offsetY = 0f
            }
          }
        }
    ) {
      val model = ImageRequest.Builder(platformContext)
        .data(imagePath)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .build()

      AsyncImage(
        model = model,
        contentDescription = "Panorama result",
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer {
            scaleX = scale; scaleY = scale
            translationX = offsetX; translationY = offsetY
          },
        contentScale = ContentScale.Fit
      )

      Box(
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            detectTapGestures { offset ->
              // Account for graphicsLayer scale/translation (aligns with Mega Matrix.invert)
              val imageX = (offset.x - offsetX) / scale
              val imageY = (offset.y - offsetY) / scale
              val pctX = (imageX / size.width * 100).toInt().coerceIn(0, 100)
              val pctY = (imageY / size.height * 100).toInt().coerceIn(0, 100)
              crosshairOffset = offset
              markerOffset = offset
              onTap(pctX, pctY)
              scope.launch {
                delay(500)
                crosshairOffset = null
              }
            }
          }
      )

      // Full-screen crosshair (aligns with Mega PanoramaNewManager:1149-1176 DrawView)
      crosshairOffset?.let { pos ->
        Canvas(Modifier.fillMaxSize()) {
          val gap = 15f
          drawLine(crosshairColor, Offset(0f, pos.y), Offset(pos.x - gap, pos.y), strokeWidth = 2f)
          drawLine(crosshairColor, Offset(pos.x + gap, pos.y), Offset(size.width, pos.y), strokeWidth = 2f)
          drawLine(crosshairColor, Offset(pos.x, 0f), Offset(pos.x, pos.y - gap), strokeWidth = 2f)
          drawLine(crosshairColor, Offset(pos.x, pos.y + gap), Offset(pos.x, size.height), strokeWidth = 2f)
        }
      }

      // Persistent marker dot (aligns with Mega PanoramaNewManager:647-685 image_pos)
      if (markerOffset != Offset.Zero) {
        val pos = markerOffset
        Canvas(Modifier.fillMaxSize()) {
          drawCircle(Color.White, radius = 6f, center = pos)
          drawCircle(crosshairColor, radius = 3f, center = pos)
        }
      }

      // Loading indicator
      if (controlStatus == MegaLiveFunctionStatus.Loading) {
        CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center)
        )
      }

      Icon(
        painter = rememberVectorPainter(image = Icons.AutoMirrored.Filled.ArrowBack),
        contentDescription = "Close panorama",
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(16.dp)
          .clickable { onClose() },
        tint = Color.White
      )

      // More button (aligns with Mega PanoramaNewManager:312-322 ic_pano_more)
      Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(16.dp)
      ) {
        Icon(
          painter = rememberVectorPainter(image = MoreVertIcon),
          contentDescription = "More",
          modifier = Modifier
            .size(24.dp)
            .clickable { showMorePanel = true },
          tint = Color.White
        )
        DropdownMenu(
          expanded = showMorePanel,
          onDismissRequest = { showMorePanel = false }
        ) {
          DropdownMenuItem(
            text = { Text("Refresh") },
            onClick = {
              showMorePanel = false
              onRefresh()
            }
          )
          DropdownMenuItem(
            text = { Text("Close") },
            onClick = {
              showMorePanel = false
              onClose()
            }
          )
        }
      }

      Text(
        text = "Pinch to zoom • Drag to pan",
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 12.sp,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 32.dp)
      )
    }
  }

  @Composable
  fun LiveVideoPlayerView(
    modifier: Modifier = Modifier,
    liveMediaPlayer: LiveMediaPlayer,
    onViewCreated: () -> Unit
  ) {
    val fullScreen by fullScreen.collectAsState()
    Box(modifier = modifier.appendWhen(!fullScreen) {
      fillMaxWidth()
    }.appendWhen(fullScreen) {
      fillMaxHeight()
    }.background(color = Color.Black)) {
      LiveStreamPlayView(
        modifier = Modifier.fillMaxSize(), liveMediaPlayer = liveMediaPlayer,
        onViewCreated = onViewCreated
      )
    }
  }

  @Composable
  fun LiveVideoController(
    rate: Int,
    isBuffering: Boolean,
    isPlaying: Boolean,
    recordStatus: MegaLiveFunctionStatus,
    recordTime: Long,
    onClickPlay: () -> Unit,
    onClickFullScreen: () -> Unit,
  ) {
    SideEffect {
      dataLogger.i("MegaLiveVideoScreen LiveDebug: [CONTROLLER_RENDER] isPlaying=$isPlaying isBuffering=$isBuffering")
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(8.dp)
    ) {
      val playIcon = if (isPlaying) {
        Icons.Default.Pause
      } else {
        Icons.Default.PlayArrow
      }
      Icon(
        imageVector = playIcon, contentDescription = "",
        modifier = Modifier.size(48.dp).clickable {
          onClickPlay()
        },
      )
      if (isBuffering) {
        CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp).size(24.dp))
      }
      Spacer(modifier = Modifier.weight(1f))
      if (recordStatus.running) {
        Text(
          text = "${recordTime.msToDurationString()}s", fontSize = 12.sp,
          modifier = Modifier.padding(end = 16.dp), color = Color.Red
        )
      }
      Text(
        text = "$rate KB/s", fontSize = 12.sp,
        modifier = Modifier.testTag("rate-tag").layoutId("rate-layout-id")
      )
      Icon(
        imageVector = Icons.Default.Fullscreen, contentDescription = "",
        modifier = Modifier.size(48.dp).clickable {
          onClickFullScreen()
        },
      )
    }
  }

  @Composable
  fun LiveVideoFunctions(
    modifier: Modifier = Modifier,
    megaDevice: MegaDevice,
    uiState: MegaLiveStreamUiState,
    onClickCapture: () -> Unit = {},
    onClickRecord: () -> Unit = {},
    onClickMute: () -> Unit = {},
    onClickTalk: () -> Unit = {},
    onClickLight: () -> Unit = {},
    onClickAITracking: () -> Unit = {},
    onClickCalibration: () -> Unit = {},
    onClick360: () -> Unit = {},
    onClickPanorama: () -> Unit = {},
    onClickQuickResponse: () -> Unit = {},
    onClickPresets: () -> Unit = {},
    onClickPTZControl: () -> Unit = {},
    onClickSingleDualSwitch: () -> Unit = {},
    onClickNightVision: () -> Unit = {},
  ) {
    LazyVerticalGrid(
      modifier = modifier.padding(8.dp),
      columns = GridCells.Fixed(5),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      if (uiState.supportsRecord) {
        item {
          FunctionalButton(
            icon = Icons.Default.VideoCall,
            text = "Record",
            onClick = onClickRecord,
            status = uiState.recordStatus,
          )
        }
      }
      if (uiState.supportsScreenshot) {
        item {
          FunctionalButton(
            icon = Icons.Default.ContentCut,
            text = "Screenshot",
            onClick = onClickCapture,
          )
        }
      }
      if (uiState.supportsTalk) {
        item {
          FunctionalButton(
            icon = Icons.Default.Call,
            text = "Talk",
            onClick = onClickTalk,
            status = uiState.talkStatus,
          )
        }
      }
      if (uiState.supportsMute) {
        item {
          FunctionalButton(
            icon = if (uiState.muteStatus.running) {
              Icons.AutoMirrored.Filled.VolumeMute
            } else {
              Icons.AutoMirrored.Filled.VolumeUp
            },
            text = "Sound",
            onClick = onClickMute,
            status = uiState.muteStatus,
          )
        }
      }
      if (uiState.supportsLight) {
        item {
          FunctionalButton(
            icon = Icons.Default.Lightbulb,
            text = "Light",
            onClick = onClickLight,
            status = uiState.lightStatus,
          )
        }
      }
      if (uiState.supportsTracking) {
        item {
          FunctionalButton(
            icon = TrackIcon,
            text = "Track",
            onClick = onClickAITracking,
            status = uiState.trackingStatus,
          )
        }
      }
      if (uiState.supportsCalibration) {
        item {
          FunctionalButton(
            icon = CalibrateIcon,
            text = "Calibrate",
            onClick = onClickCalibration,
            status = uiState.calibrationStatus,
          )
        }
      }
      if (uiState.supports360) {
        item {
          FunctionalButton(
            icon = Round360Icon,
            text = "360°",
            onClick = onClick360,
            status = uiState.round360Status,
          )
        }
      }
      if (uiState.supportsPanorama) {
        item {
          FunctionalButton(
            icon = PanoramaIcon,
            text = "Pano",
            onClick = onClickPanorama,
            status = uiState.panoramaStatus,
          )
        }
      }

      if (uiState.supportsQuickResponse) {
        item {
          FunctionalButton(
            icon = Icons.AutoMirrored.Filled.Message,
            text = "Quick",
            onClick = onClickQuickResponse,
          )
        }
      }
      if (uiState.supportsPreset) {
        item {
          FunctionalButton(
            icon = PresetIcon,
            text = "Preset",
            onClick = onClickPresets,
          )
        }
      }
      if (uiState.supportsPTZControl) {
        item {
          FunctionalButton(
            icon = Icons.Default.ControlCamera,
            text = "PTZ",
            onClick = onClickPTZControl,
          )
        }
      }
      if (uiState.supportsSingleDualSwitch) {
        item {
          FunctionalButton(
            icon = Icons.Default.SwapHoriz,
            text = "Dual View",
            onClick = onClickSingleDualSwitch,
          )
        }
      }
      if (uiState.supportsNightVision) {
        item {
          FunctionalButton(
            icon = Icons.Default.NightsStay,
            text = "Night",
            onClick = onClickNightVision,
          )
        }
      }
    }
  }

  @Composable
  private fun FunctionalButton(
    icon: ImageVector,
    text: String = icon.name,
    modifier: Modifier = Modifier,
    status: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle,
    onClick: () -> Unit = {},
  ) {
    val bgColor by animateColorAsState(
      when {
        status.running -> Color(0xFF005D8E).copy(alpha = 0.15f)
        else -> Color.White
      }
    )
    val iconColor by animateColorAsState(
      when {
        status.running -> Color(0xFF005D8E)
        status == MegaLiveFunctionStatus.Error -> Color(0xFFF44336)
        else -> Color(0xFF333333)
      }
    )

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier
        .background(color = bgColor, shape = RoundedCornerShape(8.dp))
        .border(
          width = if (status.running) 1.5.dp else 0.5.dp,
          color = if (status.running) Color(0xFF005D8E) else Color(0xFFDDDDDD),
          shape = RoundedCornerShape(8.dp)
        )
        .appendWhen(status.loading) { rotatingDashedBorder() }
        .clickable { 
          dataLogger.i("MegaLiveVideoScreen/FunctionalButton: clickable=$text")
          onClick() 
        }
        .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = text,
        tint = iconColor,
        modifier = Modifier.size(24.dp)
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = text,
        fontSize = 9.sp,
        color = iconColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }

  override fun onBackClick() {
    if (fullScreen.value) {
      exitFullScreen()
    } else {
      super.onBackClick()
    }
  }

}

private val AlarmIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "Alarm", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 1024f, viewportHeight = 1024f
  ).apply {
    path(
      fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
      pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
    ) {
      // Outer bell shell
      moveTo(506.026667f, 218.88f)
      arcToRelative(311.893333f, 311.893333f, 0f, false, false, -306.773334f, 316.586667f)
      verticalLineToRelative(328.533333f)
      horizontalLineToRelative(625.493334f)
      verticalLineToRelative(-316.586667f)
      arcToRelative(323.413333f, 323.413333f, 0f, false, false, -318.72f, -328.533333f)
      close()
      // Inner cutout (creates hollow bell via EvenOdd)
      moveTo(768f, 805.546667f)
      horizontalLineToRelative(-341.333333f)
      verticalLineTo(512f)
      horizontalLineTo(369.92f)
      verticalLineToRelative(293.546667f)
      horizontalLineTo(256f)
      verticalLineToRelative(-270.08f)
      arcToRelative(256f, 256f, 0f, false, true, 250.026667f, -258.133334f)
      arcTo(266.24f, 266.24f, 0f, false, true, 768f, 547.413333f)
      close()
      // Bottom bar (absolute moveTo)
      moveTo(938.666667f, 922.88f)
      verticalLineTo(981.333333f)
      horizontalLineTo(85.333333f)
      verticalLineToRelative(-58.453333f)
      close()
      // Top spike (absolute moveTo)
      moveTo(540.586667f, 160f)
      horizontalLineToRelative(-57.173334f)
      verticalLineTo(42.666667f)
      horizontalLineToRelative(57.173334f)
      close()
      // Left spark (absolute moveTo)
      moveTo(245.76f, 266.24f)
      lineTo(177.066667f, 178.346667f)
      lineToRelative(42.666666f, -36.693334f)
      lineTo(290.133333f, 229.546667f)
      close()
      // Right spark (absolute moveTo)
      moveTo(773.12f, 266.24f)
      lineToRelative(-42.666667f, -37.973333f)
      lineToRelative(72.96f, -87.466667f)
      lineToRelative(42.666667f, 38.4f)
      close()
    }
  }.build()
}

private val CalibrateIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "Calibrate",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 1024f,
    viewportHeight = 1024f
  ).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)) {
      // Outer crosshair
      moveTo(981.333f, 469.333f)
      lineTo(955.733f, 469.333f)
      curveTo(938.667f, 256f, 768f, 85.333f, 554.667f, 64f)
      lineTo(554.667f, 42.667f)
      curveTo(554.667f, 17.067f, 537.6f, 0f, 512f, 0f)
      curveTo(486.4f, 0f, 469.333f, 17.067f, 469.333f, 42.667f)
      lineTo(469.333f, 64f)
      curveTo(256f, 85.333f, 85.333f, 256f, 68.267f, 469.333f)
      lineTo(42.667f, 469.333f)
      curveTo(17.067f, 469.333f, 0f, 486.4f, 0f, 512f)
      curveTo(0f, 537.6f, 17.067f, 554.667f, 42.667f, 554.667f)
      lineTo(68.267f, 554.667f)
      curveTo(85.333f, 768f, 256f, 938.667f, 469.333f, 955.733f)
      lineTo(469.333f, 981.333f)
      curveTo(469.333f, 1006.933f, 486.4f, 1024f, 512f, 1024f)
      curveTo(537.6f, 1024f, 554.667f, 1006.933f, 554.667f, 981.333f)
      lineTo(554.667f, 955.733f)
      curveTo(768f, 934.4f, 938.667f, 763.733f, 955.733f, 554.667f)
      lineTo(981.333f, 554.667f)
      curveTo(1006.933f, 554.667f, 1024f, 537.6f, 1024f, 512f)
      curveTo(1024f, 486.4f, 1006.933f, 469.333f, 981.333f, 469.333f)
      close()
      // Top-right quadrant
      moveTo(870.4f, 469.333f)
      lineTo(763.733f, 469.333f)
      curveTo(746.667f, 362.667f, 661.333f, 277.333f, 554.667f, 260.267f)
      lineTo(554.667f, 153.6f)
      curveTo(721.067f, 170.667f, 853.333f, 302.933f, 870.4f, 469.333f)
      close()
      // Circle
      moveTo(512f, 682.667f)
      curveTo(418.133f, 682.667f, 341.333f, 605.867f, 341.333f, 512f)
      curveTo(341.333f, 418.133f, 418.133f, 341.333f, 512f, 341.333f)
      curveTo(605.867f, 341.333f, 682.667f, 418.133f, 682.667f, 512f)
      curveTo(682.667f, 605.867f, 605.867f, 682.667f, 512f, 682.667f)
      close()
      // Top-left quadrant
      moveTo(469.333f, 153.6f)
      lineTo(469.333f, 260.267f)
      curveTo(362.667f, 277.333f, 277.333f, 362.667f, 260.267f, 469.333f)
      lineTo(153.6f, 469.333f)
      curveTo(170.667f, 302.933f, 302.933f, 170.667f, 469.333f, 153.6f)
      close()
      // Bottom-left quadrant
      moveTo(153.6f, 554.667f)
      lineTo(260.267f, 554.667f)
      curveTo(277.333f, 661.333f, 362.667f, 746.667f, 469.333f, 763.733f)
      lineTo(469.333f, 870.4f)
      curveTo(302.933f, 853.333f, 170.667f, 721.067f, 153.6f, 554.667f)
      close()
      // Bottom-right quadrant
      moveTo(554.667f, 870.4f)
      lineTo(554.667f, 763.733f)
      curveTo(661.333f, 746.667f, 746.667f, 661.333f, 763.733f, 554.667f)
      lineTo(870.4f, 554.667f)
      curveTo(853.333f, 721.067f, 721.067f, 853.333f, 554.667f, 870.4f)
      close()
    }
    path(fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)) {
      // Inner crosshair
      moveTo(597.333f, 469.333f)
      lineTo(554.667f, 469.333f)
      lineTo(554.667f, 426.667f)
      curveTo(554.667f, 401.067f, 537.6f, 384f, 512f, 384f)
      curveTo(486.4f, 384f, 469.333f, 401.067f, 469.333f, 426.667f)
      lineTo(469.333f, 469.333f)
      lineTo(426.667f, 469.333f)
      curveTo(401.067f, 469.333f, 384f, 486.4f, 384f, 512f)
      curveTo(384f, 537.6f, 401.067f, 554.667f, 426.667f, 554.667f)
      lineTo(469.333f, 554.667f)
      lineTo(469.333f, 597.333f)
      curveTo(469.333f, 622.933f, 486.4f, 640f, 512f, 640f)
      curveTo(537.6f, 640f, 554.667f, 622.933f, 554.667f, 597.333f)
      lineTo(554.667f, 554.667f)
      lineTo(597.333f, 554.667f)
      curveTo(622.933f, 554.667f, 640f, 537.6f, 640f, 512f)
      curveTo(640f, 486.4f, 618.667f, 469.333f, 597.333f, 469.333f)
      close()
    }
  }.build()
}

private val PresetIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "Preset", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 1024f, viewportHeight = 1024f
  ).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(Color(0xFF231F20))) {
      moveTo(511.954f, 250.972f)
      curveTo(438.245f, 250.972f, 378.285f, 309.85f, 378.285f, 382.205f)
      curveTo(378.285f, 454.544f, 438.245f, 513.362f, 511.954f, 513.362f)
      curveTo(585.662f, 513.362f, 645.594f, 454.544f, 645.594f, 382.205f)
      curveTo(645.594f, 309.849f, 585.662f, 250.972f, 511.954f, 250.972f)
      moveTo(511.954f, 455.253f)
      curveTo(470.978f, 455.253f, 437.643f, 422.473f, 437.643f, 382.174f)
      curveTo(437.643f, 341.89f, 470.977f, 309.097f, 511.954f, 309.097f)
      curveTo(552.9f, 309.097f, 586.233f, 341.89f, 586.233f, 382.174f)
      curveTo(586.233f, 422.473f, 552.9f, 455.253f, 511.954f, 455.253f)
      close()
    }
    path(fill = androidx.compose.ui.graphics.SolidColor(Color(0xFF231F20))) {
      moveTo(824.812f, 401.685f)
      curveTo(824.812f, 232.244f, 684.464f, 94.407f, 511.954f, 94.407f)
      curveTo(339.475f, 94.407f, 199.186f, 232.244f, 199.186f, 401.685f)
      curveTo(199.186f, 534.57f, 391.793f, 802.331f, 474.648f, 911.27f)
      curveTo(483.372f, 922.762f, 497.331f, 929.591f, 511.954f, 929.591f)
      curveTo(526.544f, 929.591f, 540.535f, 922.762f, 549.288f, 911.27f)
      curveTo(632.115f, 802.331f, 824.812f, 534.603f, 824.812f, 401.685f)
      moveTo(516.888f, 864.759f)
      lineTo(511.954f, 871.317f)
      lineTo(507.02f, 864.759f)
      curveTo(350.607f, 656.929f, 253.37f, 479.47f, 253.37f, 401.685f)
      curveTo(253.37f, 261.622f, 369.35f, 147.672f, 511.954f, 147.672f)
      curveTo(654.529f, 147.672f, 770.507f, 261.622f, 770.507f, 401.685f)
      curveTo(770.538f, 479.5f, 673.361f, 656.929f, 516.888f, 864.759f)
      close()
    }
  }.build()
}

private val Round360Icon: ImageVector by lazy {
  ImageVector.Builder(
    name = "Round360", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 1024f, viewportHeight = 1024f
  ).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black)) {
      // Top-right arc and text area
      moveTo(906.752f, 387.584f)
      curveTo(889.344f, 377.856f, 867.328f, 384f, 857.088f, 401.408f)
      curveTo(847.36f, 418.816f, 853.504f, 440.832f, 870.912f, 451.072f)
      curveTo(919.552f, 478.72f, 946.176f, 509.952f, 946.176f, 539.136f)
      curveTo(946.176f, 593.92f, 844.288f, 666.112f, 655.36f, 692.224f)
      curveTo(635.392f, 694.784f, 621.568f, 713.216f, 624.128f, 733.184f)
      curveTo(626.688f, 751.616f, 642.048f, 764.416f, 659.968f, 764.416f)
      curveTo(661.504f, 764.416f, 663.552f, 764.416f, 665.088f, 763.904f)
      curveTo(880.128f, 734.208f, 1018.88f, 645.632f, 1018.88f, 538.624f)
      curveTo(1018.88f, 498.688f, 999.424f, 440.32f, 906.752f, 387.584f)
      close()
      // "360" text
      moveTo(318.976f, 505.344f)
      curveTo(293.888f, 504.32f, 280.064f, 488.96f, 276.992f, 458.24f)
      lineTo(238.592f, 468.48f)
      curveTo(246.784f, 516.096f, 274.432f, 540.672f, 321.536f, 541.696f)
      curveTo(373.76f, 539.136f, 401.408f, 511.488f, 404.48f, 459.776f)
      curveTo(403.968f, 430.08f, 388.096f, 408.576f, 357.888f, 394.752f)
      curveTo(382.976f, 383.488f, 395.264f, 364.544f, 395.264f, 338.432f)
      curveTo(393.728f, 288.768f, 368.64f, 263.68f, 320.512f, 262.656f)
      curveTo(279.552f, 263.68f, 253.952f, 287.232f, 243.2f, 334.336f)
      lineTo(281.6f, 344.576f)
      curveTo(286.208f, 317.44f, 299.52f, 303.104f, 321.536f, 301.568f)
      curveTo(343.552f, 302.592f, 354.816f, 314.368f, 355.84f, 336.896f)
      curveTo(354.304f, 364.544f, 338.944f, 378.368f, 310.272f, 378.368f)
      lineTo(290.816f, 378.368f)
      lineTo(290.816f, 416.256f)
      lineTo(318.976f, 416.256f)
      curveTo(347.136f, 417.28f, 361.984f, 430.592f, 364.544f, 456.704f)
      curveTo(365.056f, 490.496f, 349.696f, 506.368f, 318.976f, 505.344f)
      close()
      moveTo(518.656f, 356.864f)
      curveTo(496.128f, 356.864f, 478.72f, 366.592f, 466.432f, 385.536f)
      curveTo(465.92f, 329.216f, 482.304f, 301.056f, 516.608f, 302.08f)
      curveTo(535.552f, 301.056f, 547.84f, 313.344f, 553.984f, 338.432f)
      lineTo(590.336f, 326.656f)
      curveTo(579.072f, 285.696f, 554.496f, 264.704f, 517.632f, 262.656f)
      curveTo(457.728f, 262.656f, 428.032f, 311.808f, 428.032f, 410.112f)
      curveTo(427.52f, 499.712f, 456.192f, 543.744f, 515.584f, 541.696f)
      curveTo(564.736f, 538.112f, 590.848f, 506.88f, 593.92f, 448f)
      curveTo(591.36f, 389.632f, 566.272f, 359.424f, 518.656f, 356.864f)
      close()
      moveTo(515.584f, 506.88f)
      curveTo(483.84f, 505.856f, 467.456f, 485.376f, 466.944f, 444.416f)
      curveTo(467.456f, 412.16f, 483.84f, 395.776f, 515.584f, 394.752f)
      curveTo(541.184f, 395.776f, 554.496f, 413.696f, 555.52f, 449.536f)
      curveTo(554.496f, 486.912f, 541.184f, 505.856f, 515.584f, 506.88f)
      close()
      moveTo(705.024f, 542.208f)
      curveTo(758.784f, 540.672f, 785.92f, 494.592f, 786.944f, 403.968f)
      curveTo(786.944f, 310.272f, 759.296f, 263.168f, 704f, 263.168f)
      curveTo(650.752f, 264.704f, 623.616f, 311.808f, 622.08f, 403.968f)
      curveTo(622.08f, 496.128f, 649.728f, 542.208f, 705.024f, 542.208f)
      close()
      moveTo(704f, 300.544f)
      curveTo(733.696f, 297.984f, 748.032f, 332.288f, 747.008f, 403.456f)
      curveTo(747.52f, 474.112f, 733.696f, 508.416f, 705.024f, 506.368f)
      curveTo(675.328f, 508.928f, 660.992f, 474.624f, 662.016f, 403.456f)
      curveTo(662.528f, 336.896f, 676.864f, 302.592f, 704f, 300.544f)
      close()
      // Degree symbol circle
      moveTo(821.248f, 257.536f)
      curveTo(861.696f, 257.536f, 893.952f, 224.768f, 893.952f, 184.832f)
      curveTo(893.952f, 144.896f, 861.184f, 112.128f, 821.248f, 112.128f)
      curveTo(780.8f, 112.128f, 748.544f, 144.896f, 748.544f, 184.832f)
      curveTo(748.544f, 224.768f, 781.312f, 257.536f, 821.248f, 257.536f)
      close()
      moveTo(821.248f, 157.184f)
      curveTo(836.608f, 157.184f, 848.896f, 169.472f, 848.896f, 184.832f)
      curveTo(848.896f, 200.192f, 836.608f, 212.48f, 821.248f, 212.48f)
      curveTo(805.888f, 212.48f, 793.6f, 199.68f, 793.6f, 184.832f)
      curveTo(793.6f, 169.472f, 806.4f, 157.184f, 821.248f, 157.184f)
      close()
      // Bottom arrow
      moveTo(578.048f, 734.72f)
      curveTo(578.048f, 733.696f, 578.048f, 732.16f, 577.536f, 731.136f)
      curveTo(577.536f, 730.112f, 577.024f, 728.576f, 576.512f, 727.552f)
      curveTo(576f, 726.528f, 576f, 725.504f, 575.488f, 724.48f)
      curveTo(574.976f, 722.944f, 573.952f, 721.408f, 573.44f, 720.384f)
      curveTo(572.928f, 719.872f, 572.928f, 719.36f, 572.416f, 718.336f)
      curveTo(571.392f, 716.8f, 570.368f, 715.776f, 569.344f, 714.24f)
      curveTo(568.832f, 713.728f, 568.32f, 713.216f, 567.808f, 712.192f)
      lineTo(567.296f, 711.68f)
      curveTo(566.272f, 710.656f, 564.736f, 709.632f, 563.712f, 708.608f)
      curveTo(563.2f, 708.096f, 562.688f, 708.096f, 562.176f, 707.584f)
      curveTo(561.664f, 707.072f, 561.664f, 707.072f, 561.152f, 707.072f)
      lineTo(418.304f, 613.888f)
      curveTo(401.408f, 603.136f, 378.88f, 607.744f, 368.128f, 624.64f)
      curveTo(357.376f, 641.536f, 361.984f, 664.064f, 378.88f, 674.816f)
      lineTo(412.16f, 696.32f)
      curveTo(217.088f, 676.864f, 81.408f, 604.672f, 81.408f, 538.624f)
      curveTo(81.408f, 509.44f, 108.032f, 478.208f, 156.672f, 450.56f)
      curveTo(174.08f, 440.832f, 180.224f, 418.304f, 170.496f, 400.896f)
      curveTo(160.768f, 383.488f, 138.24f, 377.344f, 120.832f, 387.072f)
      curveTo(27.648f, 440.32f, 8.192f, 498.688f, 8.192f, 539.136f)
      curveTo(8.192f, 658.944f, 181.76f, 752.64f, 424.448f, 771.072f)
      lineTo(378.368f, 801.28f)
      curveTo(361.472f, 812.032f, 356.864f, 834.56f, 367.616f, 851.456f)
      curveTo(374.784f, 862.208f, 386.048f, 867.84f, 398.336f, 867.84f)
      curveTo(404.992f, 867.84f, 412.16f, 865.792f, 418.304f, 861.696f)
      lineTo(562.176f, 768f)
      lineTo(562.688f, 768f)
      curveTo(564.224f, 767f, 566.272f, 765.44f, 567.808f, 763.904f)
      lineTo(568.832f, 762.88f)
      curveTo(569.856f, 761.856f, 571.392f, 760.32f, 572.416f, 758.784f)
      curveTo(572.416f, 758.272f, 572.928f, 758.272f, 572.928f, 757.76f)
      curveTo(573.44f, 757.248f, 573.44f, 756.736f, 573.952f, 755.712f)
      curveTo(574.976f, 754.176f, 575.488f, 753.152f, 576f, 751.616f)
      curveTo(576.512f, 750.592f, 576.512f, 749.568f, 577.024f, 748.544f)
      curveTo(577.536f, 747.52f, 577.536f, 746.496f, 578.048f, 744.96f)
      curveTo(578.048f, 743.936f, 578.56f, 742.4f, 578.56f, 741.376f)
      curveTo(578.56f, 740.352f, 579.072f, 739.328f, 579.072f, 737.792f)
      curveTo(578.56f, 736.768f, 578.56f, 735.744f, 578.048f, 734.72f)
      close()
    }
  }.build()
}

private val MoreVertIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "MoreVert", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
  ).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black)) {
      moveTo(12f, 8f)
      curveTo(13.1f, 8f, 14f, 7.1f, 14f, 6f)
      curveTo(14f, 4.9f, 13.1f, 4f, 12f, 4f)
      curveTo(10.9f, 4f, 10f, 4.9f, 10f, 6f)
      curveTo(10f, 7.1f, 10.9f, 8f, 12f, 8f)
      close()
      moveTo(12f, 10f)
      curveTo(10.9f, 10f, 10f, 10.9f, 10f, 12f)
      curveTo(10f, 13.1f, 10.9f, 14f, 12f, 14f)
      curveTo(13.1f, 14f, 14f, 13.1f, 14f, 12f)
      curveTo(14f, 10.9f, 13.1f, 10f, 12f, 10f)
      close()
      moveTo(12f, 16f)
      curveTo(10.9f, 16f, 10f, 16.9f, 10f, 18f)
      curveTo(10f, 19.1f, 10.9f, 20f, 12f, 20f)
      curveTo(13.1f, 20f, 14f, 19.1f, 14f, 18f)
      curveTo(14f, 16.9f, 13.1f, 16f, 12f, 16f)
      close()
    }
  }.build()
}

private val PanoramaIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "Panorama", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
  ).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black)) {
      moveTo(21f, 4f)
      curveTo(21f, 2.9f, 20.1f, 2f, 19f, 2f)
      lineTo(5f, 2f)
      curveTo(3.9f, 2f, 3f, 2.9f, 3f, 4f)
      lineTo(3f, 20f)
      curveTo(3f, 21.1f, 3.9f, 22f, 5f, 22f)
      lineTo(19f, 22f)
      curveTo(20.1f, 22f, 21f, 21.1f, 21f, 20f)
      lineTo(21f, 4f)
      close()
      moveTo(5f, 4f)
      lineTo(8f, 4f)
      lineTo(8f, 12f)
      lineTo(5f, 12f)
      lineTo(5f, 4f)
      close()
      moveTo(10f, 4f)
      lineTo(14f, 4f)
      lineTo(14f, 12f)
      lineTo(10f, 12f)
      lineTo(10f, 4f)
      close()
      moveTo(16f, 4f)
      lineTo(19f, 4f)
      lineTo(19f, 12f)
      lineTo(16f, 12f)
      lineTo(16f, 4f)
      close()
      moveTo(5f, 14f)
      lineTo(19f, 14f)
      lineTo(19f, 20f)
      lineTo(5f, 20f)
      lineTo(5f, 14f)
      close()
    }
  }.build()
}

private val TrackIcon: ImageVector by lazy {
  ImageVector.Builder(
    name = "Track", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 1024f, viewportHeight = 1024f
  ).apply {
    path(fill = androidx.compose.ui.graphics.SolidColor(Color(0xFF272636))) {
      moveTo(605.848f, 958.709f)
      curveTo(605.486f, 958.709f, 605.124f, 958.701f, 604.76f, 958.682f)
      curveTo(595.661f, 958.214f, 587.887f, 951.97f, 585.471f, 943.186f)
      lineTo(476.899f, 548.458f)
      lineTo(80.071f, 437.835f)
      curveTo(71.31f, 435.392f, 65.095f, 427.616f, 64.643f, 418.53f)
      curveTo(64.192f, 409.446f, 69.608f, 401.093f, 78.085f, 397.796f)
      lineTo(929.251f, 66.729f)
      curveTo(937.06f, 63.691f, 945.921f, 65.554f, 951.849f, 71.479f)
      curveTo(957.772f, 77.403f, 959.637f, 86.269f, 956.6f, 94.077f)
      lineTo(625.532f, 945.241f)
      curveTo(622.362f, 953.391f, 614.519f, 958.709f, 605.848f, 958.709f)
      close()
      moveTo(152.537f, 414.173f)
      lineTo(499.769f, 510.97f)
      curveTo(506.918f, 512.963f, 512.496f, 518.562f, 514.466f, 525.716f)
      lineTo(609.288f, 870.447f)
      lineTo(899.813f, 123.516f)
      lineTo(152.537f, 414.173f)
      close()
    }
  }.build()
}



