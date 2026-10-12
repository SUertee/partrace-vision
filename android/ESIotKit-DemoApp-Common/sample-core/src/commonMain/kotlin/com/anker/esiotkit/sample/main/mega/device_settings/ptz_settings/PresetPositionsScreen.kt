package com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings


import kotlin.time.Clock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.media.live_stream.LiveMediaPlayer
import com.anker.esiotkit.sample.main.mega.device_settings.PTZSettingsViewModel
import com.anker.esiotkit.sample.main.mega.live_video.MegaLiveStreamAction
import com.anker.esiotkit.sample.main.mega.live_video.MegaLiveStreamViewModel
import com.anker.esiotkit.sample.main.mega.live_video.PTZDirection
import com.anker.esiotkit.sample.support.model.MegaPlayType
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.LiveStreamPlayView
import com.anker.esiotkit.support.platform.IotImage
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import kotlin.jvm.Transient
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_ptz_preset_positions_title

/**
 * Preset position data class
 */
@Serializable
data class PresetPosition(
  val id: String,
  val name: String,
  val isSet: Boolean = false
)

// Primary theme color
private val PrimaryColor = Color(0xFF005D8E)

/**
 * Preset Positions Screen
 * Allows users to adjust preset position using pan, tilt, and zoom.
 * Choose a default position for the camera to return to after automatic pan and tilt rotation.
 */
@OptIn(ExperimentalMultiplatform::class)
class PresetPositionsScreen(
  @kotlinx.serialization.Transient private val viewModel: PTZSettingsViewModel,
  @kotlinx.serialization.Transient private val megaDevice: MegaDevice
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_ptz_preset_positions_title

  @Composable
  override fun Content() = BaseColumn {
    val deviceManager = koinInject<ESIotDeviceManager>()
    val liveStreamViewModel = rememberScreenModel { MegaLiveStreamViewModel(megaDevice, deviceManager) }
    val deviceSn = megaDevice.deviceSn
    val coroutineScope = rememberCoroutineScope()

    // State for thumbnails - trigger recomposition when thumbnails change
    var thumbnailVersion by remember { mutableIntStateOf(0) }

    // preset.id 存储设备 0-based 索引（0,1,2,3），与协议一致
    // 先用本地缓存渲染
    var presetsList by remember {
      mutableStateOf(
        (0..3).map { i ->
          PresetPosition(i.toString(), if (i == 0) "1-Default" else "${i + 1}",
            isSet = PresetPositionManager.isPresetSet(deviceSn, i))
        }
      )
    }

    // 进入页面查询一次设备
    LaunchedEffect(deviceSn) {
      PresetPositionManager.initDevice(deviceSn)
      viewModel.queryPresetPositions { setPresets ->
        // setPresets 为 0-based 索引列表
        presetsList = (0..3).map { i ->
          PresetPosition(i.toString(), if (i == 0) "1-Default" else "${i + 1}",
            isSet = setPresets.contains(i))
        }
      }
    }

    // 监听其他界面变更，直接从 cache 同步（不重查设备）
    LaunchedEffect(deviceSn) {
      PresetPositionManager.presetChanges.collect { changedDeviceSn ->
        if (changedDeviceSn == deviceSn) {
          presetsList = (0..3).map { i ->
            PresetPosition(i.toString(), if (i == 0) "1-Default" else "${i + 1}",
              isSet = PresetPositionManager.isPresetSet(deviceSn, i))
          }
          thumbnailVersion++
        }
      }
    }

    // Load selected and default index from manager
    var selectedPresetIndex by remember { mutableIntStateOf(PresetPositionManager.getSelectedIndex(deviceSn)) }
    var defaultPresetIndex by remember { mutableIntStateOf(PresetPositionManager.getDefaultIndex(deviceSn)) }
    var currentZoom by remember { mutableIntStateOf(1) }

    // Track which preset is waiting for screenshot
    var pendingScreenshotPresetIndex by remember { mutableStateOf<Int?>(null) }

    // Start live stream when screen loads
    DisposableEffect(Unit) {
      liveStreamViewModel.action(MegaLiveStreamAction.Play(MegaPlayType.Normal))

      // Set up screenshot callback（presetIndex 为 0-based，cache key 与设备协议一致）
      liveStreamViewModel.onCutImageCallback = { image ->
        pendingScreenshotPresetIndex?.let { presetIndex ->
          PresetPositionManager.setThumbnail(deviceSn, presetIndex, image)
          thumbnailVersion++
          pendingScreenshotPresetIndex = null
        }
      }

      onDispose {
        // Save selected index to manager
        PresetPositionManager.setSelectedIndex(deviceSn, selectedPresetIndex)
        PresetPositionManager.setDefaultIndex(deviceSn, defaultPresetIndex)
        // Clean up callback
        liveStreamViewModel.onCutImageCallback = null
        liveStreamViewModel.action(MegaLiveStreamAction.Exit)
      }
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF1A1A1A))
    ) {
      // Instruction text
      Text(
        text = buildAnnotatedString {
          append("After adjusting the preset position using pan, tilt, and zoom. Choose a default position for the camera to return to after each automatic pan and tilt rotation. ")
          withStyle(
            style = SpanStyle(
              color = PrimaryColor,
              textDecoration = TextDecoration.Underline
            )
          ) {
            append("Learn more")
          }
        },
        color = Color.White,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
      )

      // Live camera preview
      LivePreviewSection(
        liveMediaPlayer = liveStreamViewModel.liveMediaPlayer,
        currentZoom = currentZoom,
        onZoomChanged = { zoom ->
          currentZoom = zoom
          // TODO: Apply zoom to camera
        },
        onViewCreated = {
          // Camera view initialized
        }
      )

      // Preset position slots
      PresetSlotsRow(
        presets = presetsList,
        selectedIndex = selectedPresetIndex,
        defaultIndex = defaultPresetIndex,
        deviceSn = deviceSn,
        thumbnailVersion = thumbnailVersion,
        onPresetSelected = { index ->
          // index 是 presetsList 的位置下标，同时也是设备 0-based 索引
          selectedPresetIndex = index
          PresetPositionManager.setSelectedIndex(deviceSn, index)
          val preset = presetsList[index]
          if (preset.isSet) {
            // preset.id 即 0-based 索引，直接传给设备
            viewModel.gotoPresetWithCallback(preset.id) {}
          }
        },
        onPresetDeleted = { index ->
          val preset = presetsList[index]
          // preset.id 是 0-based，直接传给设备
          viewModel.deletePresetWithCallback(preset.id) {
            presetsList = presetsList.mapIndexed { i, p ->
              if (i == index) p.copy(isSet = false) else p
            }
            coroutineScope.launch {
              PresetPositionManager.setPresetStatusAndNotify(deviceSn, index, false)
              PresetPositionManager.removeThumbnail(deviceSn, index)
              thumbnailVersion++
            }
          }
        }
      )

      Spacer(modifier = Modifier.height(24.dp))

      // PTZ Control Pad
      PTZControlPad(
        onDirectionControl = { direction ->
          // 单击：cmdType=1 单次转动
          liveStreamViewModel.controlPTZ(direction.name)
        },
        onLongPressStart = { direction ->
          // 长按开始：cmdType=2
          liveStreamViewModel.startPTZMovement(direction.name)
        },
        onLongPressStop = { direction ->
          // 长按结束：cmdType=3
          liveStreamViewModel.stopPTZMovement(direction.name)
        },
        onSetClicked = {
          // selectedPresetIndex 是 0-based，直接传给设备
          pendingScreenshotPresetIndex = selectedPresetIndex
          viewModel.savePresetWithCallback(selectedPresetIndex.toString()) {
            presetsList = presetsList.mapIndexed { i, p ->
              if (i == selectedPresetIndex) p.copy(isSet = true) else p
            }
            coroutineScope.launch {
              PresetPositionManager.setPresetStatusAndNotify(deviceSn, selectedPresetIndex, true)
              // 预置位抓图：仅沙盒封面+内存回调，不进相册（双业务分流）
              liveStreamViewModel.captureForPreset()
            }
          }
        },
        modifier = Modifier.align(Alignment.CenterHorizontally)
      )

      Spacer(modifier = Modifier.height(16.dp))

      // Set as default position link
      Text(
        text = "Set as default position",
        color = PrimaryColor,
        fontSize = 16.sp,
        modifier = Modifier
          .align(Alignment.CenterHorizontally)
          .clickable {
            defaultPresetIndex = selectedPresetIndex
            PresetPositionManager.setDefaultIndex(deviceSn, selectedPresetIndex)
          }
          .padding(16.dp)
      )

      Spacer(modifier = Modifier.weight(1f))
    }

    MegaLoading(viewModel.loading)
  }

  @Composable
  private fun LivePreviewSection(
    liveMediaPlayer: LiveMediaPlayer,
    currentZoom: Int,
    onZoomChanged: (Int) -> Unit,
    onViewCreated: () -> Unit
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .background(Color.Black)
    ) {
      // Live stream view
      LiveStreamPlayView(
        modifier = Modifier.fillMaxSize(),
        liveMediaPlayer = liveMediaPlayer,
        onViewCreated = onViewCreated
      )

      // Zoom controls overlay at bottom center
      Row(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        ZoomButton(
          text = "1x",
          isSelected = currentZoom == 1,
          onClick = { onZoomChanged(1) }
        )
        ZoomButton(
          text = "3x",
          isSelected = currentZoom == 3,
          onClick = { onZoomChanged(3) }
        )
      }
    }
  }

  @Composable
  private fun ZoomButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
  ) {
    Box(
      modifier = Modifier
        .size(40.dp)
        .background(
          color = if (isSelected) Color.White else Color.Black.copy(alpha = 0.6f),
          shape = CircleShape
        )
        .clickable(onClick = onClick),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = text,
        color = if (isSelected) Color.Black else Color.White,
        fontSize = 14.sp
      )
    }
  }

  @Composable
  private fun PresetSlotsRow(
    presets: List<PresetPosition>,
    selectedIndex: Int,
    defaultIndex: Int,
    deviceSn: String,
    thumbnailVersion: Int,
    onPresetSelected: (Int) -> Unit,
    onPresetDeleted: (Int) -> Unit
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      presets.forEachIndexed { index, preset ->
        // preset.id 即 0-based 索引，thumbnail cache key 与设备协议一致
        val thumbnail = PresetPositionManager.getThumbnail(deviceSn, preset.id.toIntOrNull() ?: index)
        PresetSlot(
          preset = preset,
          isSelected = index == selectedIndex,
          isDefault = index == defaultIndex,
          thumbnail = thumbnail,
          onSelected = { onPresetSelected(index) },
          onDeleted = { onPresetDeleted(index) }
        )
      }
    }
  }

  @Composable
  private fun PresetSlot(
    preset: PresetPosition,
    isSelected: Boolean,
    isDefault: Boolean,
    thumbnail: IotImage?,
    onSelected: () -> Unit,
    onDeleted: () -> Unit
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Box(
        modifier = Modifier
          .size(width = 100.dp, height = 70.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFF2A2A2A))
          .border(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) PrimaryColor else Color(0xFF3A3A3A),
            shape = RoundedCornerShape(8.dp)
          )
          .clickable(onClick = onSelected),
        contentAlignment = Alignment.Center
      ) {
        if (preset.isSet) {
          // Show preset with thumbnail or placeholder
          Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnail != null) {
              // Display the thumbnail image
              PlatformIotImageView(
                image = thumbnail,
                modifier = Modifier.fillMaxSize()
              )
            } else {
              // Placeholder background indicating preset is set
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .background(Color(0xFF3A3A3A)),
                contentAlignment = Alignment.Center
              ) {
                // Show a visual indicator that preset is set
                Text(
                  text = "P${preset.id}",
                  color = Color.White.copy(alpha = 0.7f),
                  fontSize = 20.sp
                )
              }
            }
            // Close button to delete preset
            Box(
              modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                .clickable(onClick = onDeleted),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove preset",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
              )
            }
          }
        } else {
          // Show add icon for empty slot
          Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Add preset",
            tint = PrimaryColor,
            modifier = Modifier.size(32.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Label：name 字段已包含正确的显示文字（"1-Default", "2", "3", "4"）
      Text(
        text = preset.name,
        color = Color.White,
        fontSize = 12.sp
      )
    }
  }

  @Composable
  private fun PTZControlPad(
    onDirectionControl: (PTZDirection) -> Unit,    // 单击：cmdType=1
    onLongPressStart: ((PTZDirection) -> Unit)? = null, // 长按开始：cmdType=2
    onLongPressStop: ((PTZDirection) -> Unit)? = null,  // 长按结束：cmdType=3
    onSetClicked: () -> Unit,
    modifier: Modifier = Modifier
  ) {
    Box(
      modifier = modifier.size(200.dp),
      contentAlignment = Alignment.Center
    ) {
      // Outer circle (ring)
      Box(
        modifier = Modifier
          .fillMaxSize()
          .border(
            width = 3.dp,
            color = Color(0xFF3A3A3A),
            shape = CircleShape
          )
      )

      // Up button
      DirectionButton(
        direction = PTZDirection.UP,
        onTap = onDirectionControl,
        onLongPressStart = onLongPressStart,
        onLongPressStop = onLongPressStop,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
      ) {
        Icon(
          imageVector = Icons.Default.KeyboardArrowUp,
          contentDescription = "Up",
          tint = Color.White,
          modifier = Modifier.size(32.dp)
        )
      }

      // Down button
      DirectionButton(
        direction = PTZDirection.DOWN,
        onTap = onDirectionControl,
        onLongPressStart = onLongPressStart,
        onLongPressStop = onLongPressStop,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
      ) {
        Icon(
          imageVector = Icons.Default.KeyboardArrowDown,
          contentDescription = "Down",
          tint = Color.White,
          modifier = Modifier.size(32.dp)
        )
      }

      // Left button
      DirectionButton(
        direction = PTZDirection.LEFT,
        onTap = onDirectionControl,
        onLongPressStart = onLongPressStart,
        onLongPressStop = onLongPressStop,
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
      ) {
        Icon(
          imageVector = Icons.Default.KeyboardArrowLeft,
          contentDescription = "Left",
          tint = Color.White,
          modifier = Modifier.size(32.dp)
        )
      }

      // Right button
      DirectionButton(
        direction = PTZDirection.RIGHT,
        onTap = onDirectionControl,
        onLongPressStart = onLongPressStart,
        onLongPressStop = onLongPressStop,
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
      ) {
        Icon(
          imageVector = Icons.Default.KeyboardArrowRight,
          contentDescription = "Right",
          tint = Color.White,
          modifier = Modifier.size(32.dp)
        )
      }

      // Center Set button
      Box(
        modifier = Modifier
          .size(80.dp)
          .border(
            width = 2.dp,
            color = PrimaryColor,
            shape = CircleShape
          )
          .clickable(onClick = onSetClicked),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = "Set",
          color = Color.White,
          fontSize = 18.sp
        )
      }
    }
  }

  @Composable
  private fun DirectionButton(
    direction: PTZDirection,
    onTap: (PTZDirection) -> Unit,
    onLongPressStart: ((PTZDirection) -> Unit)? = null,
    onLongPressStop: ((PTZDirection) -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
  ) {
    Box(
      modifier = modifier
        .size(44.dp)
        .pointerInput(direction) {
          detectTapGestures(
            onPress = { _ ->
              val longPressThreshold = viewConfiguration.longPressTimeoutMillis
              val startTime = Clock.System.now().toEpochMilliseconds()
              tryAwaitRelease()
              val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
              if (elapsed < longPressThreshold) {
                // 单击：cmdType=1
                onTap(direction)
              } else {
                // 长按结束：cmdType=3（开始由 onLongPress 已发）
                onLongPressStop?.invoke(direction)
              }
            },
            onLongPress = {
              // 长按开始：cmdType=2
              onLongPressStart?.invoke(direction)
            }
          )
        },
      contentAlignment = Alignment.Center
    ) {
      content()
    }
  }
}

/**
 * Platform-specific composable to display IotImage
 * Implemented using expect/actual pattern
 */
@Composable
expect fun PlatformIotImageView(image: IotImage, modifier: Modifier)
