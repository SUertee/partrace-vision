package com.anker.esiotkit.sample.main.mega.device_settings.audio_settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.sample.main.mega.device_settings.AudioSettingsViewModel
import com.anker.esiotkit.sample.main.mega.device_settings.audio_settings.SoundDetectionScreen
import com.anker.esiotkit.sample.support.rememberBooleanProperty
import com.anker.esiotkit.sample.ui.component.ConfirmDialog
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRow
import com.anker.esiotkit.sample.view.currentNavigator
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.sample.view.CustomSlider
import com.anker.esiotkit.sample.view.PropertySlider
import com.anker.esiotkit.sample.view.PropertySwitch
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_audio_title

/**
 * @version 2024/10/25
 */

class AudioSettingsScreen(
  private val viewModel: AudioSettingsViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_audio_title

  @Composable
  override fun Content() = BaseColumn(
    scrollState = rememberScrollState()
  ) {
    PropertySlider(
      viewModel.megaDevice,
      ThingIdentifier.ALARM_VOLUME_VALUE,
      title = "Alarm Volume",
    )
    PropertySwitch(
      megaDevice = viewModel.megaDevice,
      identifier = ThingIdentifier.AUDIO_RECORDING_ON_OFF,
      title = "Audio Recording",
      description = "Enable audio recording when recording videos.",
      showDisableDialog = true,
      disableDialogTitle = "Do you want to turn off the camera audio?",
      disableDialogDesc = "Recording sound will be disabled.",
    )
    PropertySwitch(
      megaDevice = viewModel.megaDevice,
      identifier = ThingIdentifier.MICROPHONE_ON_OFF,
      title = "Microphone",
      showDisableDialog = true,
      disableDialogTitle = "Do you want to turn off the microphone?",
      disableDialogDesc = "The talk-back, recording sound and sound alarm will be disabled as well.",
    )

    PropertySwitch(
      megaDevice = viewModel.megaDevice,
      identifier = ThingIdentifier.SPEAKER_ON_OFF,
      title = "Speaker",
    )
    val speakerOn by viewModel.megaDevice.rememberBooleanProperty(ThingIdentifier.SPEAKER_ON_OFF)
    if (speakerOn) {
      PropertySlider(
        megaDevice = viewModel.megaDevice,
        identifier = ThingIdentifier.SPEAKER_VOLUME,
        title = "Speaker Volume",
        modifier = Modifier.padding(top = 16.dp),
      )
      PropertySlider(
        megaDevice = viewModel.megaDevice,
        identifier = ThingIdentifier.DEVICE_AUDIO_RINGTONE_VOLUME,
        title = "Doorbell Ringtone Volume",
        modifier = Modifier.padding(top = 16.dp),
      )
    }

    val soundDetectionVisible = remember { viewModel.megaDevice.hasProperty("sound_detection_status") }
    appLogger.i("AudioSettings/visibility: soundDetectionVisible=$soundDetectionVisible, deviceSn=${viewModel.megaDevice.deviceSn}")
    if (soundDetectionVisible) {
      CommonRow(
        title = "Sound Detection",
        onClick = { currentNavigator?.push(SoundDetectionScreen(viewModel = viewModel)) }
      )
    }

    MegaLoading(viewModel.loading)
  }

  @Composable
  fun SpeakerSettings(
    speakerState: Boolean,
    speakerVolume: Int,
    onSpeakerStateChange: (Boolean) -> Unit,
    onSpeakerVolumeChange: (Int) -> Unit,
    megaDevice: MegaDevice,
  ) {
    var speakerDialog by remember { mutableStateOf(false) }

    if (speakerDialog) {
      ConfirmDialog(
        title = "Do you want to turn off the speaker?",
        description = "Both talk-back and sound alarm will be disabled.",
        onConfirm = {
          onSpeakerStateChange(false)
          speakerDialog = false
        },
        onCancel = {
          speakerDialog = false
        }
      )
    }
    CommonRow(
      title = "Speaker",
      rightContent = {
        Switch(
          checked = speakerState,
          onCheckedChange = {
            if (it) {
              onSpeakerStateChange(it)
            } else speakerDialog = true
          }
        )
      },
      content = {
        if (speakerState && !megaDevice.hasProperty(ThingIdentifier.DEVICE_AUDIO_RINGTONE_VOLUME)) {
          VolumeSlider(
            title = "Speaker Volume",
            volume = 2 - speakerVolume,
            onVolumeChange = { value ->
              val intValue = when (value) {
                0 -> 2 // LOW
                1 -> 1 // Medium
                2 -> 0 // HIGH
                else -> 1 // Default to Medium
              }
              onSpeakerVolumeChange(intValue)
            }
          )
        }
      }
    )
  }

  @Composable
  fun VolumeSlider(
    title: String,
    volume: Int,
    onVolumeChange: (Int) -> Unit
  ) {
    Column(
      modifier = Modifier.fillMaxWidth()
    ) {
      CustomSlider(
        title = title,
        range = 0..2,
        sliderValue = volume,
        showNumbersAbove = false,
        onValueChange = onVolumeChange
      )
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(text = "LOW", color = if (volume == 0) MaterialTheme.colorScheme.primary else Color.Gray)
        Text(text = "Medium", color = if (volume == 1) MaterialTheme.colorScheme.primary else Color.Gray)
        Text(text = "HIGH", color = if (volume == 2) MaterialTheme.colorScheme.primary else Color.Gray)
      }
    }
  }
}