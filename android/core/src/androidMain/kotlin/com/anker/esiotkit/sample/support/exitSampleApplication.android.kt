package com.anker.esiotkit.sample.support

import com.anker.esiotkit.sample.ESIotActivityManager

/**
 * @version 2024/8/5
 */
actual fun exitSampleApplication() {
  ESIotActivityManager.finishAllActivity()
}