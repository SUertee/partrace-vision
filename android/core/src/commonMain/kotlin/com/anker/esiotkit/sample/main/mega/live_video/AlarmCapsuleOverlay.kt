package com.anker.esiotkit.sample.main.mega.live_video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AlarmCapsuleOverlay(
  alarmTypeName: String,
  countdown: Int,
  onStop: () -> Unit
) {
  Row(
    modifier = Modifier
      .clip(RoundedCornerShape(20.dp))
      .background(Color(0xFFFF6B35))
      .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = "$alarmTypeName ${countdown}s",
      color = Color.White,
      fontSize = 13.sp,
      fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.width(6.dp))
    Icon(
      imageVector = Icons.Default.Close,
      contentDescription = "Stop alarm",
      tint = Color.White,
      modifier = Modifier
        .size(22.dp)
        .clip(RoundedCornerShape(11.dp))
        .background(Color.White.copy(alpha = 0.3f))
        .clickable { onStop() }
        .padding(3.dp)
    )
  }
}
