package com.anker.esiotkit.sample.main.mega.add_device.device_family

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.main.mega.add_device.ble_connect.MegaBLEConnectScreen
import com.anker.esiotkit.sample.main.mega.add_device.cable_connect.MegaCableConnectScreen
import com.anker.esiotkit.sample.main.mega.add_device.scan_qrcode.MegaScanQRCodeScreen
import com.anker.esiotkit.sample.main.mega.add_device.sub_device.MegaSubDeviceConnectScreen
import com.anker.esiotkit.security_device.pair.DevicePairType
import com.anker.esiotkit.security_device.pair.SecurityDevicePairConfig
import com.anker.esiotkit.support.log.appLogger

/**
 * @version 2024/9/29
 */
object MegaPairingTypePage {

  @Composable
  fun Content() {

    val detailsItems = listOf(
      DevicePairType.SECURITY_CABLE,
      DevicePairType.SECURITY_QRCODE,
      DevicePairType.SECURITY_BLE,
      DevicePairType.SECURITY_SOFT_AP,
      DevicePairType.SECURITY_4G,
      DevicePairType.SUB_QRCODE,
      DevicePairType.SUB_BLE,
      DevicePairType.SUB_SOUND_WAVE,
      DevicePairType.SUB_SUB1G,
      DevicePairType.SUB_P2P,
      DevicePairType.CLEAN_ALOT_BLE,
      DevicePairType.CLEAN_X9_BLE,
      DevicePairType.CLEAN_TUYA_SDK_BLE,
      DevicePairType.CLEAN_TUYA_BLE,
      DevicePairType.CLEAN_SMART_NEW_BLE,
      DevicePairType.CLEAN_X8_BLE,
      DevicePairType.CLEAN_ALOT_SOFT_AP,
      DevicePairType.CLEAN_TUYA_SDK_SOFT_AP,
      DevicePairType.CLEAN_SELF_DEVELOPED_SOFT_AP,
      DevicePairType.CLEAN_SCAN_SOFT_AP
    )
    val navigator = LocalNavigator.currentOrThrow

    DeviceDetailList(detailsItems = detailsItems, onItemClick = { detailsItem ->
      appLogger.i("MegaPairingTypePage/onItemClick/detailsItem:$detailsItem")
      when (detailsItem.split("-").last()) {
        SecurityDevicePairConfig.SECURITY_CABLE -> navigator.push(MegaCableConnectScreen(detailsItem = detailsItem))
        SecurityDevicePairConfig.SECURITY_QRCODE -> navigator.push(MegaScanQRCodeScreen(detailsItem = detailsItem))
        SecurityDevicePairConfig.SECURITY_BLE -> navigator.push(MegaBLEConnectScreen(detailsItem = detailsItem))
        SecurityDevicePairConfig.SECURITY_SUB_SOUND_WAVE -> navigator.push(MegaSubDeviceConnectScreen(detailsItem = detailsItem))
        SecurityDevicePairConfig.SECURITY_SUB_P2P -> navigator.push(MegaSubDeviceConnectScreen(detailsItem = detailsItem))
        else -> {}
      }
    })
  }
}

@Composable
fun DeviceDetailList(
  detailsItems: List<String>,
  onItemClick: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier.fillMaxSize()
  ) {
    LazyColumn {
      items(detailsItems) { detailsItem ->
        DetailsItemCard(detailsItem = detailsItem, onClick = { onItemClick(detailsItem) })
      }
    }
  }
}

@Composable
fun DetailsItemCard(
  detailsItem: String, onClick: () -> Unit, modifier: Modifier = Modifier
) {
  HorizontalDivider(modifier = Modifier.fillMaxWidth())
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
    modifier = modifier.fillMaxWidth()
      .background(Color.White).clickable { onClick() }.padding(16.dp),
  ) {
    Text(
      text = detailsItem,
      fontSize = 16.sp,
      color = Color.Black
    )

  }
}

