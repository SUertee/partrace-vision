package com.anker.esiotkit.sample.demo

import com.anker.esiotkit.sample.demo.ai.AwsAiServiceImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * AWS 真实链路冒烟（§8 验收 V2/V5 的本地可执行部分）。
 *
 * 默认跳过；设置环境变量 RUN_AWS_SMOKE=true 后手动执行：
 *   RUN_AWS_SMOKE=true ./gradlew :sample-core:testDebugUnitTest --tests "*AwsRealSmokeTest*"
 */
class AwsRealSmokeTest {

  private val enabled: Boolean
    get() = System.getProperty("runAwsSmoke") == "true" || System.getenv("RUN_AWS_SMOKE") == "true"

  @Test
  fun smoke_intentViaNovaLite() = runBlocking {
    assumeTrue("skip: RUN_AWS_SMOKE != true", enabled)
    val ai = AwsAiServiceImpl()
    val intent = ai.parseIntent("帮我看下今天下午家里有没有人出入")
    println("AWS-SMOKE intent=$intent lastError=${ai.lastError}")
    assertTrue("startHour 应在 0..23", intent.startHour in 0..23)
    assertTrue("endHour 应在 0..24", intent.endHour in 0..24)
  }

  @Test
  fun smoke_intentFourQueries() = runBlocking {
    assumeTrue("skip: RUN_AWS_SMOKE != true", enabled)
    val ai = AwsAiServiceImpl()
    val queries = listOf(
      "帮我看下今天下午家里有没有人出入",
      "今天上午有没有包裹放门口",
      "昨天晚上院子里有没有车",
      "今天家里有没有人",
    )
    queries.forEach { q ->
      val intent = ai.parseIntent(q)
      println("AWS-SMOKE fourQuery q=$q -> $intent lastError=${ai.lastError}")
      assertTrue("startHour<=endHour ($q)", intent.startHour <= intent.endHour)
      assertTrue("detectionType 非空 ($q)", intent.detectionType.isNotBlank())
    }
  }

  @Test
  fun smoke_imageViaNovaPro() = runBlocking {
    assumeTrue("skip: RUN_AWS_SMOKE != true", enabled)
    val file = TestData.thumbnails
      .listFiles()?.firstOrNull { it.name.endsWith(".jpg") }
    assertNotNull(file)
    val base64 = file!!.readText().trim()
    val ai = AwsAiServiceImpl()
    val result = ai.analyzeImage(base64)
    println("AWS-SMOKE image result=$result lastError=${ai.lastError}")
    assertNotNull("Nova Pro 图片分析应返回结果: ${ai.lastError}", result)
  }

  @Test
  fun smoke_videoViaNovaPro() = runBlocking {
    assumeTrue("skip: RUN_AWS_SMOKE != true", enabled)
    val dir = TestData.videos
    val file = dir.listFiles()?.filter { it.extension == "mp4" }?.minByOrNull { it.length() }
    assertNotNull("test_data 视频不存在", file)
    val base64 = java.util.Base64.getEncoder().encodeToString(file!!.readBytes())
    val ai = AwsAiServiceImpl()
    val result = ai.analyzeVideo(base64)
    println("AWS-SMOKE video bytes=${file.length()} result=$result lastError=${ai.lastError}")
    assertNotNull("Nova Pro 视频分析应返回结果: ${ai.lastError}", result)
  }

  @Test
  fun smoke_pollyTts() = runBlocking {
    assumeTrue("skip: RUN_AWS_SMOKE != true", enabled)
    val ai = AwsAiServiceImpl()
    val audio = ai.synthesizeSpeech("家里一切正常")
    println("AWS-SMOKE tts bytes=${audio?.size}")
    assertNotNull("Polly 应返回音频", audio)
    assertTrue("音频应非空", (audio?.size ?: 0) > 1000)
  }
}
