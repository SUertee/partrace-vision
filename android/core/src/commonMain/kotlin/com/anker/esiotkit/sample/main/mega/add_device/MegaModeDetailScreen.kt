package com.anker.esiotkit.sample.main.mega.add_device

import kotlinx.serialization.Transient

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
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_add_device_mode_family_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/9/9
 */
class MegaModeDetailScreen(@Transient val ModeItem: MegaNetworkModeScreen.ModeType) : BaseScreen() {
  override val title: StringResource get() = Res.string.eslot_add_device_mode_family_title

  @Composable
  override fun Content() {

    val navigator = LocalNavigator.currentOrThrow

    DeviceDetailScreen(detailsItems = ModeItem.details, onItemClick = { detailsItem ->
      when (detailsItem.type.split(" ").last()) {
        // SecurityDevicePairConfig.SECURITY_CABLE -> {}
        // SecurityDevicePairConfig.SECURITY_QRCODE -> navigator.push(MegaScanQRCodeScreen(detailsItem = detailsItem))
        // SecurityDevicePairConfig.SECURITY_BLE -> navigator.push(MegaBLEConnectScreen(detailsItem = detailsItem))
        // SecurityDevicePairConfig.SECURITY_SUB_SOUND_WAVE -> navigator.push(MegaSubDeviceConnectScreen())
        // else -> {}
      }
    })
  }

  @Composable
  fun DeviceDetailScreen(
    detailsItems: List<MegaNetworkModeScreen.ModeDetail>,
    onItemClick: (MegaNetworkModeScreen.ModeDetail) -> Unit,
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
    detailsItem: MegaNetworkModeScreen.ModeDetail, onClick: () -> Unit, modifier: Modifier = Modifier
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)
        .background(Color.White, shape = RoundedCornerShape(16.dp)).clickable { onClick() }.padding(16.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = detailsItem.image,
          contentDescription = detailsItem.type,
          modifier = modifier.size(32.dp)

        )
        Text(
          text = detailsItem.type,
          fontSize = 16.sp,
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

