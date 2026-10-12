package com.anker.esiotkit.sample.main.mega.add_device.ble_connect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.domain.add_device.wifi.model.WifiNetwork
import com.anker.esiotkit.sample.main.mega.MegaMainScreen
import com.anker.esiotkit.sample.main.mega.add_device.MegaAddDeviceScreen
import com.anker.esiotkit.sample.support.permissions.rememberPermissionController
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.component.WifiListDialog
import com.anker.esiotkit.sample.ui.component.WifiSignalIcon
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.PermissionState
import dev.icerock.moko.permissions.compose.BindEffect
import org.publicvalue.multiplatform.qrcode.CodeType
import org.publicvalue.multiplatform.qrcode.ScannerWithPermissions
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_add_device_ble_connect_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/9/13
 */
class MegaBLEConnectScreen(var detailsItem: String) : BaseScreen() {

  override val title: StringResource get() = Res.string.eslot_add_device_ble_connect_title

  private val protocol: String = detailsItem.split("-").last()

  @Composable
  override fun Content() = BaseColumn {
    val navigator = LocalNavigator.currentOrThrow
    val viewModel = rememberScreenModel { MegaBLEConnectViewModel() }
    val uiState by viewModel.uiState.collectAsState()
    val controller = rememberPermissionController()
    BindEffect(controller)

    LaunchedEffect(Unit) {
      appLogger.i("MegaBLEConnectScreen/Content/init detailsItem:$detailsItem protocol:$protocol")
      val btScanState = controller.getPermissionState(Permission.BLUETOOTH_SCAN)
      val btConnectState = controller.getPermissionState(Permission.BLUETOOTH_CONNECT)
      val locationState = controller.getPermissionState(Permission.LOCATION)
      appLogger.i("MegaBLEConnectScreen/Content/permissionState: BT_SCAN=$btScanState, BT_CONNECT=$btConnectState, LOCATION=$locationState")
      if (btScanState != PermissionState.Granted) {
        appLogger.i("MegaBLEConnectScreen/Content/requestPermission: BLUETOOTH_SCAN")
        controller.providePermission(Permission.BLUETOOTH_SCAN)
      }
      if (btConnectState != PermissionState.Granted) {
        appLogger.i("MegaBLEConnectScreen/Content/requestPermission: BLUETOOTH_CONNECT")
        controller.providePermission(Permission.BLUETOOTH_CONNECT)
      }
      if (locationState != PermissionState.Granted) {
        appLogger.i("MegaBLEConnectScreen/Content/requestPermission: LOCATION (required for BLE scan on Android)")
        controller.providePermission(Permission.LOCATION)
      }
    }

    LaunchedEffect(
      uiState.isScanned, uiState.connection, uiState.connectionBLESuccess,
      uiState.connectionBLEFail, uiState.WiFiConnection, uiState.connectionWiFiSuccess,
      uiState.connectionWiFiFail, uiState.addSuccess
    ) {
      val pageState = when {
        uiState.connectionWiFiSuccess && uiState.addSuccess -> "DeviceAdded"
        uiState.connectionWiFiSuccess -> "NameDevice"
        uiState.connectionWiFiFail -> "WiFiFail"
        uiState.WiFiConnection -> "WiFiConnecting"
        uiState.connectionBLESuccess -> "BLEConnected"
        uiState.connectionBLEFail -> "BLEConnectFail"
        uiState.connection -> "BLEConnecting"
        uiState.isScanned -> "Scanned"
        else -> "Scanning"
      }
      appLogger.i("MegaBLEConnectScreen/Content/pageState:$pageState protocol:$protocol")
    }

    when {
      uiState.connectionWiFiSuccess -> {
        ConnectDevice(
          name = uiState.name,
          addSuccess = uiState.addSuccess,
          onNameChange = viewModel::changeName,
          onNextClick = {
          appLogger.i("MegaBLEConnectScreen/NameForm/onConfirmClick")
          viewModel.isAddSuccess(true)
        },
        )
      }
      uiState.connectionWiFiFail -> {
        ConnectWiFiFail(uiState.wifiFailMessage, onRetry = { viewModel.resetStates() })
      }
      uiState.WiFiConnection -> {
        ToConnect("Connecting... to wifi...")
      }
      uiState.connectionBLESuccess -> WifiConnect(
        uiState = uiState,
        onClick = {
          appLogger.i("MegaBLEConnectScreen/WifiConnect/onNextClick")
          viewModel.onWifiCredentialsSubmitted(uiState.wifiName, uiState.wifiPassword)
        },
        onNameChange = viewModel::onWifiNameChange,
        onPasswordChange = viewModel::onWifiPasswordChange,
        wifiList = uiState.wifiList,
        wifiListLoading = uiState.wifiListLoading,
        onPickWifi = viewModel::onWifiPicked
      )

      uiState.connectionBLEFail -> {
        ConnectFail(connectDevice = {
          appLogger.i("MegaBLEConnectScreen/ConnectFail/onRetryClick")
          viewModel.bluetoothConnect()
        })
      }
      uiState.connection -> {
        ToConnect("Connecting... to the device...")
      }
      uiState.isScanned -> AddDevicePage(
        reminderPage = uiState.reminderPage,
        changePage = { value -> viewModel.choiceReminderPage(value) },
        connectDevice = {
          appLogger.i("MegaBLEConnectScreen/AddDevicePage/onBluetoothEnabledClick")
          viewModel.bluetoothConnect()
        })
      else -> ScannerWithPermissions(
        onScanned = {
          appLogger.i("MegaBLEConnectScreen/Content/onScanned:code=$it protocol=$protocol")
          viewModel.onScanned(it, protocol) { isMatching ->
            appLogger.i("MegaBLEConnectScreen/Content/protocolMatch:isMatching=$isMatching")
            if (isMatching) {
              viewModel.protocolMatchSuccess(true)
            } else {
              emitToast("协议不匹配, 请选择正确配网协议！")
              viewModel.resetStates()
              navigator.push(MegaAddDeviceScreen)
            }
          }
          true
        }, types = CodeType.entries.toList()
      )
    }
    MegaLoading(viewModel.loading)
  }

  @Composable
  fun ConnectDevice(
    name: String, onNameChange: (String) -> Unit, onNextClick: () -> Unit, addSuccess: Boolean,
  ) {
    val navigator = LocalNavigator.currentOrThrow
    if (addSuccess) {
      ToConnect("Added successfully")
      CommonButton("Back") {
        navigator.popUntil { it == MegaMainScreen }
      }
    } else {
      NameForm(
        name = name, onNameChange = onNameChange, onNextClick = onNextClick
      )
    }
  }

  @Composable
  fun NameForm(
    name: String,
    onNameChange: (String) -> Unit,
    onNextClick: () -> Unit,
  ) {
    Column(
      modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5))
    ) {
      Text(
        text = "Give your HomeBase a name", fontSize = 16.sp, color = Color.Gray
      )
      TextField(
        value = name,
        onValueChange = onNameChange,
        placeholder = { Text(text = "Name", color = Color.Gray) },
        colors = TextFieldDefaults.textFieldColors(
          backgroundColor = Color.White,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
      )
      Spacer(modifier = Modifier.height(24.dp))
      Text(
        text = "*Please avoid using personal sensitive information", fontSize = 20.sp
      )
      Spacer(modifier = Modifier.weight(1f))
      Button(
        onClick = onNextClick,
        enabled = name.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(if (name.isNotEmpty()) Color(0xFF007AFF) else Color.Gray)
      ) {
        Text(text = "Confirm", color = Color.White, fontSize = 16.sp)
      }
    }
  }

  @Composable
  fun WifiConnect(
    uiState: MegaBLEConnectState,
    onClick: () -> Unit,
    onNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    wifiList: List<WifiNetwork>,
    wifiListLoading: Boolean,
    onPickWifi: (String) -> Unit,
    modifier: Modifier = Modifier
  ) {
    var showWifiListDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(Icons.Default.Home, contentDescription = null, modifier = modifier.size(128.dp))
      Text(text = "Please select a Wi-Fi network")
      OutlinedTextField(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        value = uiState.wifiName,
        onValueChange = onNameChange,
        label = { Text("name") },
        trailingIcon = {
          Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Select Wi-Fi",
            modifier = Modifier.clickable { showWifiListDialog = true }
          )
        },
      )

      OutlinedTextField(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        value = uiState.wifiPassword,
        onValueChange = onPasswordChange,
        label = { Text("Password") },
        trailingIcon = {
          Icon(
            Icons.Default.Home, contentDescription = "Toggle Password Visibility"
          )
        }
      )
      Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(16.dp).height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(Color.Blue)
      ) {
        Text(text = "Next", color = Color.White, fontSize = 16.sp)
      }
    }

    if (showWifiListDialog) {
      WifiListDialog(
        wifiList = wifiList,
        loading = wifiListLoading,
        onPick = { ssid ->
          onPickWifi(ssid)
          showWifiListDialog = false
        },
        onDismiss = { showWifiListDialog = false }
      )
    }
  }

  @Composable
  fun ConnectFail(modifier: Modifier = Modifier, connectDevice: () -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(Icons.Default.Home, contentDescription = null, modifier = modifier.size(128.dp))
      Spacer(modifier = modifier.height(24.dp))
      Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)
          .background(Color.White, shape = RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        Text(
          text = "Failed to connect to Bluetooth",
          fontSize = 18.sp,
          color = Color.Red,
          modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
          text = "1. Make sure your Wi-Fi Bridge is powered on.\n" + "2. Press the Sync button for 2s to activate the Bluetooth signal.\n" + "3. Check that the setup code has been entered correctly.",
          fontSize = 16.sp,
          color = Color(0xFF333333),
          textAlign = TextAlign.Center,
          lineHeight = 24.sp,
          modifier = Modifier.padding(bottom = 16.dp)
        )
      }
      Spacer(modifier = modifier.weight(1f))
      Button(
        onClick = {
          connectDevice()
        },
        modifier = modifier.fillMaxWidth().padding(16.dp).height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(Color.Blue)
      ) {
        Text(text = "Retry", color = Color.White, fontSize = 16.sp)
      }
    }
  }

  @Composable
  fun ConnectWiFiFail(message: String = "", onRetry: () -> Unit = {}) {
    val navigator = LocalNavigator.currentOrThrow
    appLogger.i("MegaBLEConnectScreen/ConnectWiFiFail/displayMessage:$message")
    Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        .background(Color.White, shape = RoundedCornerShape(16.dp)).padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Text(
        text = if (message.isNotEmpty()) message else "Connect wiFi fail",
        fontSize = 16.sp, textAlign = TextAlign.Center
      )
      Spacer(modifier = Modifier.height(16.dp))
      CommonButton("Retry") {
        appLogger.i("MegaBLEConnectScreen/ConnectWiFiFail/onRetryClick")
        onRetry()
      }
      Spacer(modifier = Modifier.height(8.dp))
      CommonButton("Back") {
        navigator.popUntil { it == MegaMainScreen }
      }
    }
  }

  @Composable
  fun ToConnect(text: String = "", modifier: Modifier = Modifier) {
    Column(
      modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)
        .background(Color.White, shape = RoundedCornerShape(16.dp)).padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Text(
        text = text, fontSize = 20.sp, textAlign = TextAlign.Center
      )
    }
  }

  @Composable
  fun AddDevicePage(
    reminderPage: Int, changePage: (Int) -> Unit, connectDevice: () -> Unit
  ) {
    when (reminderPage) {
      0 -> {
        reminderPage(text = "Press the Sync button for 2 seconds until the LED blinks green.",
                     changePage = { changePage(1) })
      }
      else -> {
        reminderPage(
          text = "Enable your Bluetooth\n" + "Go to your phone's Bluetooth and enable it.",
          changePage = { connectDevice() },
          bottomText = "I've turned on Bluetooth"
        )
      }
    }
  }

  @Composable
  fun reminderPage(
    text: String = "", changePage: () -> Unit, modifier: Modifier = Modifier, bottomText: String = "Next"
  ) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(Icons.Default.Home, contentDescription = null, modifier = modifier.size(128.dp))
      Spacer(modifier = modifier.height(24.dp))
      Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)
          .background(Color.White, shape = RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        Text(
          text = text, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 24.sp
        )
      }
      Spacer(modifier = modifier.weight(1f))
      Button(
        onClick = {
          appLogger.i("MegaBLEConnectScreen/reminderPage/onClick:${bottomText}")
          changePage()
        },
        modifier = modifier.fillMaxWidth().padding(16.dp).height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(Color.Blue)
      ) {
        Text(text = bottomText, color = Color.White, fontSize = 20.sp)
      }
    }
  }
}
