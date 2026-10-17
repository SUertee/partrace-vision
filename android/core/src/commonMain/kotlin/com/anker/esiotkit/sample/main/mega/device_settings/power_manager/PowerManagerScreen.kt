package com.anker.esiotkit.sample.main.mega.device_settings.power_manager

import kotlinx.serialization.Transient

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.sample.main.mega.device_settings.MegaDeviceSettingsViewModel
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonLeftOptionRow
import com.anker.esiotkit.sample.view.CommonRow
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key.Companion.P
import androidx.compose.ui.text.style.TextAlign
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.net_api.response.thing.TDEnum
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.support.rememberIntProperty
import com.anker.esiotkit.sample.support.rememberProperty
import com.anker.esiotkit.sample.support.rememberStringProperty
import com.anker.esiotkit.sample.ui.component.BatteryIndicator
import com.anker.esiotkit.sample.ui.component.ButtonDialog
import com.anker.esiotkit.sample.ui.composable.rememberDerivedState
import com.anker.esiotkit.support.log.appLogger
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_power_title

/**
 * @version 2024/10/29
 */

class PowerManagerScreen(@Transient private val viewModel: MegaDeviceSettingsViewModel) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_power_title

  @Composable
  override fun Content() = BaseColumn {
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val bottomSheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)

    ModalBottomSheetLayout(
      sheetState = bottomSheetState,
      sheetContent = {
        BottomSheetContent(
          onHide = { scope.launch { bottomSheetState.hide() } },
          setPowerChargeMode = { mode -> viewModel.setPowerChargeMode(mode) })
      },
      sheetShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.padding(vertical = 16.dp)
        ) {
          PropertyThingContainer(
            megaDevice = viewModel.megaDevice,
            identifier = ThingIdentifier.BATTERY_VALUE
          ) {
            val value by viewModel.megaDevice.rememberIntProperty(it)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.Center
            ) {
              BatteryIndicator(value)
            }
          }
          DetectionStatistics(viewModel.megaDevice)
          Spacer(modifier = Modifier.height(16.dp))
          PropertyThingContainer(
            megaDevice = viewModel.megaDevice,
            identifier = ThingIdentifier.POWER_MANAGER_MODE
          ) {
            WorkingModeOptions(
              megaDevice = viewModel.megaDevice,
              onSetPowerManagerMode = { mode -> viewModel.setPowerManagerMode(mode) },
              onCustomizeRecording = { navigator.push(CustomizeRecordingScreen(viewModel = viewModel)) }
            )
          }

          Spacer(modifier = Modifier.height(16.dp))

          PropertyThingContainer(
            megaDevice = viewModel.megaDevice,
            identifier = ThingIdentifier.POWER_CHARGE_MODE
          ) {
            val powerSource by viewModel.megaDevice.rememberIntProperty(it)
            PowerSourceOption(
              powerSource = powerSource,
              onShow = {
                appLogger.i("Show bottom sheet");
                scope.launch { bottomSheetState.show() }
              }
            )
          }
        }
      }
    }
    MegaLoading(viewModel.loading)
  }

  @Composable
  fun BottomSheetContent(onHide: () -> Unit, setPowerChargeMode: (Int) -> Unit) {
    var showLSolarPanelDialog by remember { mutableStateOf(false) }
    var showLEDDialog by remember { mutableStateOf(false) }
    if (showLSolarPanelDialog) {
      ButtonDialog(
        dialogText = "Please confirm you have installed the solar panel and connect it to the camera before changing the settings.",
        onConfirm = {
          setPowerChargeMode(1)
          showLSolarPanelDialog = false
          showLEDDialog = true
        },
        onCancel = {
          showLSolarPanelDialog = false
        },
        buttonText = "Confirm"
      )
    }
    if (showLEDDialog) {
      ButtonDialog(
        dialogText = "The camera status LED will stay off when solar panel is connected.",
        onConfirm = {
          showLEDDialog = false
        },
        onCancel = {
          showLEDDialog = false
        },
        buttonText = "Got it"
      )
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = "Battery",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth()
          .clickable {
            setPowerChargeMode(0)
            onHide()
          }
          .padding(16.dp),
        textAlign = TextAlign.Center
      )
      Divider()
      Text(
        text = "External Solar Panel",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
          .fillMaxWidth()
          .clickable {
            onHide()
            showLSolarPanelDialog = true
          }
          .padding(16.dp),
        textAlign = TextAlign.Center
      )
      Divider()
      Text(
        text = "Cancel",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onHide() }
          .padding(16.dp),
        textAlign = TextAlign.Center
      )
    }
  }

  @Composable
  fun DetectionStatistics(megaDevice: MegaDevice) {
    Column {
      Text(
        "Detection statistics",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(16.dp)
      )
      Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
          .fillMaxWidth()
          .height(IntrinsicSize.Min)
      ) {
        val workingDay by megaDevice.rememberStringProperty(ThingIdentifier.WORKING_DAYS)
        StatisticItem(workingDay, "Working Days", Modifier.weight(1f))
        Divider(color = Color.Gray, modifier = Modifier.fillMaxHeight().width(1.dp))
        val detectedEvents by megaDevice.rememberStringProperty(ThingIdentifier.DETECTED_EVENTS)
        StatisticItem(detectedEvents, "Detected Events", Modifier.weight(1f))
        Divider(color = Color.Gray, modifier = Modifier.fillMaxHeight().width(1.dp))
        val recordingNumber by megaDevice.rememberStringProperty(ThingIdentifier.RECORDING_NUMBER)
        StatisticItem(recordingNumber, "Recordings", Modifier.weight(1f))
      }
    }
  }

  @Composable
  fun WorkingModeOptions(
    megaDevice: MegaDevice,
    onSetPowerManagerMode: (String) -> Unit,
    onCustomizeRecording: () -> Unit
  ) {
    val property = megaDevice.rememberProperty(ThingIdentifier.POWER_MANAGER_MODE) ?: return
    val propertyValue by megaDevice.rememberStringProperty(ThingIdentifier.POWER_MANAGER_MODE)
    val td by rememberDerivedState { property.td?.dataType }
    appLogger.i("PowerManagerScreen/WorkingModeOptions/td:$td")
    Column {
      Text("Working Mode", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
      td?.specs?.enumList?.forEach { tdEnum ->
        CommonLeftOptionRow(
          title = getBatteryTitle(tdEnum),
          description = getBatteryDescription(tdEnum),
          selected = propertyValue == tdEnum.value,
          rightIcon = {
            if (tdEnum.desc == "CUSTOMIZE_RECORDING") {
              Icon(imageVector = Icons.Filled.Edit, contentDescription = null)
            }
          },
          onIconClick = {
            if (tdEnum.desc == "CUSTOMIZE_RECORDING") {
              onCustomizeRecording()
            }
          },
          onClick = { onSetPowerManagerMode(tdEnum.value) }
        )
      }
    }
  }

  @Composable
  fun PowerSourceOption(powerSource: Int, onShow: () -> Unit) {
    CommonRow(
      title = "Power Source",
      rightContent = {
        Text(
          text = if (powerSource == 0) "Battery" else "Solar Panel",
          color = MaterialTheme.colorScheme.primary
        )
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
      },
      onClick = { onShow() }
    )
  }

  @Composable
  fun StatisticItem(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.padding(horizontal = 8.dp)
    ) {
      Text(value, style = MaterialTheme.typography.titleMedium)
      Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
  }

  private fun getBatteryTitle(tdEnum: TDEnum): String {
    return when (tdEnum.desc) {
      "OPTIMAL_BATTERY_LIFE" -> "Optimal Battery Life"
      "OPTIMAL_SURVEILLANCE" -> "Optimal Surveillance"
      "BALANCE_SURVEILLANCE" -> "Balance Surveillance"
      "CUSTOMIZE_RECORDING" -> "Customize Recording"
      else -> tdEnum.desc
    }
  }

  private fun getBatteryDescription(tdEnum: TDEnum): String {
    return when (tdEnum.desc) {
      "OPTIMAL_BATTERY_LIFE" -> "Video clip length is up to 20 seconds. \nThe system automatically adjusts the detection time period to reduce the chances of detecting the same object twice in a short period."
      "OPTIMAL_SURVEILLANCE" -> "Video clip length can be up to 60 seconds.\n The system will attempt to record each event for as much as possible."
      "BALANCE_SURVEILLANCE" -> "Video clip length is up to 30 seconds. \nThe system automatically adjusts the detection time period to reduce the chances of detecting the same object twice in a short period."
      "CUSTOMIZE_RECORDING" -> "Video clip length and detection periods can be customized. \nMotion is detected more frequently the battery will drain faster."
      else -> ""
    }
  }
}