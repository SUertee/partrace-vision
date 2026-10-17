package com.anker.esiotkit.sample.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.anker.esiotkit.sample.main.mega.add_device.device_family.MergedDevice

/**
 * @version 2024/6/12
 */
@Composable
fun MergedDeviceCard(device: MergedDevice, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color.Gray.copy(0.5f))
      .padding(vertical = 8.dp)
      .clickable { onClick() },
    verticalAlignment = Alignment.CenterVertically
  ) {
    Spacer(modifier = Modifier.width(16.dp))
    AsyncImage(
      model = device.iconURL,
      contentDescription = device.deviceCateLv2,
      modifier = Modifier
        .size(64.dp)
        .clip(RoundedCornerShape(8.dp)),
      contentScale = ContentScale.Crop
    )
    Spacer(modifier = Modifier.width(16.dp))
    Column {
      Text(
        text = device.deviceCateLv2,
        fontSize = 16.sp
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = device.deviceTypeNames.joinToString(" / "),
        color = Color.Gray,
        fontSize = 12.sp
      )
    }
  }
}