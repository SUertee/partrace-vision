package com.anker.esiotkit.sample.main.mega.device_settings.video_settings

import kotlinx.serialization.Transient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.sample.main.mega.device_settings.VideoSettingsViewModel
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonLeftOptionRow
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_video_streaming_display_title

/**
 * @version 2024/10/25
 */

class StreamingDisplayScreen(@Transient private val viewModel: VideoSettingsViewModel) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_video_streaming_display_title

  @Composable
  override fun Content() = BaseColumn {

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      CommonLeftOptionRow(
        title = "Smartphone first",
        description = "Stream 2K on smartphones in the best effort. The Alexa/Google smart display devices negotiate and stream. Latency is expected.",
        selected = viewModel.streamingDisplayState == 0,
        onClick = { viewModel.setStreamingDisplayState(0) }
      )
      CommonLeftOptionRow(
        title = "Smart display first",
        description = "Stream in compatible mode by default. The Alexa/Google smart display will start streaming faster. Smartphone streaming will also be on compatible mode, whose highest resolution is 1600x1200.",
        selected = viewModel.streamingDisplayState == 1,
        onClick = { viewModel.setStreamingDisplayState(1) }
      )

    }
    MegaLoading(viewModel.loading)
  }
}










