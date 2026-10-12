package com.anker.esiotkit.sample.demo

import com.anker.esiotkit.sample.demo.util.Hmac
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * AWS SigV4 签名器（AWS4-HMAC-SHA256）。
 *
 * 仅依赖 commonMain 可用的能力（korlibs sha256 + kotlinx-datetime），
 * 通过 header 方式为 REST 请求生成签名。
 *
 * 参考：https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
 */
object AwsSigV4Signer {

  data class SignResult(
    /** 需要附加到请求的 headers（含 Authorization / x-amz-date / x-amz-content-sha256） */
    val headers: Map<String, String>,
    val amzDate: String,
    val credentialScope: String,
  )

  /**
   * 生成签名。
   *
   * @param method  HTTP 方法（GET/POST/PUT）
   * @param host    目标 host（如 bedrock-runtime.us-east-1.amazonaws.com）
   * @param path    规范化 URI 路径（如 /model/amazon.nova-lite-v1:0/converse）
   * @param query   规范化查询串（不含 ?，无参数传空串；参数需已按 key 排序并 URL 编码）
   * @param service 服务名（bedrock / transcribe / polly / s3）
   * @param region  区域（us-east-1）
   * @param accessKey / secretKey 凭证
   * @param payload 请求体字节（无体传空数组）
   * @param contentType 请求 Content-Type（无则传 null）
   */
  fun sign(
    method: String,
    host: String,
    path: String,
    query: String,
    service: String,
    region: String,
    accessKey: String,
    secretKey: String,
    payload: ByteArray,
    contentType: String? = null,
    extraHeaders: Map<String, String> = emptyMap(),
  ): SignResult {
    val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC)
    val amzDate = buildAmzDate(now)
    val dateStamp = amzDate.substring(0, 8)

    val payloadHash = Hmac.sha256Hex(payload)

    // ── 1) CanonicalRequest（header 按名称字典序）──
    val headersToSign = mutableMapOf<String, String>()
    headersToSign["host"] = host
    if (contentType != null) headersToSign["content-type"] = contentType
    headersToSign["x-amz-content-sha256"] = payloadHash
    headersToSign["x-amz-date"] = amzDate
    extraHeaders.forEach { (k, v) -> headersToSign[k.lowercase()] = v }

    val canonicalHeadersBuilder = StringBuilder()
    headersToSign.keys.sorted().forEach { k -> canonicalHeadersBuilder.append(k).append(':').append(headersToSign[k]).append('\n') }
    val signedHeaders = headersToSign.keys.sorted().joinToString(";")

    val canonicalRequest = buildString {
      append(method).append('\n')
      append(encodePath(path)).append('\n')
      append(query).append('\n')
      append(canonicalHeadersBuilder)
      append('\n')
      append(signedHeaders).append('\n')
      append(payloadHash)
    }

    // ── 2) StringToSign ──
    val credentialScope = "$dateStamp/$region/$service/aws4_request"
    val stringToSign = buildString {
      append("AWS4-HMAC-SHA256").append('\n')
      append(amzDate).append('\n')
      append(credentialScope).append('\n')
      append(Hmac.sha256Hex(canonicalRequest.encodeToByteArray()))
    }

    // ── 3) SigningKey（HMAC 链）──
    val kSecret = ("AWS4" + secretKey).encodeToByteArray()
    val kDate = Hmac.hmacSha256(kSecret, dateStamp.encodeToByteArray())
    val kRegion = Hmac.hmacSha256(kDate, region.encodeToByteArray())
    val kService = Hmac.hmacSha256(kRegion, service.encodeToByteArray())
    val kSigning = Hmac.hmacSha256(kService, "aws4_request".encodeToByteArray())

    // ── 4) Signature ──
    val signature = Hmac.hmacSha256Hex(kSigning, stringToSign.encodeToByteArray())

    val authorization = "AWS4-HMAC-SHA256 " +
      "Credential=$accessKey/$credentialScope, " +
      "SignedHeaders=$signedHeaders, " +
      "Signature=$signature"

    val headers = buildMap {
      put("x-amz-date", amzDate)
      put("x-amz-content-sha256", payloadHash)
      put("Authorization", authorization)
      if (contentType != null) put("Content-Type", contentType)
    }

    return SignResult(headers, amzDate, credentialScope)
  }

  private fun buildAmzDate(dt: kotlinx.datetime.LocalDateTime): String {
    fun p(v: Int, len: Int = 2) = v.toString().padStart(len, '0')
    return "${p(dt.year, 4)}${p(dt.monthNumber)}${p(dt.dayOfMonth)}T" +
      "${p(dt.hour)}${p(dt.minute)}${p(dt.second)}Z"
  }

  private val HEX_CHARS = "0123456789ABCDEF"

  private fun encodePath(path: String): String = buildString(path.length + 8) {
    for (ch in path) {
      when {
        ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
          ch == '-' || ch == '_' || ch == '.' || ch == '~' || ch == '/' -> append(ch)
        else -> {
          for (b in ch.toString().encodeToByteArray()) {
            append('%')
            append(HEX_CHARS[(b.toInt() ushr 4) and 0x0F])
            append(HEX_CHARS[b.toInt() and 0x0F])
          }
        }
      }
    }
  }
}
