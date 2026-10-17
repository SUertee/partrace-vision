package com.anker.esiotkit.sample.support

import com.anker.esiotkit.support.log.appLogger

object EventImageCache {
  private val cache = mutableMapOf<String, ByteArray>()
  private const val maxSize = 100

  fun get(key: String): ByteArray? {
    val bytes = cache[key]
    if (bytes != null) {
      appLogger.i("[IMG-CACHE] hit: key=$key, bytes=${bytes.size}")
    }
    return bytes
  }

  fun put(key: String, bytes: ByteArray) {
    if (cache.size >= maxSize) {
      val oldestKey = cache.keys.first()
      cache.remove(oldestKey)
      appLogger.i("[IMG-CACHE] evict: key=$oldestKey (cache full)")
    }
    cache[key] = bytes
    appLogger.i("[IMG-CACHE] put: key=$key, bytes=${bytes.size}, total=${cache.size}")
  }

  fun clear() {
    cache.clear()
    appLogger.i("[IMG-CACHE] cleared")
  }
}
