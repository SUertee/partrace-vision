package com.anker.esiotkit.sample.main.mega.add_device.sub_device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.AlertDialog
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.sample.main.mega.MegaMainScreen
import com.anker.esiotkit.sample.main.mega.MegaMainViewModel
import com.anker.esiotkit.sample.main.mega.add_device.MegaAddDeviceScreen
import com.anker.esiotkit.sample.support.rememberThingPropertyValue
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import org.publicvalue.multiplatform.qrcode.CodeType
import org.publicvalue.multiplatform.qrcode.ScannerWithPermissions
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_add_device_sub_device_connect_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/9/13
 */
class MegaSubDeviceConnectScreen(var detailsItem: String) : BaseScreen() {

  override val title: StringResource get() = Res.string.eslot_add_device_sub_device_connect_title
  private val protocol: String = detailsItem.split("-").last()
  private var resetViewModelFlag by mutableStateOf(false)
  override fun onBackClick() {

    // 设置标志以指示需要重置 ViewModel
    resetViewModelFlag = true
    super.onBackClick()
  }

  @Composable
  override fun Content() = BaseColumn {
    val viewMainModel = rememberScreenModel { MegaMainViewModel() }
    val viewModel = rememberScreenModel { MegaSubDeviceViewModel(megaMainViewModel = viewMainModel) }
    val uiState by viewModel.uiState.collectAsState()
    val navigator = LocalNavigator.currentOrThrow


    if (resetViewModelFlag) {
      LaunchedEffect(Unit) {
        viewModel.resetStates()
        resetViewModelFlag = false
      }
    }
    when {
      uiState.connectionSuccess -> connectionSuccess()
      uiState.connectionFail -> {
        ConnectFail(connectDevice = { viewModel.connectDoorbell() })
      }
      uiState.connection -> {
        ToConnect("Wi-Fi Doorbell Chime is emitting sound wave to discover new battery doorbell")
      }
      uiState.connectDoorbell -> {
        ConnectDoorbellPage("Press the SYNC button for 2 seconds until you hear a Beep.",
                            onClick = { viewModel.connectDoorbell() })
      }
      uiState.isScanned -> ChooseHomeBase(
        homeBases = uiState.homeBaseList,
        onClick = { homeBase -> viewModel.onHomeBaseSelected(homeBase) },
        showDialog = uiState.showDialog,
        changeShowDialog = { viewModel.changeShowDialog(it) })
      else -> ScannerWithPermissions(
        onScanned = {
          viewModel.onScanned(it,protocol) { isMatching ->
            if (isMatching) {
              viewModel.protocolMatchSuccess()
            } else {
              emitToast("协议不匹配, 请选择正确配网协议！")
              viewModel.resetStates()
              navigator.push(MegaAddDeviceScreen)
            }
          }
          appLogger.i("MegaSubDeviceConnectScreen/Content/onScanned:$it")
          true
        }, types = CodeType.entries.toList())
    }
    MegaLoading(viewModel.loading)
  }

  @Composable
  private fun connectionSuccess() {
    val navigator = LocalNavigator.currentOrThrow
    Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        .background(Color.White, shape = RoundedCornerShape(16.dp)).padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Text(
        text = "Connect Success", fontSize = 20.sp, textAlign = TextAlign.Center
      )
      CommonButton("Back") {
        navigator.popUntil { it == MegaMainScreen }
      }
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
          text = "Unable to add doorbell.",
          fontSize = 18.sp,
          color = Color.Red,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )
        Text(
          text = "1. Check if your Wi-Fi Doorbell Chime 2 has internet access.\n" + "2. Check if you hear a Beep and the doorbell ring lights up. If not, charge it via the MicroUSB port and retry.\n" + "3. Keep the Doorbell close to Wi-Fi Doorbell Chime.",
          fontSize = 16.sp,
          color = Color(0xFF333333),
          textAlign = TextAlign.Start,
          lineHeight = 24.sp,
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
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
  fun ChooseHomeBase(
    homeBases: List<MegaDevice>,
    onClick: (MegaDevice) -> Unit,
    showDialog: Boolean,
    changeShowDialog: (Boolean) -> Unit
  ) {

    if (showDialog) {
      AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = true),
        title = {
          Text(
            text = "HomeBase is offline",
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
          )
        },
        text = null,
        shape = RoundedCornerShape(16.dp),
        buttons = {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Button(
              onClick = { changeShowDialog(false) },
              colors = ButtonDefaults.buttonColors(Color.Blue),
              modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
              shape = RoundedCornerShape(16.dp)
            ) {
              Text("OK", color = Color.White)
            }
          }
        },
        backgroundColor = Color.White
      )
    }
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
      Text(
        text = "Select a HomeBase for doorbell",
        fontSize = 16.sp,
        color = Color.Gray,
        modifier = Modifier.padding(bottom = 16.dp)
      )
      LazyColumn(
        modifier = Modifier.background(shape = RoundedCornerShape(16.dp), color = Color.White)
          .padding(horizontal = 16.dp)
      ) {
        items(homeBases) { homeBase ->
          if (homeBase.deviceSn == homeBase.parentSn) {
            HorizontalDivider(modifier = Modifier.fillMaxWidth())
          }
          HomeBaseItem(homeBase = homeBase, onClick = {
            onClick(homeBase)
          }, showDiaLog = { changeShowDialog(true) })
          Divider(color = Color.LightGray, thickness = 1.dp)

        }
      }

    }
  }

  @Composable
  fun HomeBaseItem(
    homeBase: MegaDevice, onClick: () -> Unit, showDiaLog: () -> Unit
  ) {
    val isOnline = homeBase.rememberThingPropertyValue("device_is_online").asBoolean()
    val onlineStatus = remember(isOnline) {
      if (isOnline) "Online" else "Offline"
    }
    Row(modifier = Modifier.fillMaxWidth().clickable { if (isOnline) onClick() else showDiaLog() }
      .padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      Column {
        androidx.compose.material3.Text(text = "Name: ${homeBase.iotDevice.deviceName}")
        androidx.compose.material3.Text(
          text = "SN: ${homeBase.deviceSn}",
          modifier = Modifier.padding(top = 4.dp),
        )
      }
      Spacer(modifier = Modifier.weight(1f))
      Text(
        text = onlineStatus, fontSize = 16.sp, color = Color(0xFF007AFF), textAlign = TextAlign.End
      )
      Spacer(modifier = Modifier.width(8.dp))
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Arrow", tint = Color(0xFF007AFF)
      )
    }
  }

  @Composable
  fun ConnectDoorbellPage(
    text: String = "", onClick: () -> Unit, modifier: Modifier = Modifier, bottonText: String = "Next"
  ) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
      // 顶部图像和标题
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
          onClick()
        },
        modifier = modifier.fillMaxWidth().padding(16.dp).height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(Color.Blue)
      ) {
        Text(text = bottonText, color = Color.White, fontSize = 20.sp)
      }
    }
  }
}
