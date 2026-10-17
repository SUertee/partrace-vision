package com.anker.esiotkit.sample.support.perf

import com.anker.esiotkit.core.config.IotDebugConfig
import com.anker.esiotkit.support.log.appLogger

object RepLog {
  inline fun i(tag: String, msg: () -> String) {
    if (IotDebugConfig.enableDetailDebugLog) appLogger.i("$tag ${msg()}")
  }
  inline fun d(tag: String, msg: () -> String) {
    if (IotDebugConfig.enableDetailDebugLog) appLogger.d("$tag ${msg()}")
  }
  inline fun w(tag: String, msg: () -> String) {
    if (IotDebugConfig.enableDetailDebugLog) appLogger.w("$tag ${msg()}")
  }
  inline fun e(tag: String, msg: () -> String) {
    if (IotDebugConfig.enableDetailDebugLog) appLogger.e("$tag ${msg()}")
  }
}
