package com.anker.esiotkit.sample.utils

import android.util.Log

/**
 * @version 2023/9/19
 */
internal const val LOG_TAG_APP = "ESIot/app"
internal const val LOG_TAG_DATA = "ESIot/data"
internal const val LOG_TAG_P2P = "ESIot/p2p"
internal const val LOG_TAG_P2PLIB = "ESIot/p2pLib"
internal const val LOG_TAG_BLE = "ESIot/ble"
internal const val LOG_TAG_HTTP = "ESIot/http"
internal const val LOG_TAG_MEDIA = "ESIot/media"

/**
 * 控制 SDK 层低优先级日志输出。关闭后仅保留 W/E 级别日志。
 * 默认 false（生产环境静默），调试时改为 true。
 */
var isVerboseSdkLoggingEnabled: Boolean = false

private inline fun logIfVerbose(tag: String, block: () -> Int) {
  if (isVerboseSdkLoggingEnabled) block()
}

object appLogger {
  fun v(msg: String) { Log.v(LOG_TAG_APP, msg) }
  fun i(msg: String) { Log.i(LOG_TAG_APP, msg) }
  fun d(msg: String) { Log.d(LOG_TAG_APP, msg) }
  fun w(msg: String) { Log.w(LOG_TAG_APP, msg) }
  fun e(msg: String) { Log.e(LOG_TAG_APP, msg) }
}

object dataLogger {
  fun v(msg: String) { Log.v(LOG_TAG_DATA, msg) }
  fun i(msg: String) { Log.i(LOG_TAG_DATA, msg) }
  fun d(msg: String) { Log.d(LOG_TAG_DATA, msg) }
  fun w(msg: String) { Log.w(LOG_TAG_DATA, msg) }
  fun e(msg: String) { Log.e(LOG_TAG_DATA, msg) }
}

object bleLogger {
  fun v(msg: String) { Log.v(LOG_TAG_BLE, msg) }
  fun i(msg: String) { Log.i(LOG_TAG_BLE, msg) }
  fun d(msg: String) { Log.d(LOG_TAG_BLE, msg) }
  fun w(msg: String) { Log.w(LOG_TAG_BLE, msg) }
  fun e(msg: String) { Log.e(LOG_TAG_BLE, msg) }
}

object p2pLibLogger {
  fun v(msg: String) { logIfVerbose(LOG_TAG_P2PLIB) { Log.v(LOG_TAG_P2PLIB, msg) } }
  fun i(msg: String) { logIfVerbose(LOG_TAG_P2PLIB) { Log.i(LOG_TAG_P2PLIB, msg) } }
  fun d(msg: String) { logIfVerbose(LOG_TAG_P2PLIB) { Log.d(LOG_TAG_P2PLIB, msg) } }
  fun w(msg: String) { Log.w(LOG_TAG_P2PLIB, msg) }
  fun e(msg: String) { Log.e(LOG_TAG_P2PLIB, msg) }
}

object p2pLogger {
  fun v(msg: String) { logIfVerbose(LOG_TAG_P2P) { Log.v(LOG_TAG_P2P, msg) } }
  fun i(msg: String) { logIfVerbose(LOG_TAG_P2P) { Log.i(LOG_TAG_P2P, msg) } }
  fun d(msg: String) { logIfVerbose(LOG_TAG_P2P) { Log.d(LOG_TAG_P2P, msg) } }
  fun w(msg: String) { Log.w(LOG_TAG_P2P, msg) }
  fun e(msg: String) { Log.e(LOG_TAG_P2P, msg) }
}

object httpLogger {
  fun v(msg: String) { Log.v(LOG_TAG_HTTP, msg) }
  fun i(msg: String) { Log.i(LOG_TAG_HTTP, msg) }
  fun d(msg: String) { Log.d(LOG_TAG_HTTP, msg) }
  fun w(msg: String) { Log.w(LOG_TAG_HTTP, msg) }
  fun e(msg: String) { Log.e(LOG_TAG_HTTP, msg) }
}

object mediaLogger {
  fun v(msg: String) { Log.v(LOG_TAG_MEDIA, msg) }
  fun i(msg: String) { Log.i(LOG_TAG_MEDIA, msg) }
  fun d(msg: String) { Log.d(LOG_TAG_MEDIA, msg) }
  fun w(msg: String) { Log.w(LOG_TAG_MEDIA, msg) }
  fun e(msg: String) { Log.e(LOG_TAG_MEDIA, msg) }
}
