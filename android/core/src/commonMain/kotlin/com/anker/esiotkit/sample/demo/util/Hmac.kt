package com.anker.esiotkit.sample.demo.util

import korlibs.crypto.HMAC
import korlibs.crypto.sha256

/**
 * HMAC-SHA256（用于 AWS SigV4 签名）。
 *
 * 直接使用 korlibs 内置实现，保证 KMP commonMain 兼容。
 */
object Hmac {

  /** HMAC-SHA256 → 32 字节 */
  fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = HMAC.hmacSHA256(key, data).bytes

  /** HMAC-SHA256 → 小写十六进制字符串 */
  fun hmacSha256Hex(key: ByteArray, data: ByteArray): String = HMAC.hmacSHA256(key, data).hex

  /** SHA-256 → 小写十六进制字符串 */
  fun sha256Hex(data: ByteArray): String = data.sha256().hex

  /** byte[] → 小写 hex */
  fun ByteArray.toHex(): String {
    val hexChars = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
      val v = b.toInt() and 0xFF
      sb.append(hexChars[v ushr 4])
      sb.append(hexChars[v and 0x0F])
    }
    return sb.toString()
  }
}
