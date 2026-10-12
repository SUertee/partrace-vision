package com.anker.esiotkit.sample.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * @version 2024/6/12
 */
@Composable
fun SearchBar(
  placeholder: String,
  onSearch: (String) -> Unit,
  modifier: Modifier = Modifier,
  initialText: String = ""
) {
  var text by remember { mutableStateOf(TextFieldValue(initialText)) }

  LaunchedEffect(Unit) {
    onSearch(initialText)
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(8.dp)
      .height(30.dp)
      .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
      .background(Color.White)
  ) {
    BasicTextField(
      value = text,
      onValueChange = {
        text = it
        onSearch(it.text)
      },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
      decorationBox = { innerTextField ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth()
        ) {
          IconButton(
            onClick = { onSearch(text.text) }
          ) {
            Icon(
              imageVector = Icons.Default.Search,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary
            )
          }
          Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
          ) {
            if (text.text.isEmpty()) {
              Text(text = placeholder, style = TextStyle(color = MaterialTheme.colorScheme.primary))
            }
            innerTextField()
          }
          if (text.text.isNotEmpty()) {
            IconButton(
              onClick = {
                text = TextFieldValue("")
                onSearch("")
              },
            ) {
              Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.primary)
            }
          }
        }
      },
      keyboardOptions = KeyboardOptions.Default.copy(
        imeAction = ImeAction.Done
      ),
      keyboardActions = KeyboardActions(
        onDone = { }
      )
    )
  }
}