package com.anker.esiotkit.sample.main.mega.add_device.scan_qrcode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.anker.esiotkit.core.constants.ESResult
import com.anker.esiotkit.sample.main.mega.MegaMainScreen
import com.anker.esiotkit.sample.main.mega.add_device.MegaAddDeviceScreen
import com.anker.esiotkit.sample.main.mega.device_settings.general.WiFiSignalScreen
import com.anker.esiotkit.sample.support.isDebugBuild
import com.anker.esiotkit.sample.support.platform.isAndroidPlatform
import com.anker.esiotkit.sample.ui.component.HBConfirmDialog
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.component.WifiConnect
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.sample.view.toastNotSupport
import com.anker.esiotkit.support.log.appLogger
import org.publicvalue.multiplatform.qrcode.CodeType
import org.publicvalue.multiplatform.qrcode.ScannerWithPermissions
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_add_device_scan_qrcode_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/9/11
 */
class MegaScanQRCodeScreen(
  val detailsItem: String,
  val wifiName: String? = null,
  val wifiPassword: String? = null,
  val deviceSn: String? = null,
) : BaseScreen() {

  override val title: StringResource get() = Res.string.eslot_add_device_scan_qrcode_title
  val protocol: String = detailsItem.split("-").last()
  private var resetViewModelFlag = false

  override fun onBackClick() {
    resetViewModelFlag = true
    super.onBackClick()
  }

  @Composable
  override fun Content() = BaseColumn {
    val viewModel = rememberScreenModel { MegaScanQrCodeViewModel() }
    val uiState by viewModel.uiState.collectAsState()
    val navigator = LocalNavigator.currentOrThrow

    if (resetViewModelFlag) {
      LaunchedEffect(Unit) {
        viewModel.resetStates()
        resetViewModelFlag = false
      }
    }
    when {
      uiState.connectionSuccess -> PairingProgressPage(
        success = true,
        uiState = uiState,
        onBackClick = {
          if (wifiName != null) {
            navigator.popUntil { screen ->
              screen is WiFiSignalScreen
            }
          } else {
            navigator.popUntil { it == MegaMainScreen }
          }
        }
      )
      uiState.connectionFailure -> PairingProgressPage(
        success = false,
        uiState = uiState,
        onBackClick = {
          if (wifiName != null) {
            navigator.popUntil { screen ->
              screen is WiFiSignalScreen
            }
          } else {
            navigator.popUntil { it == MegaMainScreen }
          }
        }
      )
      uiState.connection -> PairingProgressPage(
        success = null,
        uiState = uiState,
        onBackClick = {}
      )
      uiState.imageByteArray != null -> QrCodeImage(
        imageByteArray = uiState.imageByteArray,
        onNextClick = { viewModel.performPairing() }
      )
      wifiName != null -> {
        LaunchedEffect(wifiName, deviceSn) {
          appLogger.i("MegaScanQRCodeScreen/enter/wifiChange: deviceSn=$deviceSn, hasWifiName=${wifiName != null}, hasPassword=${wifiPassword != null}")
          viewModel.generateAndShowQrCode(wifiName, wifiPassword, deviceSn)
        }
      }
      uiState.isScanned -> WifiCredentialsPage(
        connectType = uiState.connectType,
        wifiName = uiState.wifiName,
        wifiPassword = uiState.wifiPassword,
        onWifiNameChange = viewModel::onWifiNameChange,
        onWifiPasswordChange = viewModel::onWifiPasswordChange,
        onConnectTypeClick = { type ->
          appLogger.i("MegaScanQRCodeScreen/WifiCredentialsPage/onConnectTypeClick type:$type, sn:${uiState.sn}")
          viewModel.choiceConnectType(type)
        },
        onConfirm = {
          appLogger.i("MegaScanQRCodeScreen/WifiCredentialsPage/HomeBase/onConfirmClicked Clicked Add HomeBase, sn:${uiState.sn}, connectType:${uiState.connectType}, mainTypes:${uiState.mainTypes.map { it.type }}, detailsItem:$detailsItem")
          viewModel.choiceConnectType(-1)
          toastNotSupport()
          // viewModel.resetStates()
          // navigator.push(MegaMainScreen)
        },
        onCancel = {
          appLogger.i("MegaScanQRCodeScreen/WifiCredentialsPage/HomeBase/onCancelClicked Canceled HomeBase, sn:${uiState.sn}")
          viewModel.choiceConnectType(-1)
        },
        onSubmitWifiCredentials = { name, password ->
          appLogger.i("MegaScanQRCodeScreen/WifiCredentialsPage/onSubmitWifiCredentials name:$name, protocol:$protocol, sn:${uiState.sn}, connectType:${uiState.connectType}")
          viewModel.onWifiCredentialsSubmitted(name, password, protocol) { isMatching ->
            if (isMatching) {
              viewModel.generateAndShowQrCode()
            } else {
              emitToast("协议不匹配, 请选择正确配网协议！")
              viewModel.resetStates()
              navigator.popUntil { it == MegaAddDeviceScreen }
            }
          }
        }
      )
      else -> ScannerWithPermissions(
        onScanned = {
          appLogger.i("MegaScanQRCodeScreen/Content/onScanned code:$it, protocol:$protocol, detailsItem:$detailsItem")
          viewModel.onScanned(it)
          true
        }, types = CodeType.entries.toList()
      )
    }
    MegaLoading(viewModel.loading)
  }

  @Composable
  fun PairingProgressPage(
    success: Boolean?, // null = connecting, true = success, false = failure
    uiState: MegaQrCodeState,
    onBackClick: () -> Unit
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)
        .background(Color.White, shape = RoundedCornerShape(16.dp))
        .padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // 结果标题：保留原有文案，置顶
      val headerText = when (success) {
        true -> "Bind Success"
        false -> "Bind Failure"
        null -> "配网进行中…"
      }
      Text(
        text = headerText,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = when (success) {
          true -> Color(0xFF34C759)
          false -> Color(0xFFFF3B30)
          null -> Color.Black
        }
      )

      // 失败详情：置于时间轴上方（仅 debug 构建）
      if (success == false && isDebugBuild && (uiState.failureCode != null || uiState.failurePayload != null)) {
        Text(
          text = "code=${uiState.failureCode}, payload=${uiState.failurePayload}",
          fontSize = 12.sp,
          textAlign = TextAlign.Center,
          color = Color.Gray,
          modifier = Modifier.padding(top = 4.dp)
        )
      }

      // Timeline
      PairingTimeline(
        nodes = uiState.timelineNodes,
        modifier = Modifier.weight(1f, fill = false)
      )

      // Back button (only show when finished)
      if (success != null) {
        CommonButton("Back") {
          onBackClick()
        }
      }
    }
  }

  @Composable
  fun PairingTimeline(
    nodes: List<PairingTimelineNode>,
    modifier: Modifier = Modifier
  ) {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
      initialValue = 1f,
      targetValue = 0.25f,
      animationSpec = infiniteRepeatable(
        animation = tween(800),
        repeatMode = RepeatMode.Reverse
      )
    )
    val scale by infiniteTransition.animateFloat(
      initialValue = 0.8f,
      targetValue = 1.2f,
      animationSpec = infiniteRepeatable(
        animation = tween(800),
        repeatMode = RepeatMode.Reverse
      )
    )

    LazyColumn(
      modifier = modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      itemsIndexed(nodes) { index, node ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
          verticalAlignment = Alignment.Top
        ) {
          // Left side: vertical timeline rail
          Box(
            modifier = Modifier
              .width(32.dp)
              .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter
          ) {
            // Draw rail line
            Canvas(modifier = Modifier.fillMaxHeight()) {
              val startY = if (index == 0) 10.dp.toPx() else 0f
              val endY = if (index == nodes.size - 1) 10.dp.toPx() else size.height
              val lineColor = if (node.status == PairingNodeStatus.SUCCESS) Color(0xFF34C759) else Color.LightGray
              drawLine(
                color = lineColor,
                start = Offset(size.width / 2, startY),
                end = Offset(size.width / 2, endY),
                strokeWidth = 2.dp.toPx()
              )
            }

            // Draw dot
            when (node.status) {
              PairingNodeStatus.SUCCESS -> {
                Box(
                  modifier = Modifier
                    .padding(top = 4.dp)
                    .size(12.dp)
                    .background(Color(0xFF34C759), shape = RoundedCornerShape(6.dp))
                )
              }
              PairingNodeStatus.FAILED -> {
                Box(
                  modifier = Modifier
                    .padding(top = 4.dp)
                    .size(12.dp)
                    .background(Color(0xFFFF3B30), shape = RoundedCornerShape(6.dp))
                )
              }
              PairingNodeStatus.RUNNING -> {
                Box(
                  modifier = Modifier
                    .padding(top = 4.dp)
                    .size(12.dp)
                    .scale(scale)
                    .alpha(alpha)
                    .background(Color(0xFF34C759), shape = RoundedCornerShape(6.dp))
                )
              }
            }
          }

          // Right side: node content
          Column(
            modifier = Modifier
              .weight(1f)
              .padding(start = 8.dp, end = 8.dp, bottom = 16.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = node.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
              )
              val timeStr = try {
                val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(node.timestampMs)
                val localDateTime = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                val hour = localDateTime.hour.toString().padStart(2, '0')
                val minute = localDateTime.minute.toString().padStart(2, '0')
                val second = localDateTime.second.toString().padStart(2, '0')
                "$hour:$minute:$second"
              } catch (e: Exception) {
                ""
              }
              Text(
                text = timeStr,
                fontSize = 12.sp,
                color = Color.Gray
              )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            node.details.forEach { detail ->
              Text(
                text = detail,
                fontSize = 12.sp,
                color = Color.DarkGray,
                modifier = Modifier.padding(vertical = 2.dp)
              )
            }
          }
        }
      }
    }
  }

  @Composable
  fun QrCodeImage(imageByteArray: ByteArray?, onNextClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
      modifier = modifier
        .fillMaxWidth()
        .padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = "Scan the QR code to connect to the device",
        style = TextStyle(fontSize = 20.sp, textAlign = TextAlign.Center)
      )
      Spacer(modifier = modifier.height(24.dp))
      AsyncImage(
        model = imageByteArray,
        contentDescription = "QR Code",
        modifier = Modifier.fillMaxWidth().weight(1f).aspectRatio(1f),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,  // 确保二维码清晰显示
      )
      Button(
        onClick = onNextClick,
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
  }

  @Composable
  fun WifiCredentialsPage(
    connectType: Int,
    wifiName: String,
    wifiPassword: String,
    onWifiNameChange: (String) -> Unit,
    onWifiPasswordChange: (String) -> Unit,
    onConnectTypeClick: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onSubmitWifiCredentials: (String, String) -> Unit
  ) {
    when (connectType) {
      0 -> {
        WifiConnect(
          wifiName = wifiName,
          wifiPassword = wifiPassword,
          onWifiNameChange = onWifiNameChange,
          onWifiPasswordChange = onWifiPasswordChange,
          onSubmit = onSubmitWifiCredentials,
          showScanPicker = isAndroidPlatform
        )
      }
      1 -> {
        HBConfirmDialog(
          onDismissRequest = {}, onConfirm = onConfirm, onCancel = onCancel
        )
      }
      else -> {
        ConnectType(onClick = onConnectTypeClick)
      }
    }
  }

  @Composable
  fun ConnectType(onClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
      Text(text = "Connect your device")
      Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 16.dp).clickable { onClick(0) }
          .background(Color.White, shape = RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(text = "Connect to Router", fontSize = 20.sp, fontWeight = FontWeight.Bold)
      }
      Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 16.dp).clickable { onClick(1) }
          .background(Color.White, shape = RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(text = "Connect to HomeBase", fontSize = 20.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}