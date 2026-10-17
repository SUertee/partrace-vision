package com.anker.esiotkit.sample.main.mega.device_settings.settings_composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.anker.esiotkit.core.entities.device.ESIotDevice
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.sample.data.ProductInfoManager
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * @version 2025/8/19
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubDeviceIconsComposable(megaDevice: MegaDevice, modifier: Modifier) {
  val subDevices = remember { MegaDeviceManager.getDeviceListByParent(megaDevice.deviceSn) }
  if (subDevices.isNotEmpty()) {
    FlowRow(modifier = modifier) {
      subDevices.forEach { subDevice ->
        SubDeviceIcon(subDevice)
      }
    }
  }
}

@Composable
private fun SubDeviceIcon(megaDevice: MegaDevice) {
  val productDescription by remember {
    derivedStateOf {
      ProductInfoManager.securityProductInfo.find { it.categoryCode == megaDevice.devicePn || it.code == megaDevice.devicePn }
    }
  }
  Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    productDescription?.let {
      AsyncImage(
        model = it.icon,
        contentDescription = it.name,
        modifier = Modifier.size(64.dp),
        contentScale = ContentScale.Crop
      )
    }
    BatteryValueComposable(
      megaDevice = megaDevice,
      modifier = Modifier
    )
    Text(
      text = megaDevice.iotDevice.deviceSn,
      fontSize = 12.sp,
      modifier = Modifier.width(80.dp)
    )
  }
}

@Preview
@Composable
fun SubDeviceIconPreview() {
  SubDeviceIcon(
    megaDevice = MegaDevice(
      iotDevice = ESIotDevice(
        deviceSn = "1234567890",
        devicePn = "security_camera",
        parentSn = "111111",
      )
    )
  )
}