package com.anker.esiotkit.sample.support

import android.app.Application

/**
 * @version 2024/7/19
 */

var sampleApplication: Application? = null

actual val applicationContext: Any
  get() = sampleApplication ?: throw IllegalStateException("Application context is not initialized yet")

