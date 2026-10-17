package com.anker.esiotkit.sample.demo.ai

import com.anker.esiotkit.sample.demo.model.AnalysisStatus
import com.anker.esiotkit.sample.demo.model.EventMatchResult
import com.anker.esiotkit.sample.demo.model.IntentResult
import com.anker.esiotkit.sample.demo.model.MatchStatus
import com.anker.esiotkit.sample.demo.model.SummaryResult
import com.anker.esiotkit.sample.demo.util.JsonCleaner

/**
 * AI 业务共享层（从 AwsAiServiceImpl 平移抽取，防双实现副本漂移）。
 *
 * Prompt 与本地降级链为业务资产：Router/AWS 两套实现共用同一份，
 * 修改只允许落在此处（禁止在任何 Impl 内再写私有副本）。
 */
internal object AiPrompts {

  fun intent(userText: String): String = """
    你是家庭安防助手的意图解析器。用户说：「${JsonCleaner.escapeForJson(userText)}」。
    请提取：1) 时间范围（小时，0-24，如"下午"=14到18）2) 查询目标（people/package/all）3) 检测类型（BODY/PET/CAR/ALL）。
    只输出 JSON，不要任何其他文字：{"start_hour":14,"end_hour":18,"query_target":"people","detection_type":"BODY","understood_intent":"查看下午是否有人出入"}
  """.trimIndent()

  fun image(): String =
    "你是家庭安防分析助手。请分析这张监控缩略图，判断画面中是否有人。" +
      "严格输出 JSON：{\"description\":\"画面简要描述\",\"has_person\":true或false,\"labels\":[\"标签\"],\"scene_type\":\"室内/室外\"}"

  fun video(): String =
    "你是家庭安防分析助手。请分析这段监控视频，判断画面中是否有人及其行为。" +
      "严格输出 JSON：{\"description\":\"画面与行为描述\",\"has_person\":true或false,\"behavior\":\"靠近/远离/停留/经过/无活动\",\"activity_type\":\"工作/经过/异常/无\"}"

  fun summary(query: String, events: List<EventMatchResult>): String {
    val lines = events.joinToString("\n") {
      "- ${it.timeText} 设备=${it.event.deviceName} SDK类型=${it.sdkType} AI判断有人=${it.aiHasPerson} 描述=${it.aiDescription} 匹配=${it.matchStatus}"
    }
    return """
      你是家庭安防助手。用户问题：「${JsonCleaner.escapeForJson(query)}」。
      以下是事件与 AI 分析结果：
      $lines
      请给出一句话中文结论（包含时间点与是否有人）。
      严格输出 JSON：{"answer":"今天下午15:26有人在家里","status":"safe或attention"}
    """.trimIndent()
  }

  fun dailyReport(
    events: List<EventMatchResult>,
    confirmed: Int,
    falseAlarm: Int,
    timeAnalysis: String,
  ): String {
    val lines = events.joinToString("\n") {
      "- ${it.timeText} 设备=${it.event.deviceName} SDK类型=${it.sdkType} AI判断有人=${it.aiHasPerson} 描述=${it.aiDescription} 匹配=${it.matchStatus}"
    }
    return """
      你是家庭安防助手，请生成今日安全日报（中文，3-5 句话，包含分类统计、时间线特征与总体结论）。
      事件列表：
      $lines
      统计：确认 $confirmed 条，疑似误报 $falseAlarm 条；时间分布：$timeAnalysis
      严格输出 JSON：{"daily_report":"...","status":"safe或attention","confirmed_count":$confirmed,"false_alarm_count":$falseAlarm,"key_events":["..."],"time_analysis":"$timeAnalysis"}
    """.trimIndent()
  }
}

/**
 * AI 响应解析与本地降级链（从 AwsAiServiceImpl 平移抽取）。
 * 非空方法（summarize/generateDailyReport）的兜底语义在此统一。
 */
internal object AiResponseParsers {

  /** 兜底意图：全时段+全目标（原 AwsAiServiceImpl 同款） */
  fun fallbackIntent() = IntentResult(
    startHour = 0, endHour = 24, queryTarget = "all", detectionType = "ALL",
    understoodIntent = "fallback", dayOffset = 0,
  )

  fun fallbackSummary(events: List<EventMatchResult>): SummaryResult {
    val confirmed = events.count { it.matchStatus == MatchStatus.CONFIRMED }
    val answer = if (confirmed > 0) {
      "该时段检测到 ${events.size} 条活动，其中 $confirmed 条已确认有目标出现"
    } else {
      "该时段检测到 ${events.size} 条活动，但均未确认有目标出现"
    }
    return SummaryResult(answer, AnalysisStatus.SUCCESS)
  }

  fun fallbackDailyReport(events: List<EventMatchResult>, confirmed: Int, falseAlarm: Int): String =
    "今日共 ${events.size} 条事件：确认 $confirmed 条，疑似误报 $falseAlarm 条。"

  fun timeAnalysis(events: List<EventMatchResult>): String {
    val hours = events.mapNotNull { e -> e.timeText.substringBefore(":").toIntOrNull() }
      .groupingBy { it }.eachCount()
    return hours.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}时×${it.value}" }
  }
}
