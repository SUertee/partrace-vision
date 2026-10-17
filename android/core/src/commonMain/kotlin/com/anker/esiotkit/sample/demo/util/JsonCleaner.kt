package com.anker.esiotkit.sample.demo.util

/**
 * AI 返回文本的 JSON 清理工具（压测验证后的实际处理方式）。
 *
 * Nova 返回的 JSON 常见形态：
 * 1. 标准 JSON
 * 2. 被 ```json ... ``` 包裹
 * 3. JSON 前后附有说明文字
 */
object JsonCleaner {

  /**
   * 去掉 markdown 代码块标记并提取 JSON 主体。
   * 返回可能为空串（表示未找到 JSON）。
   */
  fun cleanJson(rawText: String): String {
    var cleaned = rawText.replace("```json", "").replace("```", "").trim()
    val match = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(cleaned)
    if (match != null) cleaned = match.value
    return cleaned.trim()
  }

  /** 清理 + 转义外部字符，便于直接 json 解析前的最终形态 */
  fun cleanJsonOrNull(rawText: String?): String? {
    if (rawText.isNullOrBlank()) return null
    val cleaned = cleanJson(rawText)
    return cleaned.ifBlank { null }
  }

  /** 简易转义：将文本包装为 JSON 字符串字面量（用于拼接 prompt） */
  fun escapeForJson(text: String): String {
    val sb = StringBuilder(text.length + 16)
    for (ch in text) {
      when (ch) {
        '\\' -> sb.append("\\\\")
        '"' -> sb.append("\\\"")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> sb.append(ch)
      }
    }
    return sb.toString()
  }
}
