package com.anker.esiotkit.sample.demo.analyzer

import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.sample.demo.model.IntentResult
import com.anker.esiotkit.support.ktx.toDateStart
import com.anker.esiotkit.support.platform.currentTimeMillis
import com.anker.esiotkit.support.utils.DateUtil

/**
 * 时间窗口过滤（纯逻辑，可离线单测）。
 *
 * 事件时间以 `startTime` 文本（本地 "yyyy-MM-dd HH:mm:ss"）与 `startTimestamp`（epoch ms）表达。
 */
object TimeWindowFilter {

  data class EventTimeView(
    val id: String,
    val startTimeText: String,
    val startTimestampMs: Long,
  )

  fun toView(event: MegaEvent): EventTimeView =
    EventTimeView(event.megaEventId, event.startTime, event.startTimestamp)

  /** 从 "yyyy-MM-dd HH:mm:ss" 提取小时；解析失败返回 null */
  fun parseHour(timeText: String): Int? {
    val timePart = timeText.substringAfter(" ", timeText)
    return timePart.substringBefore(":").trim().toIntOrNull()?.takeIf { it in 0..23 }
  }

  fun inHourWindow(hour: Int, startHour: Int, endHour: Int): Boolean {
    if (endHour <= startHour) return false
    return hour >= startHour && hour < endHour
  }

  /** 当日 0 点（本地时区）epoch ms；dayOffset: 0=今天，-1=昨天 */
  fun dayStartMs(dayOffset: Int, nowMs: Long = currentTimeMillis()): Long {
    val dateTime = DateUtil.timeMillisToDateTimeString(nowMs)
    val todayStart = DateUtil.dateTimeStringToTimeMillis(
      try { dateTime.toDateStart() } catch (_: Exception) { dateTime }
    )
    return todayStart - dayOffset * 24L * 60 * 60 * 1000
  }

  fun filterViews(
    views: List<EventTimeView>,
    dayOffset: Int,
    startHour: Int,
    endHour: Int,
    nowMs: Long = currentTimeMillis(),
  ): List<EventTimeView> {
    val dayStart = dayStartMs(dayOffset, nowMs)
    val dayEnd = dayStart + 24L * 60 * 60 * 1000
    return views.filter { view ->
      val hour = parseHour(view.startTimeText)
        ?: view.startTimestampMs.takeIf { it > 0 }?.let { ((it - dayStart) / 3_600_000L).toInt() }
        ?: return@filter false

      val inDay = view.startTimestampMs <= 0 ||
        (view.startTimestampMs in dayStart until dayEnd)
      inDay && inHourWindow(hour, startHour, endHour)
    }
  }

  fun filterEvents(
    events: List<MegaEvent>,
    intent: IntentResult,
    nowMs: Long = currentTimeMillis(),
  ): List<MegaEvent> {
    val views = events.map { toView(it) }
    val keptIds = filterViews(views, intent.dayOffset, intent.startHour, intent.endHour, nowMs)
      .map { it.id }
      .toSet()
    return events.filter { it.megaEventId in keptIds }
  }
}
