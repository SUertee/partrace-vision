package com.anker.esiotkit.sample.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * T-UI-06: 添加设备按钮
 *
 * 悬浮在设备列表右下角，点击触发添加设备流程。
 */
@Composable
fun AddDeviceButton(
  onClick: () -> Unit,
) {
  ExtendedFloatingActionButton(
    onClick = onClick,
    modifier = Modifier.padding(16.dp),
    containerColor = Color(0xFF5B9BD5),
    contentColor = Color.White
  ) {
    Icon(
      imageVector = Icons.Default.Add,
      contentDescription = "添加设备"
    )
    Text(text = "添加设备")
  }
}
