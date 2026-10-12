package com.anker.esiotkit.sample.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * @version 2023/3/16
 */
inline fun Modifier.appendWhen(condition: Boolean, block: Modifier.() -> Modifier): Modifier {
  return if (condition) {
    block()
  } else {
    this
  }
}

@ReadOnlyComposable
@Composable
fun Int.toDp(): Dp {
  return LocalDensity.current.run { this@toDp.toDp() }
}

@ReadOnlyComposable
@Composable
fun Int?.toDpOrZero(): Dp {
  return LocalDensity.current.run { this@toDpOrZero?.toDp() ?: 0.dp }
}

@ReadOnlyComposable
@Composable
fun Float.toDp(): Dp {
  return LocalDensity.current.run { this@toDp.toDp() }
}

@ReadOnlyComposable
@Composable
fun Float?.toDpOrZero(): Dp {
  return LocalDensity.current.run { this@toDpOrZero?.toDp() ?: 0.dp }
}

@ReadOnlyComposable
@Composable
fun Dp.toPx(): Float {
  return LocalDensity.current.run {
    this@toPx.toPx()
  }
}

@Composable
fun <T> state(value: T) = remember { mutableStateOf(value) }

@Composable
fun <T> rememberDerivedState(calculation: () -> T) = remember { derivedStateOf(calculation) }

@Composable
fun <T> stateList(value: List<T> = emptyList()) = remember { mutableStateListOf<T>().apply { addAll(value) } }

expect fun debugLog(msg: String)
