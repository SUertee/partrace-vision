package com.anker.esiotkit.sample.main.mega.device_settings.general

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.sample.main.mega.add_device.scan_qrcode.MegaScanQRCodeScreen
import com.anker.esiotkit.sample.main.mega.device_settings.DeviceGeneralViewModel
import com.anker.esiotkit.sample.model.isStandalone
import com.anker.esiotkit.sample.model.isStation
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.support.platform.isAndroidPlatform
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.sample.ui.component.HBConfirmDialog
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.component.WifiConnect
import com.anker.esiotkit.sample.ui.component.WifiSignalStrength
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRow
import com.anker.esiotkit.sample.view.toastNotSupport
import com.anker.esiotkit.security_device.pair.DevicePairType
import com.anker.esiotkit.security_device.pair.SecurityDevicePairConfig
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_general_wifi_connection_title

/**
 * @version 2024/10/25
 */

class WiFiConnectionScreen(
  private val viewModel: DeviceGeneralViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_general_wifi_connection_title

  @Composable
  override fun Content() = BaseColumn {
    var connectType by remember { mutableStateOf(-1) }
    var wifiName by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    val navigator = LocalNavigator.currentOrThrow

    LaunchedEffect(Unit) {
      viewModel.getPairingType()
    }
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      when (connectType) {
        0 -> {
          WifiConnect(
            wifiName = wifiName,
            wifiPassword = wifiPassword,
            onWifiNameChange = { wifiName = it },
            onWifiPasswordChange = { wifiPassword = it },
            showScanPicker = isAndroidPlatform,
            onSubmit = { wifiName, wifiPassword ->
              appLogger.i("WiFiConnection/onSubmit/enter: deviceSn=${viewModel.megaDevice.deviceSn}, pairingType=${viewModel.pairingType}, wifiNameLength=${wifiName.length}")
              when (viewModel.pairingType) {
                SecurityDevicePairConfig.SECURITY_QRCODE -> {
                  appLogger.i("WiFiConnection/onSubmit/route: target=MegaScanQRCodeScreen, deviceSn=${viewModel.megaDevice.deviceSn}")
                  navigator.push(
                    MegaScanQRCodeScreen(
                      DevicePairType.SECURITY_QRCODE,
                      wifiName,
                      wifiPassword,
                      viewModel.megaDevice.deviceSn
                    )
                  )
                }
                else -> {
                  appLogger.w("WiFiConnection/onSubmit/noRoute: deviceSn=${viewModel.megaDevice.deviceSn}, pairingType=${viewModel.pairingType}")
                  toastNotSupport()
                }
              }
            })
        }
        1 -> {
          HBConfirmDialog(
            onDismissRequest = {}, onConfirm = {
              connectType = -1
              toastNotSupport()
            }, onCancel = { connectType = -1 }
          )
        }
        else -> {
          ConnectType(
            viewModel.deviceWifiName,
            viewModel.deviceWifiSignalStrengthLevel,
            viewModel.deviceWifiSignalStrength,
            onClick = { connectType = it }
          )
        }
      }
    }
    MegaLoading(viewModel.loading)
  }

  @Composable
  fun ConnectType(
    deviceWifiName: String,
    deviceWifiSignalStrengthLevel: Int,
    deviceWifiSignalStrength: String,
    onClick: (Int) -> Unit
  ) {
    WiFiSettings(
      wifiName = deviceWifiName,
      strengthLevel = deviceWifiSignalStrengthLevel,
      strength = deviceWifiSignalStrength,
      onClick = { onClick(0) })
    CommonRow(
      title = "Connect with HomeBase",
      onClick = { onClick(1) },
    )
  }

  @Composable
  fun WiFiSettings(wifiName: String, strengthLevel: Int, strength: String, onClick: () -> Unit) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      WifiSignalStrength(
        deviceWifiSignalStrengthLevel = strengthLevel,
        deviceWifiSignalStrength = strength
      )
    }
    PropertyThingContainer(
      megaDevice = viewModel.megaDevice,
      identifier = ThingIdentifier.CHANGE_WIFI_IS_SUPPORT
    ) {
      CommonRow(
        title = "Wi-Fi Setup",
        onClick = onClick,
        rightContent = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = wifiName, color = MaterialTheme.colorScheme.primary)
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowForward,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary
            )
          }
        }
      )
    }
    val megaDevice = viewModel.megaDevice
    val hasChangeWifiSupport = remember(megaDevice) {
      megaDevice.hasProperty(ThingIdentifier.CHANGE_WIFI_IS_SUPPORT)
    }
    val isStandaloneWifiDevice = remember(megaDevice.deviceSn, megaDevice.parentSn) {
      megaDevice.isStandalone() && !megaDevice.isStation()
    }
    if (!hasChangeWifiSupport && isStandaloneWifiDevice) {
      appLogger.i("WiFiConnection/WiFiSetup/fallback: deviceSn=${megaDevice.deviceSn}, reason=change_wifi_is_support_not_found, isStandalone=${megaDevice.isStandalone()}, isStation=${megaDevice.isStation()}")
      CommonRow(
        title = "Wi-Fi Setup",
        onClick = {
          appLogger.i("WiFiConnection/WiFiSetup/click: deviceSn=${megaDevice.deviceSn}, source=fallback, pairingType=${viewModel.pairingType}")
          onClick()
        },
        rightContent = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = wifiName, color = MaterialTheme.colorScheme.primary)
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowForward,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary
            )
          }
        }
      )
    }
  }
}