package com.anker.esiotkit.sample.main.mega.device_settings.general

import kotlinx.serialization.Transient

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.view.BaseScreen
import org.jetbrains.compose.resources.StringResource
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_settings_general_camera_name_title

class CameraNameScreen(private val deviceName: String, @Transient private val changeDeviceName: (String) -> Unit = {}) :
  BaseScreen() {
  override val title: StringResource
    get() = Res.string.eslot_settings_general_camera_name_title

  private var name by mutableStateOf(deviceName)
  private var isSaveEnabled by mutableStateOf(false)

  @Composable
  override fun RowScope.TitleRightIcon() {
    TextButton(
      onClick = {
        changeDeviceName(name)
        super.onBackClick()
      },
      enabled = isSaveEnabled
    ) {
      Text(text = "Save", color = if (isSaveEnabled) MaterialTheme.colorScheme.primary else Color.Gray)
    }
  }

  @Composable
  override fun Content() = BaseColumn {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(
        text = "Device Name",
        fontSize = 14.sp,
        color = Color.Gray
      )
      TextField(
        value = name,
        onValueChange = {
          name = it
          isSaveEnabled = it != deviceName
        },
        modifier = Modifier
          .fillMaxWidth()
          .background(Color.White, RoundedCornerShape(8.dp)),
        trailingIcon = {
          if (name.isNotEmpty()) {
            IconButton(onClick = { name = "" }) {
              Icon(imageVector = Icons.Default.Close, contentDescription = null)
            }
          }
        },
        singleLine = true,
        colors = TextFieldDefaults.textFieldColors(
          backgroundColor = Color.Transparent,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent
        )
      )
      Text(
        text = "*Please avoid using personal sensitive information.",
        fontSize = 16.sp,
        color = Color.Gray
      )

    }
  }
}