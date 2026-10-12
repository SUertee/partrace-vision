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
import com.anker.esiotkit.demo.generated.resources.eslot_settings_video_streaming_quality_title

/**
 * Streaming Quality Screen - 从物模型动态获取分辨率选项
 *
 * @version 2024/10/25
 */
class StreamingQualityScreen(@Transient private val viewModel: VideoSettingsViewModel) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_video_streaming_quality_title

  @Composable
  override fun Content() = BaseColumn {
    // 从物模型获取分辨率选项列表
    val property = remember {
      viewModel.megaDevice.getProperty(ThingIdentifier.LIVE_STREAM_RESOLUTION)
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
            description = getQualityDescription(enumItem.value),
            selected = viewModel.streamingQualityState == enumItem.value,
            onClick = { viewModel.setStreamingQualityState(enumItem.value) }
          )
        }
      } else {
        // 兜底：如果物模型没有 enumList，显示默认选项
        CommonLeftOptionRow(
          title = "Auto",
          description = "Automatically adjust the image quality according to network speed.",
          selected = viewModel.streamingQualityState == "0",
          onClick = { viewModel.setStreamingQualityState("0") }
        )
        CommonLeftOptionRow(
          title = "High (2K)",
          description = "High image quality will consume more data and may cause intermittent playback when the network is weak.",
          selected = viewModel.streamingQualityState == "3",
          onClick = { viewModel.setStreamingQualityState("3") }
        )
        CommonLeftOptionRow(
          title = "Medium (1080P)",
          description = "Medium image quality will consume less data.",
          selected = viewModel.streamingQualityState == "2",
          onClick = { viewModel.setStreamingQualityState("2") }
        )
        CommonLeftOptionRow(
          title = "Low (720P)",
          description = "Low image quality will keep streaming smooth in poor network conditions.",
          selected = viewModel.streamingQualityState == "1",
          onClick = { viewModel.setStreamingQualityState("1") }
        )
      }
    }
    MegaLoading(viewModel.loading)
  }

  /**
   * 根据 quality 值返回描述文案
   */
  private fun getQualityDescription(value: String): String {
    return when (value) {
      "0" -> "Automatically adjust the image quality according to network speed."
      "1" -> "Low image quality will keep streaming smooth in poor network conditions."
      "2" -> "Medium image quality will consume less data."
      "3" -> "High image quality will consume more data and may cause intermittent playback when the network is weak."
      "4" -> "Maximum image quality for the best viewing experience."
      "5" -> "4K ultra high definition quality."
      else -> ""
    }
  }
}
