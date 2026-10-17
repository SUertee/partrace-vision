package com.anker.esiotkit.sample.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * @version 2024/10/20
 */
@Composable
fun MegaLoading(loading: Boolean, text: String? = null) {
  if (loading) {
    Dialog(onDismissRequest = { }) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Color.Yellow)
        if (text != null) {
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = text,
            fontSize = 14.sp,
            color = Color.White
          )
        }
      }
    }
  }
}
