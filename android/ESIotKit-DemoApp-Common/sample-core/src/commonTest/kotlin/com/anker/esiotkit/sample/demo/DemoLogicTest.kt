package com.anker.esiotkit.sample.demo

import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.sample.demo.ai.IAiAnalysisService
import com.anker.esiotkit.sample.demo.ai.IntentParser
import com.anker.esiotkit.sample.demo.analyzer.MatchRules
import com.anker.esiotkit.sample.demo.analyzer.SafetyAnalyzerImpl
import com.anker.esiotkit.sample.demo.analyzer.TimeWindowFilter
import com.anker.esiotkit.sample.demo.data.IEventDataSource
import com.anker.esiotkit.sample.demo.model.AnalysisStatus
import com.anker.esiotkit.sample.demo.model.DailyReportResult
import com.anker.esiotkit.sample.demo.model.EventMatchResult
import com.anker.esiotkit.sample.demo.model.ImageAnalysisResult
import com.anker.esiotkit.sample.demo.model.IntentResult
import com.anker.esiotkit.sample.demo.model.MatchStatus
import com.anker.esiotkit.sample.demo.model.SummaryResult
import com.anker.esiotkit.sample.demo.model.VideoAnalysisResult
import com.anker.esiotkit.sample.demo.util.JsonCleaner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

class DemoLogicTest {

  private class FakeDataSource(private val events: List<MegaEvent>) : IEventDataSource {
    override suspend fun fetchTodayEvents(dayOffset: Int, deviceSn: String): List<MegaEvent> = events
    override suspend fun fetchThumbnailBase64(event: MegaEvent): String? = null
    override suspend fun downloadEventVideo(event: MegaEvent): String? = null
    override suspend fun isDeviceOnline(deviceSn: String): Boolean = true
  }

  private class FakeAiService(
    private val intent: IntentResult = IntentResult(0, 24, "all", "ALL", "", 0),
  ) : IAiAnalysisService {
    override suspend fun parseIntent(userText: String): IntentResult = intent
    override suspend fun analyzeImage(imageBase64: String): ImageAnalysisResult? = null
    override suspend fun analyzeVideo(videoBase64: String): VideoAnalysisResult? = null
    override suspend fun summarize(query: String, eventSummaries: List<EventMatchResult>): SummaryResult =
      SummaryResult("test", AnalysisStatus.SUCCESS)
    override suspend fun generateDailyReport(events: List<EventMatchResult>): DailyReportResult =
      DailyReportResult("test", AnalysisStatus.SUCCESS, 0, 0, emptyList(), "")
    override suspend fun transcribeAudio(audioData: ByteArray): String? = null
    override suspend fun synthesizeSpeech(text: String): ByteArray? = null
  }

  @Test
  fun u1_intentParser_localAfternoonPeople() {
    val intent = IntentParser.parseLocal("帮我看下今天下午家里有没有人出入")
    assertNotNull(intent)
    assertEquals(14, intent.startHour)
    assertEquals(18, intent.endHour)
    assertEquals("BODY", intent.detectionType)
  }

  @Test
  fun u2_intentParser_localMorningPackage() {
    val intent = IntentParser.parseLocal("今天上午有没有包裹放门口")
    assertNotNull(intent)
    assertEquals("package", intent.queryTarget)
    assertEquals(6, intent.startHour)
    assertEquals(12, intent.endHour)
  }

  @Test
  fun u3_match_confirmedWhenPersonDetected() {
    assertEquals(MatchStatus.CONFIRMED, MatchRules.matchEvent("BODY", true))
  }

  @Test
  fun u4_match_falseAlarmWhenNoPerson() {
    assertEquals(MatchStatus.FALSE_ALARM, MatchRules.matchEvent("BODY", false))
  }

  @Test
  fun u5_jsonClean_standard() {
    assertEquals("""{"summary":"ok"}""", JsonCleaner.cleanJson("""{"summary":"ok"}"""))
  }

  @Test
  fun u6_jsonClean_markdownWrapped() {
    assertEquals("""{"summary":"ok"}""", JsonCleaner.cleanJson("```json\n{\"summary\":\"ok\"}\n```"))
  }

  @Test
  fun u7_jsonClean_extraText() {
    assertEquals("""{"summary":"ok"}""", JsonCleaner.cleanJson("根据分析结果：{\"summary\":\"ok\"}"))
  }

  @Test
  fun u8_timeWindowFilter_keepsOnly15_26() {
    val times = listOf("09:00", "10:15", "11:30", "12:45", "13:10", "15:26", "19:00", "20:30", "21:45", "23:10")
    val views = times.mapIndexed { index, time ->
      TimeWindowFilter.EventTimeView("e$index", "2026-09-16 $time:00", 0L)
    }
    val kept = TimeWindowFilter.filterViews(views, dayOffset = 0, startHour = 14, endHour = 18)
    assertEquals(1, kept.size)
    assertEquals("e5", kept.first().id)
  }

  @Test
  fun u9_emptyEvents_answerNoActivity() = runBlocking {
    val analyzer = SafetyAnalyzerImpl(FakeDataSource(emptyList()), FakeAiService())
    val result = analyzer.analyze("帮我看下今天下午家里有没有人出入")
    assertEquals("该时段没有检测到活动", result.answer)
    assertEquals(AnalysisStatus.EMPTY, result.status)
  }

  @Test
  fun u9b_emptyQueryStillNoCrash() = runBlocking {
    val analyzer = SafetyAnalyzerImpl(FakeDataSource(emptyList()), FakeAiService())
    val result = analyzer.analyze("")
    assertEquals(AnalysisStatus.EMPTY, result.status)
  }
}
