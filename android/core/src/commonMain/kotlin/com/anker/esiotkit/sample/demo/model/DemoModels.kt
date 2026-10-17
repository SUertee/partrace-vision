package com.anker.esiotkit.sample.demo.model

import com.anker.esiotkit.domain.model.MegaEvent

/** 分析状态 */
enum class AnalysisStatus { SUCCESS, FAILED, TIMEOUT, EMPTY }

/** 事件匹配状态：SDK 检测类型 vs AI 判断 */
enum class MatchStatus { CONFIRMED, FALSE_ALARM, UNKNOWN }

/** 意图识别结果 */
data class IntentResult(
  val startHour: Int,
  val endHour: Int,
  val queryTarget: String,   // people / package / all
  val detectionType: String, // BODY / PET / CAR / ALL
  val understoodIntent: String,
  val dayOffset: Int = 0,    // 相对今天的天数偏移（0=今天，-1=昨天）
)

/** 图片理解结果（Nova Pro） */
data class ImageAnalysisResult(
  val description: String,
  val hasPerson: Boolean,
  val labels: List<String>,
  val sceneType: String,
)

/** 视频理解结果（Nova Pro） */
data class VideoAnalysisResult(
  val description: String,
  val hasPerson: Boolean,
  val behavior: String,      // 靠近 / 远离 / 停留 / 经过 / 无活动
  val activityType: String,  // 工作 / 经过 / 异常 等
)

/** 单条事件的匹配结果 */
data class EventMatchResult(
  val event: MegaEvent,
  val sdkType: String,
  val aiHasPerson: Boolean,
  val aiDescription: String,
  val matchStatus: MatchStatus,
) {
  val eventId: String get() = event.megaEventId
  val timeText: String get() = try { event.startTime.substringAfter(" ") } catch (_: Exception) { "N/A" }
}

/** 方案1 汇总回答 */
data class SummaryResult(
  val answer: String,
  val status: AnalysisStatus,
)

/** 方案2 日报结果 */
data class DailyReportResult(
  val dailyReport: String,
  val status: AnalysisStatus,
  val confirmedCount: Int,
  val falseAlarmCount: Int,
  val keyEvents: List<String>,
  val timeAnalysis: String,
)

/** 方案1/2 统一返回（UI 与日志使用） */
data class SafetyAnalysisResult(
  val answer: String,
  val status: AnalysisStatus,
  val matchResults: List<EventMatchResult>,
  val totalElapsedMs: Long,
)
