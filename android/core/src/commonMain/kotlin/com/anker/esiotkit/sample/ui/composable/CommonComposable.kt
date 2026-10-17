package com.anker.esiotkit.sample.ui.composable

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * @version 2023/3/16
 */
fun Modifier.simpleClickable(onClick: () -> Unit) = composed {
  this.clickable(remember {
    MutableInteractionSource()
  }, null) { onClick() }
}

fun Modifier.touchToHideKeyboard() = composed {
  val focusManager = LocalFocusManager.current
  this.simpleClickable {
    focusManager.clearFocus(true)
  }
}

@Composable
internal fun ItemRow(
  modifier: Modifier = Modifier,
  verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
  content: @Composable RowScope.() -> Unit
) {
  Row(
    modifier = modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
    verticalAlignment = verticalAlignment,
    content = content
  )
}

@Composable
internal fun InputView(
  value: String,
  onValueChange: (String) -> Unit,
  label: @Composable (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  isError: Boolean = false,
  enabled: Boolean = true,
  singleLine: Boolean = false,
  readOnly: Boolean = false,
  colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  visualTransformation: VisualTransformation = VisualTransformation.None,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
  // 输入太快的时候光标会错位，所以这里做了一个缓存，这个可能是 Compose 的 bug，看后面有没有更新
  var cacheValue by state(TextFieldValue(""))
  if (cacheValue.text != value) {
    cacheValue = cacheValue.copy(text = value, selection = TextRange(value.length))
  }
  OutlinedTextField(
    value = cacheValue,
    onValueChange = {
      cacheValue = it
      onValueChange(it.text)
    },
    label = label,
    modifier = modifier,
    colors = colors,
    isError = isError,
    enabled = enabled,
    readOnly = readOnly,
    interactionSource = interactionSource,
    singleLine = singleLine,
    visualTransformation = visualTransformation,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions
  )
}

fun Modifier.enableAlpha(enabled: Boolean) = composed {
  val alpha by animateFloatAsState(if (enabled) 1f else 0.5f)
  this.alpha(alpha)
}