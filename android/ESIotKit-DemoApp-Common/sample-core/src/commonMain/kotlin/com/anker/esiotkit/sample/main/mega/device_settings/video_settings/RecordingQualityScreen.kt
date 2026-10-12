package com.anker.esiotkit.sample.main.mega.device_settings.video_settings

import kotlinx.serialization.Transient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.sample.main.mega.device_settings.VideoSettingsViewModel
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonLeftOptionRow
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_video_recording_quality_title

/**
 * Recording Quality Screen - 从物模型动态获取录制分辨率选项
 *
 * @version 2024/10/25
 */
class RecordingQualityScreen(@Transient private val viewModel: VideoSettingsViewModel) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_video_recording_quality_title

  @Composable
  override fun Content() = BaseColumn {
    // 从物模型获取录制分辨率选项列表
    val property = remember {
      viewModel.megaDevice.getProperty(ThingIdentifier.RECORD_RESOLUTION)
    }
    val enumList = remember {
      property?.td?.dataType?.specs?.enumList ?: emptyList()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (enumList.isNotEmpty()) {
        // 动态显示物模型定义的选项
        enumList.forEach { enumItem ->
          CommonLeftOptionRow(
            title = enumItem.desc.ifEmpty { "Quality ${enumItem.value}" },
            description = getRecordingQualityDescription(enumItem.value),
            selected = viewModel.recordingQualityState == enumItem.value,
            onClick = { viewModel.setRecordingQualityState(enumItem.value) }
          )
        }
      } else {
        // 兜底：如果物模型没有 enumList，显示默认选项
        CommonLeftOptionRow(
          title = "2K HD",
          description = "2K high resolution with the high compression encoding format",
          selected = viewModel.recordingQualityState == "5",
          onClick = { viewModel.setRecordingQualityState("5") }
        )
        CommonLeftOptionRow(
          title = "Full HD (1080P)",
          description = "1080P resolution with the H.264 encoding format",
          selected = viewModel.recordingQualityState == "2",
          onClick = { viewModel.setRecordingQualityState("2") }
        )
      }
    }
    MegaLoading(viewModel.loading)
  }

  /**
   * 根据 quality 值返回录制质量描述文案
   */
  private fun getRecordingQualityDescription(value: String): String {
    return when (value) {
      "0" -> "Auto quality based on storage capacity."
      "1" -> "720P resolution for basic quality recording."
      "2" -> "1080P resolution with the H.264 encoding format."
      "3" -> "2K resolution for high quality recording."
      "4" -> "2K+ resolution for enhanced quality."
      "5" -> "2K high resolution with the high compression encoding format."
      else -> ""
    }
  }
}
