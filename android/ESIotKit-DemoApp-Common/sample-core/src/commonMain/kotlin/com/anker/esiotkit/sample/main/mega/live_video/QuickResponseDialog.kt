package com.anker.esiotkit.sample.main.mega.live_video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

data class QuickResponseItem(
  val id: String,
  val message: String
)

@Composable
fun QuickResponseDialog(
  responses: List<QuickResponseItem>,
  onDismiss: () -> Unit,
  onResponseSelected: (QuickResponseItem) -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, RoundedCornerShape(16.dp))
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text(
        text = "Quick Response",
        fontSize = 18.sp,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp)
      )

      if (responses.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
          items(responses) { response ->
            ResponseItem(
              response = response,
              onClick = {
                onResponseSelected(response)
                onDismiss()
              }
            )
            if (responses.indexOf(response) < responses.size - 1) {
              HorizontalDivider()
            }
          }
        }
      }

      Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
      ) {
        Text("Cancel")
      }
    }
  }
}

@Composable
private fun ResponseItem(
  response: QuickResponseItem,
  onClick: () -> Unit
) {
  Text(
    text = response.message,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp, horizontal = 8.dp),
    fontSize = 14.sp
  )
}
