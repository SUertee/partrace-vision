package com.anker.esiotkit.sample.support.perf

import com.anker.esiotkit.core.config.IotDebugConfig
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.platform.currentTimeMillis

object SdkPerfTracker {

  inline fun <T> measure(operation: String, block: () -> T): T {
    if (!IotDebugConfig.enableDetailDebugLog) return block()
    val start = currentTimeMillis()
    return block().also {
      appLogger.i("SdkPerf ══ $operation ══ cost=${currentTimeMillis() - start}ms")
    }
  }
}
