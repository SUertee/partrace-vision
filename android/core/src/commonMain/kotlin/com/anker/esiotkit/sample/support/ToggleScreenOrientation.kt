package com.anker.esiotkit.sample.support

import com.anker.esiotkit.support.utils.asKnFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * @version 2024/12/2
 */
internal val _fullScreen = MutableStateFlow(false)
val fullScreen = _fullScreen.asKnFlow()
expect fun intoFullScreen()

expect fun exitFullScreen()

fun toggleScreenOrientation() {
  if (_fullScreen.value) {
    exitFullScreen()
  } else {
    intoFullScreen()
  }
}