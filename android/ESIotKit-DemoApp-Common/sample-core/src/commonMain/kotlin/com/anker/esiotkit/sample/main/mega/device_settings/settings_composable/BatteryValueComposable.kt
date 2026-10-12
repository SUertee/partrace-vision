package com.anker.esiotkit.sample.main.mega.device_settings.settings_composable

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.support.rememberBooleanProperty
import com.anker.esiotkit.sample.support.rememberIntProperty
import com.anker.esiotkit.sample.ui.component.BatteryIndicator

/**
 * @version 2025/8/19
 */
@Composable
internal fun BatteryValueComposable(megaDevice: MegaDevice, modifier: Modifier = Modifier) {
  PropertyThingContainer(megaDevice = megaDevice, identifier = ThingIdentifier.BATTERY_VALUE) {
    val isOnline by megaDevice.rememberBooleanProperty(ThingIdentifier.DEVICE_IS_ONLINE)
    Column(modifier = modifier) {
      val batteryValue by megaDevice.rememberIntProperty(ThingIdentifier.BATTERY_VALUE)
      BatteryIndicator(batteryValue)
      if (!isOnline) {
        Text(
          text = "Offline",
          color = Color.Red,
          fontSize = 14.sp
        )
      }
    }
  }
}