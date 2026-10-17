package com.anker.esiotkit.sample.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * @version 2024/3/12
 */
@Composable
fun CommonButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Button(onClick = onClick, modifier = modifier) {
    Text(text = text)
  }
}

@Composable
fun RoundedCornerButton(
  text: String,
  modifier: Modifier = Modifier.padding(16.dp),
  shape: RoundedCornerShape = RoundedCornerShape(8.dp),
  onClick: () -> Unit
) {
  Button(onClick = onClick, shape = shape, modifier = modifier) {
    Text(text = text)
  }
}

@Composable
fun CommonOutfittedButton(
  text: String,
  modifier: Modifier = Modifier.padding(16.dp),
  shape: RoundedCornerShape = RoundedCornerShape(8.dp),
  color: Color = MaterialTheme.colorScheme.primary,
  onClick: () -> Unit
) {
  OutlinedButton(
    onClick = onClick,
    shape = shape,
    modifier = modifier,
    border = BorderStroke(1.dp, color)
  ) {
    Text(text = text)
  }
}
