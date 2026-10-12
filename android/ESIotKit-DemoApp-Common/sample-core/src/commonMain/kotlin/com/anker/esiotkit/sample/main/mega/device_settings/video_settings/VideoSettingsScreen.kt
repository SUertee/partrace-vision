package com.anker.esiotkit.sample.main.mega.device_settings.video_settings

import kotlinx.serialization.Transient

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.compat.ESIotCompatSwitch
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.net_api.response.thing.TDDataType
import com.anker.esiotkit.sample.main.mega.device_settings.VideoSettingsViewModel
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.support.rememberIntProperty
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.component.ConfirmDialog
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.composable.state
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRow
import com.anker.esiotkit.sample.view.CommonSwitchRow
import com.anker.esiotkit.sample.view.toastNotSupport
import com.anker.esiotkit.support.ktx.toBoolean
import com.anker.esiotkit.support.log.appLogger
import com.composeunstyled.Slider
import com.composeunstyled.rememberSliderState
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_video_title

/**
 * @version 2024/10/25
 */

class VideoSettingsScreen(@Transient private val viewModel: VideoSettingsViewModel) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_video_title

  @Composable
  override fun Content() = BaseColumn(paddingHorizontal = 0.dp) {
    val navigator = LocalNavigator.currentOrThrow
    val megaDevice = remember { viewModel.megaDevice }
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      PropertyThingContainer(
        megaDevice = viewModel.megaDevice,
        identifier = ThingIdentifier.WATERMARK_SET
      ) {
        val isNewApi = ESIotCompatSwitch.isNewApiCompatEnabled()
        val watermarkProperty = remember { viewModel.megaDevice.getProperty(ThingIdentifier.WATERMARK_SET) }
        val watermarkType = watermarkProperty?.td?.dataType?.type
        when {
          watermarkType == TDDataType.TYPE_BOOL -> {
            // Bool 设备（新/旧 API 均适用）：显示 Switch 开关
            appLogger.i("VideoSettings/Watermark: route=BOOL_SWITCH isNewApi=$isNewApi watermarkType=$watermarkType")
            CommonSwitchRow(
              title = "Watermark",
              state = viewModel.watermarkState == 1,
              onClick = {
                viewModel.setWatermarkState(ThingPayload.fromBoolean(it))
              }
            )
          }
          !isNewApi -> {
            // 旧 API + 枚举设备：仅支持 On/Off，ON 下发最大枚举值
            val maxEnumValue = remember {
              watermarkProperty?.td?.dataType?.specs?.enumList
                ?.mapNotNull { it.associatedValue?.toIntOrNull() ?: it.value.toIntOrNull() }
                ?.maxOrNull() ?: 1
            }
            appLogger.i("VideoSettings/Watermark: route=OLD_API_ENUM_SWITCH isNewApi=$isNewApi watermarkType=$watermarkType maxEnumValue=$maxEnumValue currentState=${viewModel.watermarkState}")
            CommonSwitchRow(
              title = "Watermark",
              state = viewModel.watermarkState > 0,
              onClick = { enabled ->
                viewModel.setWatermark(if (enabled) maxEnumValue else 0)
              }
            )
          }
          else -> {
            // 新 API + 枚举设备：显示当前选中项名称，点击进入选项列表
            appLogger.i("VideoSettings/Watermark: route=NEW_API_ENUM_PAGE isNewApi=$isNewApi watermarkType=$watermarkType currentState=${viewModel.watermarkState}")
            val watermarkLabel = remember(viewModel.watermarkState) {
              val enumList = watermarkProperty?.td?.dataType?.specs?.enumList
              if (!enumList.isNullOrEmpty()) {
                enumList.firstOrNull { it.value.toIntOrNull() == viewModel.watermarkState }?.desc
              } else {
                when (viewModel.watermarkState) {
                  0 -> "Off"
                  1 -> "Timestamp"
                  2 -> "Timestamp and logo"
                  3 -> "Logo"
                  else -> null
                }
              }
            }
            CommonRow(
              title = "Watermark",
              onClick = {
                navigator.push(
                  WatermarkScreen(viewModel = viewModel)
                )
              },
              rightContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  if (watermarkLabel != null) {
                    Text(
                      text = watermarkLabel,
                      color = MaterialTheme.colorScheme.primary,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.widthIn(max = 200.dp)
                    )
                  }
                  Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
              }
            )
          }
        }
      }

      // Streaming Quality - 实时流分辨率设置
      PropertyThingContainer(
        megaDevice = viewModel.megaDevice,
        identifier = ThingIdentifier.LIVE_STREAM_RESOLUTION
      ) {
        // 使用 ViewModel 的属性确保状态同步
        StreamingQualitySettings(state = viewModel.streamingQualityState, onClick = {
          navigator.push(
            StreamingQualityScreen(viewModel = viewModel)
          )
        })
      }

      // Recording Quality - 录像分辨率设置
      PropertyThingContainer(
        megaDevice = viewModel.megaDevice,
        identifier = ThingIdentifier.RECORD_RESOLUTION
      ) {
        // 使用 ViewModel 的属性确保状态同步
        RecordingQualitySettings(state = viewModel.recordingQualityState, onClick = {
          navigator.push(
            RecordingQualityScreen(viewModel = viewModel)
          )
        })
      }

      PropertyThingContainer(
        megaDevice = viewModel.megaDevice,
        identifier = ThingIdentifier.VIDEO_QUALITY_HDR_SWITCH
      ) {
        CommonSwitchRow(
          title = "HDR Enabled",
          state = viewModel.hdrState,
          onClick = {
            viewModel.setHDRState(it)
          }
        )
      }

      PropertyThingContainer(
        megaDevice = viewModel.megaDevice,
        identifier = ThingIdentifier.VIDEO_QUALITY_HDR_SWITCH
      ) {
        StreamingDisplaySettings(
          state = viewModel.streamingDisplayState,
          onClick = {
            navigator.push(
              StreamingDisplayScreen(viewModel = viewModel)
            )
          }
        )
      }

      // Night Vision Settings - 切换夜视模式（优先使用 nightvision_type_new）
      PropertyThingContainer(
        megaDevice = viewModel.megaDevice,
        identifier = viewModel.nightVisionIdentifier
      ) {
        val modeName = remember(viewModel.nightVisionMode) {
          mapNightVisionName(viewModel.nightVisionMode)
        }
        NightVisionSettings(
          modeName = modeName,
          onClick = {
            navigator.push(NightVisionScreen(viewModel = viewModel))
          })
      }

      // Privacy Mode Toggle
      val privacyModeVisible = remember {
        megaDevice.hasProperty("privacy_mode_enable")
      }
      appLogger.i("VideoSettings/visibility: privacyModeVisible=$privacyModeVisible, deviceSn=${megaDevice.deviceSn}")
      if (privacyModeVisible) {
        // 使用 ViewModel 的属性确保状态同步
        var showDisableDialog by remember { mutableStateOf(false) }

        if (showDisableDialog) {
          ConfirmDialog(
            title = "Disable Privacy Mode?",
            description = "This will disable privacy zones and related features.",
            onConfirm = {
              viewModel.setPrivacyModeEnable(false)
              showDisableDialog = false
            },
            onCancel = {
              showDisableDialog = false
            }
          )
        }

        CommonRow(
          title = "Privacy Mode",
          description = "Enable all privacy features",
          rightContent = {
            Switch(
              checked = viewModel.privacyModeEnable == 1,
              onCheckedChange = {
                if (it) {
                  viewModel.setPrivacyModeEnable(true)
                } else {
                  showDisableDialog = true
                }
              })
          }
        )
      }

      val privacyZonesVisible = remember { viewModel.isPrivacyZonesSupported }
      appLogger.i("VideoSettings/visibility: privacyZonesVisible=$privacyZonesVisible, source=isSupported, deviceSn=${megaDevice.deviceSn}")
      if (privacyZonesVisible) {
        CommonRow(
          title = "Privacy Zones",
          onClick = { navigator.push(PrivacyZonesConfigScreen(viewModel = viewModel)) }
        )
      }

      // Continuous Recording
      PropertyThingContainer(
        megaDevice = viewModel.megaDevice,
        identifier = "continuous_recording"
      ) {
        val continuousRecording by megaDevice.rememberIntProperty("continuous_recording")
        CommonSwitchRow(
          title = "Continuous Recording",
          state = continuousRecording == 1,
          onClick = { enabled ->
            viewModel.setContinuousRecording(enabled)
          }
        )
      }

      // Rotate Image 180° - 画面旋转180度，用于倒装场景
      if (viewModel.isRotateImageSupported) {
        CommonSwitchRow(
          title = "Rotate Image 180°",
          description = "Rotate the image 180 degrees for upside-down mounting",
          state = viewModel.rotateImageState == 1,
          onClick = { enabled ->
            viewModel.setRotateImage180(enabled)
          }
        )
      }

    }
    MegaLoading(viewModel.loading)
  }

  @Composable
  fun StreamingQualitySettings(state: String, onClick: () -> Unit) {
    // 从物模型获取当前选中项的描述
    val property = remember { viewModel.megaDevice.getProperty(ThingIdentifier.LIVE_STREAM_RESOLUTION) }
    val enumList = remember { property?.td?.dataType?.specs?.enumList ?: emptyList() }

    val streamingQualityTitle = if (enumList.isNotEmpty()) {
      // 从物模型 enumList 获取描述
      enumList.firstOrNull { it.value == state }?.desc ?: "Unknown ($state)"
    } else {
      // 兜底显示
      when (state) {
        "0" -> "Auto"
        "1" -> "Low (720P)"
        "2" -> "Medium (1080P)"
        "3" -> "High (2K)"
        "4" -> "Max (4K)"
        else -> "Unknown ($state)"
      }
    }
    CommonRow(
      title = "Streaming Quality",
      description = "The image quality of live streaming.",
      onClick = onClick,
      rightContent = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(text = streamingQualityTitle, color = MaterialTheme.colorScheme.primary)
          Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
      }
    )
  }

  @Composable
  fun RecordingQualitySettings(state: String, onClick: () -> Unit) {
    appLogger.i("RecordingQualitySettings: state='$state'")

    // 从物模型获取当前选中项的描述
    val property = remember { viewModel.megaDevice.getProperty(ThingIdentifier.RECORD_RESOLUTION) }
    val enumList = remember { property?.td?.dataType?.specs?.enumList ?: emptyList() }

    val recordingQualityTitle = if (enumList.isNotEmpty()) {
      // 从物模型 enumList 获取描述
      enumList.firstOrNull { it.value == state }?.desc ?: if (state.isEmpty()) "Not Set" else "Unknown ($state)"
    } else {
      // 兜底显示
      when (state) {
        "0" -> "Auto"
        "1" -> "HD (720P)"
        "2" -> "Full HD (1080P)"
        "3" -> "2K"
        "5" -> "2K HD"
        "" -> "Not Set"
        else -> "Unknown ($state)"
      }
    }
    CommonRow(
      title = "Recording Quality",
      description = "The image quality and encoding format of video clips.",
      onClick = onClick,
      rightContent = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(text = recordingQualityTitle, color = MaterialTheme.colorScheme.primary)
          Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
      }
    )
  }

  @Composable
  fun NightVisionSettings(state: Boolean, stateChange: (Boolean) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    if (showDialog) {
      ConfirmDialog(
        title = "Do you want to turn off the night vision?",
        description = "The night vision will not be activated automatically at nighttime.",
        onConfirm = {
          stateChange(false)
          showDialog = false
        },
        onCancel = {
          showDialog = false
        }
      )
    }
    CommonRow(
      title = "Auto Night Vision",
      rightContent = {
        Switch(
          checked = state,
          onCheckedChange = {
            if (it) stateChange(it) else showDialog = true
          })
      }
    )
  }

  @Composable
  fun StreamingDisplaySettings(state: Int, onClick: () -> Unit) {
    val title = when (state) {
      1 -> "Smart display first"
      0 -> "Smartphone first"
      else -> "Unknown"
    }
    CommonRow(
      title = "Streaming to Alexa/Google Smart Displays",
      onClick = onClick,
      rightContent = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = title, color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp)
          )
          Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
      }
    )
  }

  @Composable
  fun NightVisionSettings(modeName: String, onClick: () -> Unit) {
    CommonRow(
      title = "Night Vision",
      onClick = onClick,
      rightContent = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = modeName, color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp)
          )
          Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
      }
    )
  }

  private fun mapNightVisionName(value: Int): String = when (value) {
    100 -> "关闭"
    101 -> "红外开启"
    102 -> "红外自动"
    103 -> "聚光灯自动"
    104 -> "标准自动"
    105 -> "日间模式自动"
    106 -> "超级日间自动"
    0 -> "关闭"
    1 -> "黑白"
    2 -> "彩色"
    else -> "Mode $value"
  }

}