package com.anker.esiotkit.sample.main.mega.device_settings.general

import kotlinx.serialization.Transient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.anker.esiotkit.sample.ui.component.DisableOverscrollEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.core.constants.isStation
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.sample.main.mega.device_settings.DeviceGeneralViewModel
import com.anker.esiotkit.sample.support.ActionThingContainer
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.support.rememberBooleanProperty

import com.anker.esiotkit.sample.ui.component.ButtonDialog
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRow
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_general_title

/**
 * @version 2024/10/25
 */

class GeneralScreen(
  @Transient private val viewModel: DeviceGeneralViewModel
) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_general_title

  @Composable
  override fun Content() = BaseColumn(paddingHorizontal = 0.dp) {
    val navigator = LocalNavigator.currentOrThrow
    val megaDevice = viewModel.megaDevice

    val isOnline by megaDevice.rememberBooleanProperty(ThingIdentifier.DEVICE_IS_ONLINE)
    val cameraOnOffState by megaDevice.rememberBooleanProperty(ThingIdentifier.CAMERA_ON_OFF)
    val isStationDevice = remember { megaDevice.deviceSn.isStation() }

    DisableOverscrollEffect {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFFF5F5F5)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        item {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color.White, RoundedCornerShape(8.dp))
          ) {
            PropertyThingContainer(
              megaDevice = megaDevice,
              identifier = ThingIdentifier.DEVICE_NAME
            ) {
              CommonRow(
                title = "Name",
                rightContent = {
                  Text(text = viewModel.displayDeviceName, color = MaterialTheme.colorScheme.primary)
                  Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                },
                onClick = {
                  navigator.push(
                    CameraNameScreen(viewModel.displayDeviceName, viewModel::setDeviceNameNew)
                  )
                }
              )
            }
          }
        }

        item {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color.White, RoundedCornerShape(8.dp))
          ) {
            PropertyThingContainer(
              megaDevice = megaDevice,
              identifier = ThingIdentifier.LED_ON_OFF
            ) {
              val ledState by megaDevice.rememberBooleanProperty(ThingIdentifier.LED_ON_OFF)
              // Station 设备没有 CAMERA_ON_OFF 属性，所以需要特殊处理
              if (isOnline && (cameraOnOffState || isStationDevice)) {
                StatusLEDSettings(ledState, viewModel::setLedState)
              }
            }
          }
        }

        // WiFi Signal Strength
        item {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color.White, RoundedCornerShape(8.dp))
          ) {
            PropertyThingContainer(
              megaDevice = megaDevice,
              identifier = ThingIdentifier.DEVICE_WIFI_SIGNAL_STRENGTH
            ) {
              CommonRow(
                title = "WiFi Signal",
                description = "View WiFi signal strength",
                onClick = { navigator.push(WiFiSignalScreen(viewModel = viewModel)) }
              )
            }
          }
        }

        item {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color.White, RoundedCornerShape(8.dp))
          ) {
            PropertyThingContainer(
              megaDevice = megaDevice,
              identifier = ThingIdentifier.TIMEZONE_SET
            ) {
              if (isOnline && (cameraOnOffState || isStationDevice)) {
                CommonRow(
                  title = "Time Settings",
                  rightContent = {
                    Text(text = "", color = MaterialTheme.colorScheme.primary)
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                  },
                  onClick = {
                    navigator.push(TimeSettingsScreen(viewModel = viewModel))
                  }
                )
              }
            }
          }
        }

        item {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color.White, RoundedCornerShape(8.dp))
          ) {
            ActionThingContainer(
              megaDevice = megaDevice,
              identifier = ThingIdentifier.GET_STORAGE_INFO
            ) {
              if (isOnline && (cameraOnOffState || isStationDevice)) {
                CommonRow(
                  title = "Storage",
                  description = "View local storage info (total and used)",
                  onClick = { navigator.push(LocalStorageScreen(viewModel = viewModel)) }
                )
              }
            }
          }
        }

        item {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color.White, RoundedCornerShape(8.dp))
          ) {
            PropertyThingContainer(
              megaDevice = megaDevice,
              identifier = ThingIdentifier.DEVICE_SERIAL_NUMBER
            ) {
              CommonRow(
                title = "Mounting Guide",
                onClick = { navigator.push(MountingGuideScreen(viewModel = viewModel)) }
              )
            }
          }
        }

        item {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color.White, RoundedCornerShape(8.dp))
          ) {
            PropertyThingContainer(
              megaDevice = megaDevice,
              identifier = ThingIdentifier.DEVICE_SERIAL_NUMBER
            ) {
              CommonRow(
                title = "Share Device",
                onClick = { navigator.push(ShareDeviceScreen(viewModel = viewModel)) }
              )
            }
          }
        }

        // About Device - Always visible
        item {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(Color.White, RoundedCornerShape(8.dp))
          ) {
            CommonRow(
              title = "About Device",
              description = "View device info: model, SN, firmware, MAC, IP",
              onClick = { navigator.push(AboutDeviceScreen(viewModel = viewModel)) }
            )
          }
        }
      }
    }
    MegaLoading(viewModel.loading)
  }

  @Composable
  fun StatusLEDSettings(state: Boolean, setState: (Boolean) -> Unit) {
    var showLedDialog by remember { mutableStateOf(false) }
    if (showLedDialog) {
      ButtonDialog(
        dialogText = "Status LED will turn on when the device is live streaming or recording.",
        onConfirm = {
          setState(true)
          showLedDialog = false
        },
        onCancel = {
          showLedDialog = false
        },
        buttonText = "Confirm"
      )
    }
    CommonRow(
      title = "Status LED",
      rightContent = {
        Switch(
          checked = state,
          onCheckedChange = { if (it) showLedDialog = true else setState(it) })
      }
    )
  }

}