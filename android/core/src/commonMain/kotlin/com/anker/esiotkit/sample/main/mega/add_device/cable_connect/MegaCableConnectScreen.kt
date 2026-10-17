package com.anker.esiotkit.sample.main.mega.add_device.cable_connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.main.mega.MegaMainScreen
import com.anker.esiotkit.sample.main.mega.add_device.MegaAddDeviceScreen
import com.anker.esiotkit.sample.ui.component.MegaLoading

import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.sample.view.emitToast
import org.publicvalue.multiplatform.qrcode.CodeType
import org.publicvalue.multiplatform.qrcode.ScannerWithPermissions
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_add_device_cable_connect_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/11/06
 */
class MegaCableConnectScreen(var detailsItem: String) : BaseScreen() {

  override val title: StringResource get() = Res.string.eslot_add_device_cable_connect_title

  private var resetViewModelFlag by mutableStateOf(false)
  private val protocol: String = detailsItem.split("-").last()
  override fun onBackClick() {
    appLogger.i("MegaCableConnectScreen/onBackClick")
    resetViewModelFlag = true
    super.onBackClick()
  }

  @Composable
  override fun Content() = BaseColumn {
    val navigator = LocalNavigator.currentOrThrow
    val viewModel = rememberScreenModel { MegaCableConnectViewModel() }
    val uiState by viewModel.uiState.collectAsState()

    if (resetViewModelFlag) {
      LaunchedEffect(Unit) {
        viewModel.resetStates()
        resetViewModelFlag = false
      }
    }
    when {
      uiState.connectionSuccess -> {
        appLogger.i("MegaCableConnectScreen/state: connectionSuccess")
        ConnectSuccess()
      }
      uiState.connectionFail -> {
        appLogger.i("MegaCableConnectScreen/state: connectionFail, sn:${uiState.sn}")
        ConnectFail(
          retryConnect = {
            appLogger.i("MegaCableConnectScreen/retryConnect clicked")
            viewModel.preScanQRCode(true)
          },
          cancelConnect = {
            appLogger.i("MegaCableConnectScreen/cancelConnect clicked")
            navigator.popUntil { it == MegaMainScreen }
          })
      }
      uiState.isScanned -> {
        appLogger.i("MegaCableConnectScreen/state: isScanned (waiting for SYNC/ALARM OFF button)")
        reminderPage(
          text = "When HomeBase emits a sound, click the SYNC/ALARM OFF button on the back.",
          changePage = {})
      }
      uiState.preScanned -> {
        appLogger.i("MegaCableConnectScreen/state: preScanned (scanner active), protocol:$protocol")
        ScannerWithPermissions(
          onScanned = {
            appLogger.i("MegaCableConnectScreen/onScanned/code:$it, protocol:$protocol")
            viewModel.onScanned(it, protocol) { isMatching ->
              appLogger.i("MegaCableConnectScreen/onScanned/protocolMatchResult:$isMatching")
              if (isMatching) {
                appLogger.i("MegaCableConnectScreen/protocol matched, starting performPairing")
                viewModel.performPairing()
              } else {
                appLogger.w("MegaCableConnectScreen/protocol mismatch, navigating back")
                emitToast("Protocol mismatch, please select the correct pairing protocol!")
                viewModel.resetStates()
                navigator.push(MegaAddDeviceScreen)
              }
            }
            true
          }, types = CodeType.entries.toList()
        )
      }
      else -> {
        appLogger.i("MegaCableConnectScreen/state: initial (waiting for LED blue)")
        reminderPage(
          text = "Wait 1 minute until HomeBase's LED turns from RED to BLUE.",
          changePage = {
            appLogger.i("MegaCableConnectScreen/Next clicked, starting preScanQRCode")
            viewModel.preScanQRCode(true)
          }, bottomText = "Next"
        )
      }
    }
    MegaLoading(viewModel.loading)
  }

  @Composable
  fun ConnectSuccess(
  ) {
    val navigator = LocalNavigator.currentOrThrow
    Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        .background(Color.White, shape = RoundedCornerShape(16.dp)).padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Text(
        text = "Added successfully", fontSize = 20.sp, textAlign = TextAlign.Center
      )
      CommonButton("Back") {
        navigator.popUntil { it == MegaMainScreen }
      }
    }
  }

  @Composable
  fun ConnectFail(modifier: Modifier = Modifier, retryConnect: () -> Unit, cancelConnect: () -> Unit) {
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
          text = "Unable to add HomeBase.",
          fontSize = 18.sp,
          color = Color.Red,
          modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
          text = "1. Make sure the Ethernet cable is plugged into HomeBase and the LED is blue.\n" +
          "2. Make sure you have scanned the QR code on the bottom of HomeBase.\n" +
          "3. Make sure you have pressed the SYNC/ALARM OFF button when you hear the voice prompts.",
          fontSize = 16.sp,
          color = Color(0xFF333333),
          textAlign = TextAlign.Center,
          lineHeight = 24.sp,
          modifier = Modifier.padding(bottom = 16.dp)
        )
      }
      Spacer(modifier = modifier.weight(1f))
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = {
            cancelConnect()
          },
          modifier = modifier.fillMaxWidth().padding(16.dp).height(48.dp),
          shape = RoundedCornerShape(24.dp),
          colors = ButtonDefaults.buttonColors(Color.White)
        ) {
          Text(text = "Cancel", fontSize = 16.sp)
        }
        Button(
          onClick = {
            retryConnect()
          },
          modifier = modifier.fillMaxWidth().padding(16.dp).height(48.dp),
          shape = RoundedCornerShape(24.dp),
          colors = ButtonDefaults.buttonColors(Color.Blue)
        ) {
          Text(text = "Retry Again", color = Color.White, fontSize = 16.sp)
        }
      }
    }
  }

  @Composable
  fun reminderPage(
    text: String = "", changePage: () -> Unit, modifier: Modifier = Modifier, bottomText: String? = null
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
      if (bottomText != null) {
        Button(
          onClick = {
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
}

