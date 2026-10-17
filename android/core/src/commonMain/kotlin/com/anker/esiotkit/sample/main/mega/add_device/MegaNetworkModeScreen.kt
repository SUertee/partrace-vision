package com.anker.esiotkit.sample.main.mega.add_device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_add_device_network_mode_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/9/9
 */
object MegaNetworkModeScreen : BaseScreen() {

  override val title: StringResource get() = Res.string.eslot_add_device_network_mode_title

  @Composable
  override fun Content() {

    val navigator = LocalNavigator.currentOrThrow

    ProductListScreen(ModeItems = ModeType.values().toList(), onItemClick = { ModeItem ->
      navigator.push(MegaModeDetailScreen(ModeItem = ModeItem))
    })
  }

  data class ModeDetail(val type: String, val image: ImageVector)

  enum class ModeType(val type: String, val image: ImageVector, val details: List<ModeDetail>) {
    Security(
      "Security", Icons.Filled.Home,
      listOf(
        ModeDetail("solo模式 有线 Cable", Icons.Filled.Favorite),
        ModeDetail("solo模式 二维码 QRCode", Icons.Filled.Favorite),
        ModeDetail("solo模式 蓝牙 BLE", Icons.Filled.Favorite),
        ModeDetail("solo模式 socket SoftAP", Icons.Filled.Favorite),
        ModeDetail("solo模式 设备插卡 4G", Icons.Filled.Favorite),
        ModeDetail("子设备 二维码 +QRCode", Icons.Filled.Favorite),
        ModeDetail("子设备 声波 +SoundWave", Icons.Filled.Favorite),
        ModeDetail("子设备 sub1G +Sub1G", Icons.Filled.Favorite),
        ModeDetail("子设备 一键切换 +P2P", Icons.Filled.Favorite),
        ModeDetail("子设备 以蓝牙方式做子设备 +BLE", Icons.Filled.Favorite),
        ModeDetail("子设备 一键入网 +.", Icons.Filled.Favorite),
      ),
    ),
    Clean(
      "Clean", Icons.Filled.Star,
      listOf(
        ModeDetail("蓝牙配网 AloT设备 BLE", Icons.Filled.Favorite),
        ModeDetail("蓝牙配网 x9蓝牙设备 BLE", Icons.Filled.Favorite),
        ModeDetail("蓝牙配网 涂鸦SDK蓝牙配网 BLE", Icons.Filled.Favorite),
        ModeDetail("蓝牙配网 涂鸦ble BLE", Icons.Filled.Favorite),
        ModeDetail("蓝牙配网 智新协议 BLE", Icons.Filled.Favorite),
        ModeDetail("蓝牙配网 x8蓝牙设备 BLE", Icons.Filled.Favorite),
        ModeDetail("AP配网 AloT设备 SoftAP", Icons.Filled.Favorite),
        ModeDetail("AP配网 涂鸦SDKAP配网 SoftAP", Icons.Filled.Favorite),
        ModeDetail("AP配网 自研AP SoftAP", Icons.Filled.Favorite),
        ModeDetail("AP配网 扫码 SoftAP", Icons.Filled.Favorite),
      ),
    )
  }

  @Composable
  fun ProductListScreen(
    ModeItems: List<ModeType>, onItemClick: (ModeType) -> Unit, modifier: Modifier = Modifier
  ) {
    Column(
      modifier = modifier.fillMaxSize()
    ) {
      LazyColumn {
        items(ModeItems) { ModeItem ->
          ModeItemCard(ModeItem = ModeItem, onClick = { onItemClick(ModeItem) })
        }
      }
    }
  }

  @Composable
  fun ModeItemCard(ModeItem: ModeType, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)
        .background(Color.White, shape = RoundedCornerShape(16.dp)).clickable { onClick() }.padding(16.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = ModeItem.image, contentDescription = ModeItem.type, modifier = modifier.size(32.dp)
        )
        Text(
          text = ModeItem.type,
          fontSize = 20.sp,
          color = Color.Black,
          modifier = modifier.padding(start = 16.dp)
        )
      }
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = "Forward",
        tint = Color.Blue
      )

    }
  }
}


