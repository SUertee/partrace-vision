package com.anker.esiotkit.sample.view

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.anker.esiotkit.support.ktx.timePass
import com.anker.esiotkit.support.platform.currentTimeMillis
import com.dokar.sonner.ToastType
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import kotlin.time.Duration.Companion.milliseconds

/**
 * @version 2022/5/22
 */

fun emitToast(message: String?) {
  toastMessage = ToastMessage.StringMessage(message, currentTimeMillis())
}

// fun emitToast(stringRes: Int) {
//   toastMessage = ToastMessage.ResMessage(stringRes, currentTimeMillis())
// }

private sealed class ToastMessage(val emitTime: Long) {
  class StringMessage(val msg: String?, emitTime: Long) : ToastMessage(emitTime)
}

private var toastMessage by mutableStateOf<ToastMessage>(ToastMessage.StringMessage(null, 0))
private const val TOAST_PERIOD = 2000L

@Composable
fun MegaToaster() {
  val toastMessage = remember(toastMessage.emitTime) { toastMessage }
  if (toastMessage.emitTime.timePass(TOAST_PERIOD)) return
  val key = toastMessage.emitTime
  val msg = when (toastMessage) {
    is ToastMessage.StringMessage -> toastMessage.msg
  }
  val toaster = rememberToasterState()
  Toaster(
    state = toaster,
    modifier = androidx.compose.ui.Modifier.fillMaxWidth(0.67f),

    alignment = androidx.compose.ui.Alignment.TopCenter,
    offset = androidx.compose.ui.unit.IntOffset(0, 100)
  )
  LaunchedEffect(key1 = key) {
    msg?.let {
      toaster.show(
        message = it,
        duration = TOAST_PERIOD.milliseconds
      )
    }
  }
}

fun toastNotSupport() {
  emitToast("not support yet")
}