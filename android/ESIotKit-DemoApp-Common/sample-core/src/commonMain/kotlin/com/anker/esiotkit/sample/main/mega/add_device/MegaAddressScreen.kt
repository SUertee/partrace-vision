package com.anker.esiotkit.sample.main.mega.add_device

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.anker.esiotkit.demo.generated.resources.eslot_add_device_address_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/8/23
 */
object MegaAddressScreen : BaseScreen() {

  override val title: StringResource get() = Res.string.eslot_add_device_address_title


  @Composable
  override fun RowScope.TitleRightIcon() {
    val navigator = LocalNavigator.currentOrThrow

    Text(text = "Save", modifier = Modifier.padding(end = 16.dp).clickable{navigator?.pop()})
  }

  @Composable
  override fun Content() {
    NameForm(
      onAddressClick = { }
    )
  }

  @Composable
  fun NameForm(
    onAddressClick: () -> Unit
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFFF5F5F5))
    ) {
      Text(
        text = "Position the mark on the map or enter an address below",
        fontSize = 16.sp,
        color = Color.Gray
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.clickable { onAddressClick() }.fillMaxWidth().padding(vertical = 8.dp)
          .background(Color.White, shape = RoundedCornerShape(8.dp))
          .padding(16.dp)
      ) {
        Text(
          text = "Address",
          fontSize = 16.sp,
          color = Color.Black
        )
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowForward,
          contentDescription = "Forward",
          tint = Color.Gray
        )
      }
    }
  }

}
