package com.anker.esiotkit.sample.main.mega.live_video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.anker.esiotkit.domain_thing.api.domain.NightVisionModeOption
import com.anker.esiotkit.support.log.appLogger

@Composable
fun NightVisionDialog(
  currentMode: Int,
  supportedModes: List<NightVisionModeOption>,
  onDismiss: () -> Unit,
  onModeSelected: (Int) -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .clip(RoundedCornerShape(16.dp))
        .background(Color.White)
        .padding(vertical = 16.dp)
    ) {
      Text(
        text = "Night Vision",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
      )

      supportedModes.forEachIndexed { index, option ->
        val isSelected = currentMode == option.key
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable {
              appLogger.i("NightVisionDialog/onModeSelected: key=${option.key}, name=${option.displayName}, currentMode=$currentMode")
              onModeSelected(option.key)
              onDismiss()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = isSelected,
            onClick = {
              onModeSelected(option.key)
              onDismiss()
            }
          )
          Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
              text = option.displayName,
              fontSize = 16.sp,
              color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black
            )
          }
        }
        if (index < supportedModes.lastIndex) {
          HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
      }
    }
  }
}
