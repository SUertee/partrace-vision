package com.anker.esiotkit.sample.support

import android.content.pm.ActivityInfo
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.anker.esiotkit.sample.ESIotActivityManager

/**
 * @version 2024/12/2
 */
actual fun intoFullScreen() {
  ESIotActivityManager.getFirstActivity()?.let {
    WindowInsetsControllerCompat(it.window, it.window.decorView).let { controller ->
      controller.hide(WindowInsetsCompat.Type.navigationBars())
      controller.hide(WindowInsetsCompat.Type.statusBars())
      controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    _fullScreen.value = true
    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
  }
}

actual fun exitFullScreen() {
  ESIotActivityManager.getFirstActivity()?.let {
    WindowInsetsControllerCompat(it.window, it.window.decorView).let { controller ->
      controller.show(WindowInsetsCompat.Type.navigationBars())
      controller.show(WindowInsetsCompat.Type.statusBars())
      controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    }
    _fullScreen.value = false
    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
  }
}