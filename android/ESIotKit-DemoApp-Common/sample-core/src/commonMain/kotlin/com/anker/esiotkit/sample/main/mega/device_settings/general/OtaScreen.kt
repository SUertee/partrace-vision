package com.anker.esiotkit.sample.main.mega.device_settings.general

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.sample.main.mega.device_settings.DeviceGeneralViewModel
import com.anker.esiotkit.sample.support.rememberBooleanProperty
import com.anker.esiotkit.sample.support.rememberLongProperty
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.utils.DateUtil
import com.composeunstyled.Text
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_ota_title

/**
 * @version 2025/8/11
 */
class OtaScreen(
  private val viewModel: DeviceGeneralViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_ota_title
  @Composable
  override fun Content() {
    val device = remember { viewModel.megaDevice }
    val otaViewModel = rememberScreenModel { OtaViewModel(device) }
    val isUpdating by device.rememberBooleanProperty(ThingIdentifier.IS_DEVICE_UPGRADE)
    BaseColumn(modifier = Modifier.fillMaxSize()) {
      val currentVersion by device.rememberStringProperty(ThingIdentifier.FIRMWARE_MAIN_VERSION)
      val currentVersionTime by device.rememberLongProperty(ThingIdentifier.FIRMWARE_MAIN_VERSION_TIME)
      Text("Current Version: $currentVersion", modifier = Modifier.padding(16.dp))
      Text(
        "Current Version Time: ${DateUtil.timeMillisToDateTimeString(currentVersionTime * 1000)}",
        modifier = Modifier.padding(16.dp)
      )
      if (otaViewModel.isOtaLoading) {
        Text(
          text = "Sending upgrade command...",
          modifier = Modifier.padding(16.dp),
          style = MaterialTheme.typography.titleMedium
        )
        CircularProgressIndicator(color = Color.Yellow)
      } else if (isUpdating || otaViewModel.isOtaing) {
        LaunchedEffect(isUpdating) {
          if (!isUpdating) {
            otaViewModel.isOtaing = false
          }
        }
        Text(
          text = "Device is updating...",
          modifier = Modifier.padding(16.dp),
          style = MaterialTheme.typography.titleMedium
        )
        CircularProgressIndicator(color = Color.Yellow)
      } else {
        val newVersion = otaViewModel.newVersion
        if (newVersion != null) {
          Text(
            text = "New Version Code: ${newVersion.romVersion}",
            style = MaterialTheme.typography.titleMedium
          )
          Text(
            text = "New Version Name:${newVersion.romVersionName}",
            style = MaterialTheme.typography.titleMedium
          )
          Text(text = newVersion.introduction, style = MaterialTheme.typography.titleSmall)
          CommonButton("Start Update", modifier = Modifier.padding(16.dp)) {
            otaViewModel.startOta()
          }
        }
        Spacer(modifier = Modifier.weight(1f))
        CommonButton("check for updates") {
          otaViewModel.checkForUpdates()
        }
      }
    }
    MegaLoading(otaViewModel.loading)
  }
}