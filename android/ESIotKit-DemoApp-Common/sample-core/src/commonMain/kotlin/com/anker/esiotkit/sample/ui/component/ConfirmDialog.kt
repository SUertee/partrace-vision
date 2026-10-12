package com.anker.esiotkit.sample.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun ButtonDialog(
  onDismissRequest: () -> Unit = {},
  onCancel: () -> Unit = {},
  onConfirm: () -> Unit,
  dialogTitle: String? = null,
  dialogText: String? = null,
  buttonText: String,
  modifier: Modifier = Modifier
) {
  AlertDialog(
    onDismissRequest = onDismissRequest,
    properties = DialogProperties(usePlatformDefaultWidth = true),
    title = dialogTitle?.let {
      {
        Text(
          text = it,
          textAlign = TextAlign.Center,
          fontSize = 20.sp,
          color = Color.Black
        )

      }
    },
    text = dialogText?.let {
      {
        Text(
          text = it,
          textAlign = TextAlign.Center,
          fontSize = 14.sp,
          color = Color.Gray
        )
      }
    },
    buttons = {
      Column(
        modifier = modifier.fillMaxWidth().padding( horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Button(
          onClick = onConfirm,
          modifier = modifier.fillMaxWidth(),
        ) {
          Text(buttonText, color = Color.White)
        }
        OutlinedButton(
          onClick = { onCancel() }, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
          Text("Cancel", color = Color.Gray)
        }
      }
    },
    modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)),
    contentColor = Color.Gray
  )
}

@Composable
fun ConfirmDialog(
  title: String,
  description: String,
  onConfirm: () -> Unit,
  onCancel: () -> Unit
) {
  Dialog(onDismissRequest = onCancel) {
    Surface(
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colors.surface
    ) {
      Column {
        Column(
          modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight(600),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
          )
          Text(
            text = description,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End
        ) {
          Box(
            modifier = Modifier
              .weight(1f)
              .border(BorderStroke(1.dp, Color.Gray.copy(0.5f)), shape = RectangleShape)
          ) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
              Text(text = "Cancel", color = Color.Gray)
            }
          }
          Box(
            modifier = Modifier
              .weight(1f)
              .border(BorderStroke(1.dp, Color.Gray.copy(0.5f)), shape = RectangleShape)
          ) {
            TextButton(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
              Text(text = "Confirm", color = MaterialTheme.colors.primary)
            }
          }
        }
      }
    }
  }
}