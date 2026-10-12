package com.anker.esiotkit.sample.demo

import com.anker.esiotkit.core.entities.event.DetectionType
import com.anker.esiotkit.sample.demo.ai.AwsAiServiceImpl
import com.anker.esiotkit.sample.demo.analyzer.MatchRules
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * §8 验收 V5：真实测试数据（test_data/）事件分类执行。
 *
 * 默认跳过；设置 RUN_AWS_SMOKE=true 后手动执行：
 *   RUN_AWS_SMOKE=true ./gradlew :sample-core:testDebugUnitTest --tests "*AwsRealDataTest*"
 */
class AwsRealDataTest {

  private val enabled: Boolean
    get() = System.getProperty("runAwsSmoke") == "true" || System.getenv("RUN_AWS_SMOKE") == "true"

  private data class RawEvent(
    val index: Int,
    val time: String,
    val type: String,
    val thumbnail: String,
  )

  private fun parseEvents(txt: File): List<RawEvent> {
    val events = mutableListOf<RawEvent>()
    val regex = Regex("""event_(\d+): time=([^,]+), type=([^,]+), thumbnail=([^,]+)""")
    txt.readLines().forEach { line ->
      regex.find(line)?.let {
        events.add(
          RawEvent(
            index = it.groupValues[1].toInt(),
            time = it.groupValues[2].trim(),
            type = it.groupValues[3].trim(),
            thumbnail = it.groupValues[4].trim(),
          )
        )
      }
    }
    return events
  }

  private fun detectionValue(type: String): Int = when (type) {
    "BODY" -> DetectionType.HUMAN.value
    "PET" -> DetectionType.PET.value
    "VEHICLE" -> DetectionType.CAR.value
    else -> DetectionType.MOTION.value
  }

  @Test
  fun v5_realEventsClassification() = runBlocking {
    assumeTrue("skip: RUN_AWS_SMOKE != true", enabled)

    val dataDir = TestData.dir
    val eventsTxt = File(dataDir, "events.txt")
    assumeTrue("skip: 未找到 test_data/events.txt（可用 -DtestDataDir=<dir> 指定）", eventsTxt.exists())
    val events = parseEvents(eventsTxt)
    println("AWS-DATA events parsed: ${events.size}")

    val ai = AwsAiServiceImpl()
    val results = mutableListOf<Triple<String, String, Boolean?>>()

    events.take(10).forEach { raw ->
      val thumbFile = File(dataDir, "thumbnails").listFiles()
        ?.firstOrNull { it.name.startsWith("thumb_${raw.index}_") }
      val base64 = thumbFile?.readText()?.trim()
      if (base64.isNullOrBlank()) {
        println("AWS-DATA event_${raw.index}: thumbnail missing, skip")
        return@forEach
      }
      val analysis = ai.analyzeImage(base64)
      val hasPerson = analysis?.hasPerson
      val status = when {
        hasPerson == null -> "AI_NULL(${ai.lastError?.take(60)})"
        MatchRules.matchEvent(raw.type, hasPerson) == com.anker.esiotkit.sample.demo.model.MatchStatus.CONFIRMED -> "CONFIRMED"
        else -> "FALSE_ALARM"
      }
      println("AWS-DATA event_${raw.index}: time=${raw.time} sdkType=${raw.type} aiHasPerson=$hasPerson status=$status desc=${analysis?.description?.take(50)}")
      results.add(Triple(raw.type, status, hasPerson))
    }

    val analyzed = results.count { it.third != null }
    val confirmed = results.count { it.second == "CONFIRMED" }
    val falseAlarm = results.count { it.second == "FALSE_ALARM" }
    println("AWS-DATA summary: analyzed=$analyzed confirmed=$confirmed falseAlarm=$falseAlarm")

    assertTrue("应至少分析 8 条事件（实际 $analyzed）", analyzed >= 8)
    assertTrue("确认+误报应等于已分析数", confirmed + falseAlarm == analyzed)
  }
}
