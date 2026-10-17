package com.anker.esiotkit.sample.demo.analyzer

import com.anker.esiotkit.sample.demo.ai.IAiAnalysisService
import com.anker.esiotkit.sample.demo.data.IEventDataSource
import com.anker.esiotkit.sample.demo.model.AnalysisStatus
import com.anker.esiotkit.sample.demo.model.EventMatchResult
import com.anker.esiotkit.sample.demo.model.MatchStatus
import com.anker.esiotkit.sample.demo.model.SafetyAnalysisResult
import com.anker.esiotkit.sample.demo.progress.DemoProgress
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.platform.currentTimeMillis
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 方案1：语音问答安心分析
 *
 * 查询 → 时间过滤 → 并行图片分析 → 匹配 → 汇总回答
 * 降级：无事件 → "没有检测到活动"；缩略图/AI 失败 → 文本降级；设备离线 → 提示
 */
class SafetyAnalyzerImpl(
  private val eventDataSource: IEventDataSource,
  private val aiService: IAiAnalysisService,
) : ISafetyAnalyzer {

  companion object {
    private const val MAX_EVENTS = 5
    private const val EMPTY_ANSWER = "该时段没有检测到活动"
    private const val OFFLINE_ANSWER = "设备离线，请检查设备连接"
    private const val QUERY_FAILED_ANSWER = "事件查询失败，请稍后重试"
  }

  override suspend fun analyze(query: String): SafetyAnalysisResult {
    val totalStart = currentTimeMillis()
    appLogger.i("SafetyAnalyzerImpl/analyze/start: query=$query")
    try {
      DemoProgress.start("intent", "意图解析", "Router 语义理解")
      val intent = aiService.parseIntent(query)
      DemoProgress.success(
        "intent",
        "时段 ${intent.startHour}-${intent.endHour} · 目标=${intent.queryTarget} · dayOffset=${intent.dayOffset}",
      )

      val allEvents = try {
        eventDataSource.fetchTodayEvents(dayOffset = intent.dayOffset)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val elapsed = currentTimeMillis() - totalStart
        appLogger.e("SafetyAnalyzerImpl/analyze/queryFailure: query=$query error=${e.message} totalElapsed=${elapsed}ms")
        DemoProgress.fail("events", "查询失败 · ${e.message ?: e::class.simpleName}")
        return SafetyAnalysisResult(QUERY_FAILED_ANSWER, AnalysisStatus.FAILED, emptyList(), elapsed)
      }

      DemoProgress.start("filter", "时间窗过滤", "窗口 ${intent.startHour}:00 - ${intent.endHour}:00")
      val filtered = TimeWindowFilter.filterEvents(allEvents, intent)
      appLogger.i("SafetyAnalyzerImpl/analyze/filter: total=${allEvents.size} filtered=${filtered.size} window=${intent.startHour}-${intent.endHour} dayOffset=${intent.dayOffset}")
      DemoProgress.success("filter", "命中 ${filtered.size}/${allEvents.size} 条")

      if (filtered.isEmpty()) {
        val elapsed = currentTimeMillis() - totalStart
        appLogger.i("SafetyAnalyzerImpl/analyze/success(empty): answer=$EMPTY_ANSWER totalElapsed=${elapsed}ms")
        return SafetyAnalysisResult(EMPTY_ANSWER, AnalysisStatus.EMPTY, emptyList(), elapsed)
      }

      val deviceSns = filtered.map { it.deviceSn }.distinct()
      DemoProgress.start("online", "设备在线校验", "涉及 ${deviceSns.size} 台设备")
      val onlineCount = deviceSns.count { eventDataSource.isDeviceOnline(it) }
      if (onlineCount == 0) {
        val elapsed = currentTimeMillis() - totalStart
        appLogger.w("SafetyAnalyzerImpl/analyze/offline: devices=$deviceSns")
        DemoProgress.fail("online", "全部设备离线")
        return SafetyAnalysisResult(OFFLINE_ANSWER, AnalysisStatus.FAILED, emptyList(), elapsed)
      }
      DemoProgress.success("online", "在线 $onlineCount/${deviceSns.size}")

      val targets = filtered.take(MAX_EVENTS)
      DemoProgress.start("images", "图片理解", "Router 逐条分析 · 共 ${targets.size} 条")
      val done = atomic(0)
      val matches = coroutineScope {
        targets.map { event ->
          async {
            val result = EventAnalysisSupport.analyzeEvent(aiService, eventDataSource, event)
            DemoProgress.update("images", "已完成 ${done.incrementAndGet()}/${targets.size} 条")
            result
          }
        }.awaitAll()
      }
      matches.forEach { m ->
        appLogger.i("SafetyAnalyzerImpl/matchResult: eventId=${m.eventId} status=${m.matchStatus}")
      }
      DemoProgress.success(
        "images",
        "${targets.size}/${targets.size} 条完成 · 确认${matches.count { it.matchStatus == MatchStatus.CONFIRMED }} / 误报${matches.count { it.matchStatus == MatchStatus.FALSE_ALARM }}",
      )

      DemoProgress.start("summary", "结论汇总", "Router 生成回答 · ${matches.size} 条依据")
      val summary = aiService.summarize(query, matches)
      val elapsed = currentTimeMillis() - totalStart
      appLogger.i("SafetyAnalyzerImpl/analyze/success: answer=${summary.answer} totalElapsed=${elapsed}ms")
      DemoProgress.success("summary", summary.answer)
      return SafetyAnalysisResult(summary.answer, summary.status, matches, elapsed)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      appLogger.e("SafetyAnalyzerImpl/analyze/failure: query=$query error=${e.message}")
      DemoProgress.failRunning("分析异常 · ${e.message ?: e::class.simpleName}")
      throw e
    }
  }
}
