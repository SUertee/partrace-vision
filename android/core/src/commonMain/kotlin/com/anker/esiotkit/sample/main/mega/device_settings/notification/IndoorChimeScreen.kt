package com.anker.esiotkit.sample.main.mega.device_settings.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.sample.main.mega.device_settings.NotificationSettingsViewModel
import com.anker.esiotkit.sample.support.rememberIntProperty
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRowItem
import com.anker.esiotkit.sample.view.CommonSwitchRow
import com.anker.esiotkit.sample.view.emitToast
import androidx.compose.ui.Alignment
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_notification_indoor_chime_title

/**
 * Indoor Chime Settings Screen
 * Configure doorbell and chime sounds
 */
class IndoorChimeScreen(
  private val viewModel: NotificationSettingsViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_notification_indoor_chime_title

  @Composable
  override fun Content() = BaseColumn(
    scrollState = rememberScrollState()
  ) {
    val megaDevice = remember { viewModel.megaDevice }

    // Chime switches group
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, RoundedCornerShape(8.dp))
    ) {
        // Doorbell Chime
        val doorbellProp = remember { megaDevice.getProperty("doorbell_chime_onoff") }
        val doorbellValue by megaDevice.rememberIntProperty("doorbell_chime_onoff")
        CommonSwitchRow(
          title = "Doorbell Chime",
          state = doorbellValue == 1,
          onClick = {
            if (doorbellProp == null) {
              emitToast("Doorbell chime not supported by this device")
            } else {
              viewModel.setDoorbellChime(it)
            }
          }
        )

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        // Minibase Chime
        val minibaseProp = remember { megaDevice.getProperty("minibase_chime_onoff") }
        val minibaseValue by megaDevice.rememberIntProperty("minibase_chime_onoff")
        CommonSwitchRow(
          title = "Minibase Chime",
          state = minibaseValue == 1,
          onClick = {
            if (minibaseProp == null) {
              emitToast("Minibase chime not supported by this device")
            } else {
              viewModel.setMinibaseChime(it)
            }
          }
        )

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        // Homebase Chime
        val homebaseProp = remember { megaDevice.getProperty("homebase_chime_onoff") }
        val homebaseValue by megaDevice.rememberIntProperty("homebase_chime_onoff")
        CommonSwitchRow(
          title = "Homebase Chime",
          state = homebaseValue == 1,
          onClick = {
            if (homebaseProp == null) {
              emitToast("Homebase chime not supported by this device")
            } else {
              viewModel.setHomebaseChime(it)
            }
          }
        )

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        // Chime Volume
        CommonRowItem(
          "Chime Volume",
          onClick = {
            val volumeProp = megaDevice.getProperty("chime_volume")
            if (volumeProp == null) {
              emitToast("Chime volume not supported by this device")
            } else {
              emitToast("Chime volume control (TODO)")
            }
          }
        )
    }
    MegaLoading(viewModel.loading)
  }
}
