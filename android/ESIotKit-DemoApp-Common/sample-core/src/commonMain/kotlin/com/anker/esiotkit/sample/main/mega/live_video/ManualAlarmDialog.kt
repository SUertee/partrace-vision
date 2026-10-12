package com.anker.esiotkit.sample.main.mega.live_video

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
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.anker.esiotkit.net_api.response.thing.TDEnum

@Composable
fun ManualAlarmDialog(
  supportedTypes: List<TDEnum>,
  onDismiss: () -> Unit,
  onTypeSelected: (Int) -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = Color.White,
      elevation = 8.dp
    ) {
      Column(
        modifier = Modifier.padding(20.dp)
      ) {
        Text(
          text = "Manual Alarm",
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold,
          color = Color(0xFF333333)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Select alarm type to trigger",
          fontSize = 13.sp,
          color = Color(0xFF999999)
        )
        Spacer(modifier = Modifier.height(16.dp))

        supportedTypes.forEach { tdEnum ->
          val typeValue = tdEnum.value.toIntOrNull() ?: return@forEach
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                onTypeSelected(typeValue)
                onDismiss()
              }
              .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.NotificationImportant,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
              tint = Color(0xFFFF6B35)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = tdEnum.desc.ifBlank { "Type ${tdEnum.value}" },
              fontSize = 15.sp,
              color = Color(0xFF333333)
            )
          }
        }

        // Fallback: if enumList is empty, show default options
        if (supportedTypes.isEmpty()) {
          val defaultTypes = listOf(
            0 to "Sound Only",
            1 to "Light Only",
            2 to "Sound and Light"
          )
          defaultTypes.forEach { (value, name) ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  onTypeSelected(value)
                  onDismiss()
                }
                .padding(vertical = 12.dp, horizontal = 8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Default.NotificationImportant,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color(0xFFFF6B35)
              )
              Spacer(modifier = Modifier.width(12.dp))
              Text(
                text = name,
                fontSize = 15.sp,
                color = Color(0xFF333333)
              )
            }
          }
        }
      }
    }
  }
}
