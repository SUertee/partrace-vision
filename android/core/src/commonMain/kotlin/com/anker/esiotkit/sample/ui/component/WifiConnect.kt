package com.anker.esiotkit.sample.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.anker.esiotkit.domain.add_device.wifi.model.WifiNetwork
import com.anker.esiotkit.sample.support.permissions.rememberPermissionController
import com.anker.esiotkit.sample.support.platform.scanNearbyWifiNetworks
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.PermissionState
import dev.icerock.moko.permissions.compose.BindEffect
import kotlinx.coroutines.launch

@Composable
fun WifiConnect(
  wifiName: String,
  wifiPassword: String,
  onWifiNameChange: (String) -> Unit,
  onWifiPasswordChange: (String) -> Unit,
  onSubmit: (String, String) -> Unit,
  showScanPicker: Boolean = false,
  modifier: Modifier = Modifier
) {
  val scope = rememberCoroutineScope()
  val controller = if (showScanPicker) rememberPermissionController() else null
  if (controller != null) {
    BindEffect(controller)
  }

  var showWifiListDialog by remember { mutableStateOf(false) }
  var scannedWifiList by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }
  var isScanning by remember { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Default.Home, contentDescription = null, modifier = modifier.size(128.dp))
    Text(text = "Please select a Wi-Fi network")
    OutlinedTextField(
      modifier = modifier
        .fillMaxWidth()
        .padding(16.dp),
      value = wifiName,
      onValueChange = onWifiNameChange,
      label = { Text("name") },
      trailingIcon = {
        Icon(
          Icons.AutoMirrored.Filled.ArrowForward,
          contentDescription = "Select Wi-Fi",
          modifier = if (showScanPicker) Modifier.clickable {
            if (isScanning) return@clickable
            appLogger.i("WifiConnect/onScanClick")
            scope.launch {
              val state = controller?.getPermissionState(Permission.LOCATION)
              val granted = state == PermissionState.Granted
              appLogger.i("WifiConnect/onScanClick: permissionState=$state")

              if (!granted && controller != null) {
                controller.providePermission(Permission.LOCATION)
              }

              val finalState = controller?.getPermissionState(Permission.LOCATION)
              val permissionGranted = finalState == PermissionState.Granted
              appLogger.i("WifiConnect/onScanClick: permissionGranted=$permissionGranted")

              if (permissionGranted) {
                isScanning = true
                val result = scanNearbyWifiNetworks()
                scannedWifiList = result
                isScanning = false
                appLogger.i("WifiConnect/scanResult: count=${result.size}, permissionGranted=true")
                if (result.isEmpty()) {
                  emitToast("未扫描到 WiFi，请手动输入")
                } else {
                  showWifiListDialog = true
                }
              } else {
                emitToast("需要位置权限才能扫描 WiFi")
                appLogger.i("WifiConnect/scanResult: count=0, permissionGranted=false")
              }
            }
          } else Modifier
        )
      },
    )
    OutlinedTextField(
      modifier = modifier
        .fillMaxWidth()
        .padding(16.dp),
      value = wifiPassword,
      onValueChange = onWifiPasswordChange,
      label = { Text("Password") },
      trailingIcon = {
        Icon(
          Icons.Default.Home, contentDescription = "Toggle Password Visibility"
        )
      }
    )
    Button(
      onClick = { onSubmit(wifiName, wifiPassword) },
      modifier = modifier
        .fillMaxWidth()
        .padding(16.dp)
        .height(48.dp),
      shape = RoundedCornerShape(24.dp),
      colors = ButtonDefaults.buttonColors(Color.Blue)
    ) {
      Text(text = "Next", color = Color.White, fontSize = 16.sp)
    }
  }

  if (showWifiListDialog) {
    WifiListDialog(
      wifiList = scannedWifiList,
      loading = isScanning,
      onPick = { ssid ->
        appLogger.i("WifiConnect/onPick: ssid=$ssid, source=phoneScan")
        onWifiNameChange(ssid)
        showWifiListDialog = false
      },
      onDismiss = { showWifiListDialog = false }
    )
  }
}


@Composable
fun HBConfirmDialog(
  onDismissRequest: () -> Unit,
  onCancel: () -> Unit = {},
  onConfirm: () -> Unit,
  modifier: Modifier = Modifier
) {
  AlertDialog(
    onDismissRequest = onDismissRequest,
    properties = DialogProperties(usePlatformDefaultWidth = true),
    title = {
      Text(
        text = "Please add HomeBase first before connecting Camera to it.", textAlign = TextAlign.Center,
      )
    },
    text = null,
    shape = RoundedCornerShape(16.dp),
    buttons = {
      Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Button(
          onClick = onConfirm,
          colors = ButtonDefaults.buttonColors(Color.Blue),
          modifier = modifier.fillMaxWidth().padding(bottom = 16.dp),
          shape = RoundedCornerShape(16.dp)
        ) {
          Text("Add HomeBase", color = Color.White)
        }
        OutlinedButton(
          onClick = { onCancel() }, modifier = Modifier.fillMaxWidth()
        ) {
          Text("Cancel", color = Color.Gray)
        }
      }
    },
    backgroundColor = Color.White,
    contentColor = Color.Gray
  )
}