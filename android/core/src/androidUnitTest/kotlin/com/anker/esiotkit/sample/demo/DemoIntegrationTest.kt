package com.anker.esiotkit.sample.demo

import com.anker.esiotkit.core.entities.event.DetectionType
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.domain.model.MegaPicture
import com.anker.esiotkit.sample.demo.ai.IAiAnalysisService
import com.anker.esiotkit.sample.demo.analyzer.DailyReportAnalyzerImpl
import com.anker.esiotkit.sample.demo.analyzer.SafetyAnalyzerImpl
import com.anker.esiotkit.sample.demo.data.IEventDataSource
import com.anker.esiotkit.sample.demo.model.AnalysisStatus
import com.anker.esiotkit.sample.demo.model.DailyReportResult
import com.anker.esiotkit.sample.demo.model.EventMatchResult
import com.anker.esiotkit.sample.demo.model.ImageAnalysisResult
import com.anker.esiotkit.sample.demo.model.IntentResult
import com.anker.esiotkit.sample.demo.model.MatchStatus
import com.anker.esiotkit.sample.demo.model.SummaryResult
import com.anker.esiotkit.sample.demo.model.VideoAnalysisResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DemoIntegrationTest {

  private fun eventMock(
    id: String,
    timeText: String,
    timestampMs: Long,
    hasThumbnail: Boolean = true,
  ): MegaEvent = mockk(relaxed = true) {
    every { megaEventId } returns id
    every { startTime } returns timeText
    every { startTimestamp } returns timestampMs
    every { deviceSn } returns "SN1"
    every { deviceName } returns "Cam1"
    every { stringExtra } returns "motion event"
    every { pictures } returns emptyList()
    every { thumbnail } returns mockk<MegaPicture>(relaxed = true) {
      every { picturePath } returns if (hasThumbnail) "path/$id" else ""
      every { parentSn } returns "SN1"
      every { detectionType } returns DetectionType.HUMAN.value
    }
  }

  private fun aiMock(
    analyzeImageResult: ImageAnalysisResult?,
    answer: String = "今天下午15:26有人在家里",
  ): IAiAnalysisService = mockk(relaxed = true) {
    coEvery { parseIntent(any()) } returns IntentResult(14, 18, "people", "BODY", "查看下午", 0)
    coEvery { analyzeImage(any()) } returns analyzeImageResult
    coEvery { summarize(any(), any()) } returns SummaryResult(answer, AnalysisStatus.SUCCESS)
    coEvery { generateDailyReport(any()) } returns DailyReportResult(
      dailyReport = "今日共 3 条事件：确认 3 条，疑似误报 0 条。",
      status = AnalysisStatus.SUCCESS,
      confirmedCount = 3,
      falseAlarmCount = 0,
      keyEvents = emptyList(),
      timeAnalysis = "15时×3",
    )
  }

  private fun todayAt(hour: Int, minute: Int = 0): Long =
    com.anker.esiotkit.sample.demo.analyzer.TimeWindowFilter.dayStartMs(0) +
      hour * 3_600_000L + minute * 60_000L

  @Test
  fun i1_voiceQuery_mainChain() = runBlocking {
    val events = listOf(eventMock("e1", "2026-09-16 15:26:01", todayAt(15, 26)))
    val dataSource = mockk<IEventDataSource>(relaxed = true) {
      coEvery { fetchTodayEvents(any(), any()) } returns events
      coEvery { fetchThumbnailBase64(any()) } returns "dGh1bWI="
      coEvery { isDeviceOnline(any()) } returns true
    }
    val analyzer = SafetyAnalyzerImpl(dataSource, aiMock(ImageAnalysisResult("有人经过", true, listOf("Person"), "室外")))
    val result = analyzer.analyze("帮我看下今天下午家里有没有人出入")
    assertEquals(AnalysisStatus.SUCCESS, result.status)
    assertTrue(result.answer.contains("有人"))
    assertEquals(1, result.matchResults.size)
    assertEquals(MatchStatus.CONFIRMED, result.matchResults.first().matchStatus)
  }

  @Test
  fun i2_voiceQuery_emptyRange() = runBlocking {
    val dataSource = mockk<IEventDataSource>(relaxed = true) {
      coEvery { fetchTodayEvents(any(), any()) } returns emptyList()
    }
    val analyzer = SafetyAnalyzerImpl(dataSource, aiMock(null))
    val result = analyzer.analyze("昨天凌晨3点有没有人")
    assertEquals("该时段没有检测到活动", result.answer)
    assertEquals(AnalysisStatus.EMPTY, result.status)
  }

  @Test
  fun i3_dailyReport_mainChain() = runBlocking {
    val events = listOf(
      eventMock("e1", "2026-09-16 15:26:01", todayAt(15, 26)),
      eventMock("e2", "2026-09-16 16:10:01", todayAt(16, 10)),
      eventMock("e3", "2026-09-16 17:05:01", todayAt(17, 5)),
    )
    val dataSource = mockk<IEventDataSource>(relaxed = true) {
      coEvery { fetchTodayEvents(any(), any()) } returns events
      coEvery { fetchThumbnailBase64(any()) } returns "dGh1bWI="
    }
    val analyzer = DailyReportAnalyzerImpl(dataSource, aiMock(ImageAnalysisResult("有人", true, emptyList(), "室内")))
    val report = analyzer.generate()
    assertEquals(AnalysisStatus.SUCCESS, report.status)
    assertTrue(report.dailyReport.isNotEmpty())
    assertEquals(3, report.confirmedCount)
  }

  @Test
  fun i4_degrade_thumbnailNull() = runBlocking {
    val events = listOf(eventMock("e1", "2026-09-16 15:26:01", todayAt(15, 26), hasThumbnail = false))
    val dataSource = mockk<IEventDataSource>(relaxed = true) {
      coEvery { fetchTodayEvents(any(), any()) } returns events
      coEvery { fetchThumbnailBase64(any()) } returns null
      coEvery { isDeviceOnline(any()) } returns true
    }
    val analyzer = SafetyAnalyzerImpl(dataSource, aiMock(ImageAnalysisResult("有人", true, emptyList(), "")))
    val result = analyzer.analyze("下午有没有人")
    assertEquals(MatchStatus.UNKNOWN, result.matchResults.first().matchStatus)
    assertTrue(result.matchResults.first().aiDescription.contains("文本降级"))
  }

  @Test
  fun v7_degrade_aiTimeout() = runBlocking {
    val events = listOf(eventMock("e1", "2026-09-16 15:26:01", todayAt(15, 26)))
    val dataSource = mockk<IEventDataSource>(relaxed = true) {
      coEvery { fetchTodayEvents(any(), any()) } returns events
      coEvery { fetchThumbnailBase64(any()) } returns "dGh1bWI="
      coEvery { isDeviceOnline(any()) } returns true
    }
    val analyzer = SafetyAnalyzerImpl(dataSource, aiMock(null))
    val result = analyzer.analyze("下午有没有人")
    assertTrue(result.matchResults.first().aiDescription.contains("文本降级"))
  }

  @Test
  fun v8_degrade_deviceOffline() = runBlocking {
    val events = listOf(eventMock("e1", "2026-09-16 15:26:01", todayAt(15, 26)))
    val dataSource = mockk<IEventDataSource>(relaxed = true) {
      coEvery { fetchTodayEvents(any(), any()) } returns events
      coEvery { isDeviceOnline(any()) } returns false
    }
    val analyzer = SafetyAnalyzerImpl(dataSource, aiMock(null))
    val result = analyzer.analyze("下午有没有人")
    assertEquals("设备离线，请检查设备连接", result.answer)
    assertEquals(AnalysisStatus.FAILED, result.status)
  }

  @Test
  fun u10_base64FileDecodesToJpeg() {
    val file = TestData.thumbnails.listFiles()?.firstOrNull { it.name.endsWith(".jpg") }
    assumeTrue("skip: 未找到测试缩略图（可用 -DtestDataDir=<dir> 指定 test_data 目录）", file != null)
    val text = file!!.readText().trim()
    val bytes = kotlin.io.encoding.Base64.Default.decode(text)
    assertTrue("解码后应为非空 JPEG", bytes.size > 100)
    assertEquals("JPEG magic FFD8", 0xFF.toByte(), bytes[0])
    assertEquals("JPEG magic FFD8", 0xD8.toByte(), bytes[1])
  }
}
