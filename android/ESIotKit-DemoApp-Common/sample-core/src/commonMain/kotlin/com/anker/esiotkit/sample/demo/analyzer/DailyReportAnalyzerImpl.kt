package com.anker.esiotkit.sample.demo.analyzer

import com.anker.esiotkit.sample.demo.ai.IAiAnalysisService
import com.anker.esiotkit.sample.demo.data.IEventDataSource
import com.anker.esiotkit.sample.demo.model.AnalysisStatus
import com.anker.esiotkit.sample.demo.model.DailyReportResult
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
 * 方案2：自动日报分析
 *
 * 查询全部事件 → 并行缩略图分析 → 分类匹配 → 时间线 → 日报
 */
class DailyReportAnalyzerImpl(
  private val eventDataSource: IEventDataSource,
  private val aiService: IAiAnalysisService,
) : ISafetyAnalyzer {

  companion object {
    private const val MAX_EVENTS = 10
    private const val QUERY_FAILED_REPORT = "事件查询失败，请稍后重试"
  }

  override suspend fun analyze(query: String): SafetyAnalysisResult {
    val report = generate()
    return SafetyAnalysisResult(
      answer = report.dailyReport,
      status = report.status,
      matchResults = emptyList(),
      totalElapsedMs = 0L,
    )
  }

  suspend fun generate(): DailyReportResult {
    val totalStart = currentTimeMillis()
    appLogger.i("DailyReportAnalyzerImpl/generate/start")
    try {
      val events = try {
        eventDataSource.fetchTodayEvents(0)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val elapsed = currentTimeMillis() - totalStart
        appLogger.e("DailyReportAnalyzerImpl/generate/queryFailure: error=${e.message} totalElapsed=${elapsed}ms")
        DemoProgress.fail("events", "查询失败 · ${e.message ?: e::class.simpleName}")
        return DailyReportResult(
          dailyReport = QUERY_FAILED_REPORT,
          status = AnalysisStatus.FAILED,
          confirmedCount = 0,
          falseAlarmCount = 0,
          keyEvents = emptyList(),
          timeAnalysis = "",
        )
      }
      appLogger.i("DailyReportAnalyzerImpl/generate/events: count=${events.size}")
      if (events.isEmpty()) {
        val elapsed = currentTimeMillis() - totalStart
        appLogger.i("DailyReportAnalyzerImpl/generate/success(empty): totalElapsed=${elapsed}ms")
        return DailyReportResult(
          dailyReport = "今天没有检测到任何活动，家里一切正常",
          status = AnalysisStatus.EMPTY,
          confirmedCount = 0,
          falseAlarmCount = 0,
          keyEvents = emptyList(),
          timeAnalysis = "",
        )
      }

      val targets = events.take(MAX_EVENTS)
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
        appLogger.i("DailyReportAnalyzerImpl/matchResult: eventId=${m.eventId} status=${m.matchStatus}")
      }
      DemoProgress.success(
        "images",
        "${targets.size}/${targets.size} 条完成 · 确认${matches.count { it.matchStatus == MatchStatus.CONFIRMED }} / 误报${matches.count { it.matchStatus == MatchStatus.FALSE_ALARM }}",
      )

      DemoProgress.start("report", "日报生成", "Router 汇总 ${matches.size} 条")
      val report = aiService.generateDailyReport(matches)
      val elapsed = currentTimeMillis() - totalStart
      appLogger.i("DailyReportAnalyzerImpl/generate/success: confirmed=${report.confirmedCount} falseAlarm=${report.falseAlarmCount} reportLength=${report.dailyReport.length} totalElapsed=${elapsed}ms")
      DemoProgress.success("report", "确认${report.confirmedCount} / 误报${report.falseAlarmCount} · ${report.dailyReport.length} 字")
      return report
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      appLogger.e("DailyReportAnalyzerImpl/generate/failure: error=${e.message}")
      DemoProgress.failRunning("日报生成异常 · ${e.message ?: e::class.simpleName}")
      throw e
    }
  }
}
