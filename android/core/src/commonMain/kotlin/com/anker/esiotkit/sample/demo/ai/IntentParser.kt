package com.anker.esiotkit.sample.demo.ai

import com.anker.esiotkit.sample.demo.model.IntentResult
import com.anker.esiotkit.sample.demo.util.JsonCleaner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 意图解析器。
 *
 * 两条路径：
 * 1. [parseAiJson]：解析 Nova Lite 返回的 JSON（主路径）
 * 2. [parseLocal]：本地启发式解析（降级路径 + 离线可测试，支持中文时间/目标表达）
 */
object IntentParser {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /** 解析 Nova Lite 返回的 JSON 文本 */
  fun parseAiJson(rawText: String?): IntentResult? {
    val cleaned = JsonCleaner.cleanJsonOrNull(rawText) ?: return null
    return try {
      val obj = json.parseToJsonElement(cleaned).jsonObject
      val start = obj["start_hour"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
      val end = obj["end_hour"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
      val target = obj["query_target"]?.jsonPrimitive?.content ?: "all"
      val detection = obj["detection_type"]?.jsonPrimitive?.content ?: "ALL"
      val understood = obj["understood_intent"]?.jsonPrimitive?.content ?: ""
      IntentResult(
        startHour = start.coerceIn(0, 24),
        endHour = end.coerceIn(0, 24),
        queryTarget = target,
        detectionType = detection.uppercase(),
        understoodIntent = understood,
      )
    } catch (_: Exception) {
      null
    }
  }

  /** 本地启发式解析（中文表达） */
  fun parseLocal(userText: String): IntentResult? {
    if (userText.isBlank()) return null
    val text = userText

    // ── 日期偏移 ──
    val dayOffset = when {
      text.contains("前天") -> -2
      text.contains("昨天") || text.contains("昨晚") || text.contains("昨夜") -> -1
      else -> 0
    }

    // ── 时间范围 ──
    var startHour: Int? = null
    var endHour: Int? = null

    // 具体钟点："凌晨3点" / "3点半" / "15点" / "下午3点"
    val hourMatch = Regex("(\\d{1,2})\\s*点").find(text)
    if (hourMatch != null) {
      var h = hourMatch.groupValues[1].toIntOrNull()
      if (h != null) {
        if (text.contains("下午") && h < 12) h += 12
        if (text.contains("晚上") && h in 1..11) h += 12
        if (h in 0..23) {
          startHour = h
          endHour = h + 1
        }
      }
    }

    if (startHour == null) {
      val period = when {
        text.contains("凌晨") -> 0 to 6
        text.contains("早上") || text.contains("早晨") -> 6 to 10
        text.contains("上午") -> 6 to 12
        text.contains("中午") -> 11 to 14
        text.contains("下午") -> 14 to 18
        text.contains("傍晚") -> 17 to 20
        text.contains("晚上") || text.contains("夜间") || text.contains("夜里") -> 18 to 24
        else -> null
      }
      if (period != null) {
        startHour = period.first
        endHour = period.second
      }
    }

    // 未识别出时间 → 全天
    if (startHour == null) {
      startHour = 0
      endHour = 24
    }

    // ── 查询目标 ──
    val queryTarget = when {
      text.contains("包裹") || text.contains("快递") -> "package"
      text.contains("人") || text.contains("有人") || text.contains("出入") -> "people"
      else -> "all"
    }

    // ── 检测类型 ──
    val detectionType = when {
      text.contains("宠物") || text.contains("猫") || text.contains("狗") -> "PET"
      text.contains("车") || text.contains("汽车") -> "CAR"
      queryTarget == "people" -> "BODY"
      else -> "ALL"
    }

    return IntentResult(
      startHour = startHour ?: 0,
      endHour = endHour ?: 24,
      queryTarget = queryTarget,
      detectionType = detectionType,
      understoodIntent = text.take(64),
      dayOffset = dayOffset,
    )
  }
}
