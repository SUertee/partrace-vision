package com.anker.esiotkit.sample.support.log

import com.anker.esiotkit.support.log.appLogger

private fun Throwable?.safeDescription(): String {
  if (this == null) return "null"
  val cls = this::class.simpleName ?: "UnknownException"
  val msg = this.message
  return if (msg.isNullOrBlank()) cls else "$cls: $msg"
}

fun logW(tag: String, throwable: Throwable?) {
  appLogger.w("$tag | ${throwable.safeDescription()}")
}

fun logE(tag: String, throwable: Throwable?) {
  appLogger.e("$tag | ${throwable.safeDescription()}")
}
