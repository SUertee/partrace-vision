package com.anker.esiotkit.sample.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BatteryIndicator(batteryLevel: Int,modifier: Modifier = Modifier) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(width = 24.dp, height = 12.dp)
        .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(2.dp))
    ) {
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        for (i in 1..5) {
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight()
              .background(if (i <= batteryLevel / 20) MaterialTheme.colorScheme.primary else Color.Gray)
          )
          if (i < 5) {
            Spacer(modifier = Modifier.width(2.dp))
          }
        }
      }
    }
    Spacer(modifier = Modifier.width(4.dp))
    Text(text = "$batteryLevel%", fontSize = 16.sp, color = Color.Black)
  }
}