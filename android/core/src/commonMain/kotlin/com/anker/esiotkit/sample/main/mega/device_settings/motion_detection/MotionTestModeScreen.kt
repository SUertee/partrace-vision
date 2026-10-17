package com.anker.esiotkit.sample.main.mega.device_settings.motion_detection

import kotlinx.serialization.Transient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.mega.device_settings.MegaDeviceSettingsViewModel
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.DetectionSensitivitySelector
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_motion_test_mode_title

/**
 * @version 2024/10/30
 */

class MotionTestModeScreen(@Transient private val viewModel: MegaDeviceSettingsViewModel) :
  BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_motion_test_mode_title

  @Composable
  override fun Content() = BaseColumn {

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 16.dp)) {
      Text(
        text = "${viewModel.deviceName} will flash red when motion is detected.",
        style = MaterialTheme.typography.bodyMedium
      )
      // DetectionSensitivitySelector(
      //   sliderValue = viewModel.detectionSensitivityTest + 1,
      //   onValueChange = { viewModel.setDetectionSensitivityTest(it - 1) })
      Text(
        text = "You can test the motion sensor detection distance with this mode.",
        fontSize = 14.sp,
        color = Color.Gray
      )

    }
    MegaLoading(viewModel.loading)
  }
}