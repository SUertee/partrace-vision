package com.anker.esiotkit.sample.support

import com.anker.esiotkit.support.platform.ESByteArray
import com.anker.esiotkit.support.platform.toESImageData

/**
 * @version 2024/12/2
 */
object MemoryTest {
  fun getByteArray(): ByteArray {
    val string = generateRandomString(100)
    return string.encodeToByteArray()
  }

  fun getNSData(): ESByteArray {
    return getByteArray().toESImageData()
  }

  private fun generateRandomString(size: Int): String {
    val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    return (1..size)
      .map { chars.random() }
      .joinToString("")
  }
}