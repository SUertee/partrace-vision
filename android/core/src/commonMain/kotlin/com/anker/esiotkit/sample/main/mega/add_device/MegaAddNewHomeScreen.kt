package com.anker.esiotkit.sample.main.mega.add_device

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.view.BaseScreen
import androidx.compose.runtime.*
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_common_untitled_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/8/23
 */
object MegaAddNewHomeScreen : BaseScreen() {

  override val title: StringResource get() = Res.string.eslot_common_untitled_title

  @Composable
  override fun Content() {

    val navigator = LocalNavigator.currentOrThrow

    var name by remember { mutableStateOf("") }

    NameForm(name = name,
             onNameChange = { name = it },
             onNextClick = { },
             onAddressClick = { navigator.push(MegaAddressScreen) })

  }

  @Composable
  fun NameForm(
    name: String, onNameChange: (String) -> Unit, onNextClick: () -> Unit, onAddressClick: () -> Unit
  ) {
    Column(
      modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5))

    ) {
      Text(
        text = "Name (required)", fontSize = 16.sp, color = Color.Gray
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
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "Address", fontSize = 16.sp, color = Color.Gray
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.clickable { onAddressClick() }.fillMaxWidth().padding(vertical = 8.dp)
          .background(Color.White, shape = RoundedCornerShape(8.dp)).padding(16.dp)
      ) {
        Text(
          text = "Address", fontSize = 16.sp, color = Color.Black
        )
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowForward,
          contentDescription = "Forward",
          tint = Color.Gray
        )
      }
      Spacer(modifier = Modifier.weight(1f))
      Button(
        onClick = onNextClick,
        enabled = name.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(if (name.isNotEmpty()) Color(0xFF007AFF) else Color.Gray)
      ) {
        Text(text = "Next", color = Color.White, fontSize = 16.sp)
      }
    }
  }

}

