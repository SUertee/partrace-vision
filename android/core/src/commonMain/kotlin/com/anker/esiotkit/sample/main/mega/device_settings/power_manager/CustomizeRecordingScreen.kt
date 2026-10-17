package com.anker.esiotkit.sample.main.mega.device_settings.power_manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.sample.main.mega.device_settings.MegaDeviceSettingsViewModel
import com.anker.esiotkit.sample.support.rememberBooleanProperty
import com.anker.esiotkit.sample.support.rememberIntProperty
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.composable.state
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRow
import com.anker.esiotkit.sample.view.SliderWithLabel
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_power_customize_recording_title

/**
 * @version 2024/10/30
 */

class CustomizeRecordingScreen(
  private val viewModel: MegaDeviceSettingsViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_power_customize_recording_title

  @Composable
  override fun Content() = BaseColumn {
    val megaDevice = remember { viewModel.megaDevice }
    Column(
      verticalArrangement = Arrangement.spacedBy(16.dp),
      modifier = Modifier.padding(vertical = 16.dp)
    ) {
      val clipValue by megaDevice.rememberIntProperty(ThingIdentifier.VIDEO_CLIP_LENGTH)
      ClipLengthSettings(
        clipLength = clipValue,
        onClipLengthChange = viewModel::setVideoClipLength
      )
      val triggerIntervalTime by megaDevice.rememberIntProperty(ThingIdentifier.TRIGGER_INTERVAL_TIME)
      IntervalSettings(
        interval = triggerIntervalTime,
        onIntervalChange = viewModel::setTriggerIntervalTime
      )
      val endEarly by megaDevice.rememberBooleanProperty(ThingIdentifier.MOTION_STOP_END_EARLY)
      EndClipSettings(
        state = endEarly,
        onStateChange = viewModel::setEndEarly
      )
      Text(
        text = "Note: Battery life decreases significantly in this mode.",
        color = Color.Red,
        fontSize = 14.sp,
      )
    }
    MegaLoading(viewModel.loading)
  }

  @Composable
  fun ClipLengthSettings(
    clipLength: Int,
    onClipLengthChange: (Int) -> Unit,
    minValue: Int = 10,
    maxValue: Int = 120
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Text("Clip length", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(start = 16.dp))
      SliderWithLabel(
        value = clipLength,
        valueRange = minValue..maxValue,
        onValueChange = { onClipLengthChange(it) },
        label = { "${it}s" },
        minValue = minValue,
        maxValue = maxValue
      )
    }
  }

  @Composable
  fun IntervalSettings(
    interval: Int,
    onIntervalChange: (Int) -> Unit,
    minValue: Int = 5,
    maxValue: Int = 60
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
    ) {
      Text(
        "Intervals between triggers",
        fontSize = 14.sp,
        color = Color.Gray,
        modifier = Modifier.padding(start = 16.dp)
      )
      SliderWithLabel(
        value = interval,
        valueRange = minValue..maxValue,
        onValueChange = { onIntervalChange(it) },
        label = { "${it}s" },
        minValue = minValue,
        maxValue = maxValue
      )
    }
  }

  @Composable
  fun EndClipSettings(
    state: Boolean,
    onStateChange: (Boolean) -> Unit
  ) {
    CommonRow(
      title = "End clip early if motion stops",
      rightContent = {
        Switch(
          checked = state,
          onCheckedChange = {
            onStateChange(it)
          }
        )
      }
    )
  }

}