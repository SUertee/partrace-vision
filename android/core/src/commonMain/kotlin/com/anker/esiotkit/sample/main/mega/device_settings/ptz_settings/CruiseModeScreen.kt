package com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.sample.main.mega.device_settings.PTZSettingsViewModel
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonLeftOptionRow
import kotlin.jvm.Transient
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_ptz_cruise_mode_title

/**
 * Cruise Mode Settings Screen
 * Select patrol pattern for auto-cruise
 */
@OptIn(ExperimentalMultiplatform::class)
class CruiseModeScreen(
  @kotlinx.serialization.Transient private val viewModel: PTZSettingsViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_ptz_cruise_mode_title

  @Composable
  override fun Content() = BaseColumn(scrollState = rememberScrollState()) {
    val megaDevice = remember { viewModel.megaDevice }
    val cruiseMode by megaDevice.rememberStringProperty("cruise_mode")

    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Patrol Mode
      CommonLeftOptionRow(
        title = "Patrol",
        description = "Camera moves between preset positions",
        selected = cruiseMode == "patrol",
        onClick = { viewModel.setCruiseMode("patrol") }
      )

      // Panoramic Mode
      CommonLeftOptionRow(
        title = "Panoramic",
        description = "Camera makes full 360° sweep",
        selected = cruiseMode == "panoramic",
        onClick = { viewModel.setCruiseMode("panoramic") }
      )

      // Custom Mode
      CommonLeftOptionRow(
        title = "Custom",
        description = "User-defined patrol path",
        selected = cruiseMode == "custom",
        onClick = { viewModel.setCruiseMode("custom") }
      )
    }
    MegaLoading(viewModel.loading)
  }
}
