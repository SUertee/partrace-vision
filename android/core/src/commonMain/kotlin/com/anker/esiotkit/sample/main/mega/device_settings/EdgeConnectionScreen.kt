package com.anker.esiotkit.sample.main.mega.device_settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.mega.device_settings.VideoSettingsViewModel
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.support.rememberBooleanProperty
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.component.ConfirmDialog
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRow
import com.anker.esiotkit.sample.view.emitToast
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_edge_connection_title

/**
 * Edge Connection Screen (HomeBase 连接设置)
 * 参考 BatteryCam EufyCamEdgeConnectionActivity
 *
 * 功能：
 * - 显示 HomeBase 连接状态
 * - Event Recording 开关（事件录像）
 * - Continuous Recording 开关（连续录像）
 * - RTSP Stream 开关
 * - 存储状态显示
 *
 * @version 2026/03/17
 */
class EdgeConnectionScreen(
  private val viewModel: MegaDeviceSettingsViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_edge_connection_title

  @Composable
  override fun Content() = BaseColumn(paddingHorizontal = 0.dp) {
    val megaDevice = remember { viewModel.megaDevice }
    val scrollState = rememberScrollState()

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(scrollState)
        .background(Color(0xFFF5F5F5)),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // 顶部说明区域
      ConnectionInfoCard(
        deviceName = megaDevice.iotDevice.deviceName,
        stationName = megaDevice.deviceSn // TODO: 获取实际的 HomeBase 名称
      )

      // HomeBase 信息
      PropertyThingContainer(
        megaDevice = megaDevice,
        identifier = "homebase_connection"
      ) {
        HomeBaseInfoCard(
          stationName = megaDevice.deviceSn,
          isConnected = true // TODO: 从物模型获取实际连接状态
        )
      }

      // 录像模式设置
      RecordingModeSettings(
        megaDevice = megaDevice,
        viewModel = viewModel
      )

      // RTSP 流设置
      PropertyThingContainer(
        megaDevice = megaDevice,
        identifier = "rtsp_stream_enabled"
      ) {
        RTSPStreamSettings(
          megaDevice = megaDevice,
          viewModel = viewModel
        )
      }

      // 存储状态
      StorageStatusCard()
    }

    MegaLoading(viewModel.loading)
  }
}

/**
 * 连接信息卡片
 */
@Composable
fun ConnectionInfoCard(
  deviceName: String,
  stationName: String
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
      .padding(16.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = null,
        tint = Color(0xFF4CAF50),
        modifier = Modifier.size(24.dp)
      )
      Spacer(modifier = Modifier.size(12.dp))
      Column {
        Text(
          text = "Connected to Edge System",
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = Color.Black
        )
        Text(
          text = "$deviceName is connected to $stationName",
          fontSize = 14.sp,
          color = Color.Gray
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text(
      text = "Your camera is part of the Edge system, enabling local storage and advanced features.",
      fontSize = 13.sp,
      color = Color.DarkGray,
      lineHeight = 18.sp
    )
  }
}

/**
 * HomeBase 信息卡片
 */
@Composable
fun HomeBaseInfoCard(
  stationName: String,
  isConnected: Boolean
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
  ) {
    CommonRow(
      title = "Edge Device",
      description = stationName,
      rightContent = {
        Icon(
          imageVector = Icons.Default.CheckCircle,
          contentDescription = null,
          tint = if (isConnected) Color(0xFF4CAF50) else Color.Gray,
          modifier = Modifier.size(20.dp)
        )
      }
    )
  }
}

/**
 * 录像模式设置
 */
@Composable
fun RecordingModeSettings(
  megaDevice: com.anker.esiotkit.domain_thing.model.device.MegaDevice,
  viewModel: MegaDeviceSettingsViewModel
) {
  var showEventRecordingDialog by remember { mutableStateOf(false) }
  var showContinuousRecordingDialog by remember { mutableStateOf(false) }

  // Event Recording 开关确认对话框
  if (showEventRecordingDialog) {
    ConfirmDialog(
      title = "Enable Event Recording?",
      description = "The camera will record video clips when motion or other events are detected.",
      onConfirm = {
        // TODO: 调用 ViewModel 方法设置事件录像
        emitToast("Event Recording enabled")
        showEventRecordingDialog = false
      },
      onCancel = {
        showEventRecordingDialog = false
      }
    )
  }

  // Continuous Recording 开关确认对话框
  if (showContinuousRecordingDialog) {
    ConfirmDialog(
      title = "Enable Continuous Recording?",
      description = "The camera will record continuously to the HomeBase storage. This requires sufficient storage space and may consume more power.",
      onConfirm = {
        // TODO: 调用 ViewModel 方法设置连续录像
        emitToast("Continuous Recording enabled")
        showContinuousRecordingDialog = false
      },
      onCancel = {
        showContinuousRecordingDialog = false
      }
    )
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
  ) {
    // Event Recording
    PropertyThingContainer(
      megaDevice = megaDevice,
      identifier = "event_recording_enabled"
    ) {
      val eventRecording by megaDevice.rememberBooleanProperty("event_recording_enabled")
      CommonRow(
        title = "Event Recording",
        description = "Record when motion or events detected",
        rightContent = {
          Switch(
            checked = eventRecording,
            onCheckedChange = {
              if (it) {
                showEventRecordingDialog = true
              } else {
                // TODO: 调用 ViewModel 方法关闭事件录像
                emitToast("Event Recording disabled")
              }
            }
          )
        }
      )
    }

    // Continuous Recording
    PropertyThingContainer(
      megaDevice = megaDevice,
      identifier = "continuous_recording_enabled"
    ) {
      val continuousRecording by megaDevice.rememberBooleanProperty("continuous_recording_enabled")
      CommonRow(
        title = "Continuous Recording",
        description = "Record continuously to HomeBase storage",
        rightContent = {
          Switch(
            checked = continuousRecording,
            onCheckedChange = {
              if (it) {
                showContinuousRecordingDialog = true
              } else {
                // TODO: 调用 ViewModel 方法关闭连续录像
                emitToast("Continuous Recording disabled")
              }
            }
          )
        }
      )
    }
  }
}

/**
 * RTSP 流设置
 */
@Composable
fun RTSPStreamSettings(
  megaDevice: com.anker.esiotkit.domain_thing.model.device.MegaDevice,
  viewModel: MegaDeviceSettingsViewModel
) {
  val rtspEnabled by megaDevice.rememberBooleanProperty("rtsp_stream_enabled")

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
  ) {
    CommonRow(
      title = "RTSP Stream",
      description = "Enable video streaming via RTSP protocol",
      rightContent = {
        Switch(
          checked = rtspEnabled,
          onCheckedChange = {
            // TODO: 调用 ViewModel 方法设置 RTSP
            emitToast("RTSP Stream ${if (it) "enabled" else "disabled"}")
          }
        )
      }
    )
  }
}

/**
 * 存储状态卡片
 */
@Composable
fun StorageStatusCard() {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White, RoundedCornerShape(8.dp))
      .padding(16.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = Icons.Default.Info,
        contentDescription = null,
        tint = Color(0xFF005D8E),
        modifier = Modifier.size(20.dp)
      )
      Spacer(modifier = Modifier.size(12.dp))
      Column {
        Text(
          text = "Storage Status",
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium,
          color = Color.Black
        )
        Text(
          text = "HomeBase Local Storage Available",
          fontSize = 13.sp,
          color = Color.Gray
        )
      }
    }
  }
}