package com.anker.esiotkit.sample.main.mega.device_settings.general

import kotlinx.serialization.Transient

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.sample.main.mega.device_settings.DeviceGeneralViewModel
import com.anker.esiotkit.sample.support.PropertyThingContainer
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonRow
import com.anker.esiotkit.sample.view.CommonRowItem
import com.anker.esiotkit.sample.view.currentNavigator
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.sample.view.toastNotSupport
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_about_device_title

/**
 * @version 2024/10/25
 */

class AboutDeviceScreen(@Transient private val viewModel: DeviceGeneralViewModel) : BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_about_device_title

  @Composable
  override fun Content() = BaseColumn {
    val clipboardManager = LocalClipboardManager.current

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      PropertyThingContainer(
        megaDevice = viewModel.megaDevice,
        identifier = ThingIdentifier.DEVICE_SERIAL_NUMBER
      ) {
        InfoSection(
          title1 = "Model No.",
          content1 = viewModel.deviceModelNumber,
          title2 = "Serial Number",
          content2 = viewModel.deviceSN,
          copyClick = { text ->
            clipboardManager.setText(AnnotatedString(text))
            emitToast("Copied to clipboard")
          }
        )
      }

      PropertyThingContainer(
        megaDevice = viewModel.megaDevice,
        identifier = ThingIdentifier.IP_ADDRESS
      ) {
        InfoSection(
          title1 = "IP Address",
          content1 = viewModel.ipAddress,
          title2 = "MAC Address",
          content2 = viewModel.macAddress,
          copyClick = { text ->
            clipboardManager.setText(AnnotatedString(text))
            emitToast("Copied to clipboard")
          }
        )
      }

      PropertyThingContainer(
        megaDevice = viewModel.megaDevice,
        identifier = ThingIdentifier.FIRMWARE_MAIN_VERSION
      ) {
        CommonRow(
          title = "System Version",
          rightContent = {
            Text(text = viewModel.systemVersion, color = Color.Gray)
          }
        )
      }

      PropertyThingContainer(
        megaDevice = viewModel.megaDevice,
        identifier = ThingIdentifier.FIRMWARE_MAIN_VERSION
      ) {
        CommonRow(
          title = "Check for firmware update",
          onClick = {
            currentNavigator?.push(OtaScreen(viewModel))
          }
        )
      }

      CommonRow(
        title = "Hardware Version",
        rightContent = {
          Text(text = viewModel.hardwareVersion, color = Color.Gray)
        }
      )

      if (viewModel.isRestartSupported) {
        Spacer(modifier = Modifier.height(16.dp))
        ResetDeviceButton(onRestartDevice = { viewModel.factoryReset() })
      }
    }
    MegaLoading(viewModel.loading, text = if (viewModel.loading) "Resetting..." else null)
  }

  @Composable
  fun InfoSection(
    title1: String,
    content1: String,
    title2: String,
    content2: String,
    copyClick: ((String) -> Unit)? = null
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, RoundedCornerShape(8.dp))
    ) {
      CommonRowItem(
        title = title1,
        rightContent = {
          Text(text = content1, color = Color.Gray)
        }
      )
      HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
      CommonRowItem(
        title = title2,
        rightContent = {
          Text(text = content2, color = Color.Gray)
          if (copyClick != null) {
            IconButton(onClick = { copyClick(content2) }, modifier = Modifier.size(36.dp)) {
              Icon(Icons.Default.ContentCopy, contentDescription = null)
            }
          }
        }
      )
    }
  }
}

@Composable
fun ResetDeviceButton(onRestartDevice: () -> Unit) {
  var showRestartDialog by remember { mutableStateOf(false) }
  if (showRestartDialog) {
    com.anker.esiotkit.sample.ui.component.ButtonDialog(
      onDismissRequest = { showRestartDialog = false },
      onCancel = { showRestartDialog = false },
      onConfirm = {
        onRestartDevice()
        showRestartDialog = false
      },
      dialogText = "Are you sure you want to reset this device to factory defaults?",
      buttonText = "Confirm"
    )
  }
  OutlinedButton(
    onClick = { showRestartDialog = true },
    modifier = Modifier
      .fillMaxWidth()
      .height(48.dp),
    shape = RoundedCornerShape(24.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF005D8E))
  ) {
    Text(text = "Reset Device", color = Color(0xFF005D8E), fontSize = 16.sp)
  }
}