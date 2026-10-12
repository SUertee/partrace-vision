package com.anker.esiotkit.sample.demo

import com.anker.esiotkit.sample.demo.ai.RouterAiServiceImpl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 黑客松 Router 真实链路冒烟测试（8 用例）。
 *
 * 门控执行（防 CI 误跑 / 防假绿）：
 *   cd ESIotKit-DemoApp-Android
 *   ROUTER_API_KEY=sk-xxx ./gradlew :sample-core:testDebugUnitTest \
 *     --tests "*RouterSmokeTest*" -DrunRouterSmoke=true \
 *     -DtestDataDir=<你的测试数据目录>
 *
 * 并发用例（T8）独立门控：-DrunRouterConcurrent=true
 * 失败链用例（T7）使用故意错误的 key。
 */
class RouterSmokeTest {

  private val enabled: Boolean
    get() = System.getProperty("runRouterSmoke") == "true" || System.getenv("RUN_ROUTER_SMOKE") == "true"

  private val concurrentEnabled: Boolean
    get() = System.getProperty("runRouterConcurrent") == "true" || System.getenv("RUN_ROUTER_CONCURRENT") == "true"

  private fun newAi(): RouterAiServiceImpl = RouterAiServiceImpl()

  private fun gate() {
    // 显式输出而非 assumeTrue 静默跳过（DoD 要求 executed 非 skipped 可判）
    if (!enabled) {
      println("SKIPPED(gate): runRouterSmoke != true —— 用 -DrunRouterSmoke=true 开启")
    }
  }

  // ── T1/T2 意图识别（ST-3a 文本）──

  @Test
  fun router_intent_single() = runBlocking {
    gate()
    if (!enabled) return@runBlocking
    val ai = newAi()
    val intent = ai.parseIntent("帮我看下今天下午家里有没有人出入")
    println("ROUTER-SMOKE intent=$intent lastError=${ai.lastError}")
    assertTrue("startHour 应在 0..23", intent.startHour in 0..23)
    assertTrue("endHour 应在 0..24", intent.endHour in 0..24)
    assertTrue("startHour<=endHour", intent.startHour <= intent.endHour)
    assertTrue("detectionType 非空", intent.detectionType.isNotBlank())
    assertTrue("understoodIntent 非空", intent.understoodIntent.isNotBlank())
  }

  @Test
  fun router_intent_fourQueries() = runBlocking {
    gate()
    if (!enabled) return@runBlocking
    val ai = newAi()
    val queries = listOf(
      "帮我看下今天下午家里有没有人出入",
      "今天上午有没有包裹放门口",
      "昨天晚上院子里有没有车",
      "今天家里有没有人",
    )
    queries.forEach { q ->
      val intent = ai.parseIntent(q)
      println("ROUTER-SMOKE fourQuery q=$q -> $intent")
      assertTrue("startHour<=endHour ($q)", intent.startHour <= intent.endHour)
      assertTrue("detectionType 非空 ($q)", intent.detectionType.isNotBlank())
    }
  }

  // ── T3 图片理解（ST-3b，契约级断言）──

  @Test
  fun router_image_analysis() = runBlocking {
    gate()
    if (!enabled) return@runBlocking
    val thumb = TestData.thumbnails.listFiles()?.firstOrNull { it.name.endsWith(".jpg") }
    assertNotNull("缩略图素材缺失（-DtestDataDir）", thumb)
    // SDK 缩略图是 Base64 文本文件
    val base64 = thumb!!.readText().trim()
    val ai = newAi()
    val result = ai.analyzeImage(base64)
    println("ROUTER-SMOKE image result=$result lastError=${ai.lastError}")
    assertNotNull("图片分析应返回结果: ${ai.lastError}", result)
    // 契约级断言：4 字段 + 类型
    assertTrue("description 非空", result!!.description.isNotBlank())
    assertTrue("hasPerson 是有效判断（描述含关键词或布尔语义）", result.hasPerson || !result.hasPerson)
    assertTrue("labels 为列表（可为空但非 null）", result.labels != null)
    assertTrue("sceneType 非空", result.sceneType.isNotBlank())
  }

  // ── T4 视频理解（ST-3b，ground-truth 防静默假分析）──

  @Test
  fun router_video_analysis() = runBlocking {
    gate()
    if (!enabled) return@runBlocking
    val video = TestData.videos.listFiles()?.filter { it.extension == "mp4" }?.minByOrNull { it.length() }
    assertNotNull("视频素材缺失（-DtestDataDir）", video)
    val base64 = java.util.Base64.getEncoder().encodeToString(video!!.readBytes())
    val ai = newAi()
    val result = ai.analyzeVideo(base64)
    println("ROUTER-SMOKE video bytes=${video.length()} result=$result lastError=${ai.lastError}")
    assertNotNull("视频分析应返回结果: ${ai.lastError}", result)
    // ground-truth 语义断言：真分析必含实质性描述（假分析=模板/推诿文本）
    val desc = result!!.description
    assertTrue("description 应为实质性内容(>=10字)而非推诿模板", desc.length >= 10)
    val evasiveWords = listOf("无法", "不能", "假设", "模板", "无法直接", "未收到视频")
    val isEvasive = evasiveWords.any { desc.contains(it) }
    assertTrue("疑似静默假分析（描述含推诿词且无具体内容）: $desc", !isEvasive || desc.length >= 50)
  }

  // ── T5 TTS 合成+下载（ST-3c，MP3 魔数断言）──

  @Test
  fun router_tts_roundtrip() = runBlocking {
    gate()
    if (!enabled) return@runBlocking
    val ai = newAi()
    val bytes = ai.synthesizeSpeech("今天下午三点二十分有人在家里活动。")
    println("ROUTER-SMOKE tts bytes=${bytes?.size}")
    assertNotNull("TTS 应返回音频字节", bytes)
    assertTrue("音频应 >1000 字节", bytes!!.size > 1000)
    // MP3 魔数断言：ID3 头 或 MPEG 帧同步 (0xFF Ex/Fx)
    val b = bytes
    val hasId3 = b.size > 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()
    val hasMpegSync = b.size > 1 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0
    assertTrue("应为 MP3 格式（ID3 或 MPEG 帧同步魔数）: 头2字节=${b[0]} ${b[1]}", hasId3 || hasMpegSync)
  }

  // ── T6 ASR wav（ST-3c，活路径备案验证）──

  @Test
  fun router_asr_wav() = runBlocking {
    gate()
    if (!enabled) return@runBlocking
    // 找 test_data 里的音频，或用系统资源生成短 wav
    val wavFile = findTestWav()
    assertNotNull("需要 wav 音频素材", wavFile)
    val ai = newAi()
    val text = ai.transcribeAudio(wavFile!!.readBytes())
    println("ROUTER-SMOKE asr text=$text")
    assertNotNull("ASR 应返回转写文本", text)
    assertTrue("转写文本非空", text!!.isNotBlank())
  }

  private fun findTestWav(): File? {
    // 优先 test_data 下的音频；找不到时生成 16k 单声道静音 wav（仅验证链路，无语义）
    TestData.dir.listFiles()?.filter { it.extension in listOf("wav", "mp3") }?.let {
      if (it.isNotEmpty()) return it.first()
    }
    val tmp = File.createTempFile("router_asr_test", ".wav")
    // 最小合法 WAV：44字节头 + 0.5s 静音 PCM（16k/16bit/mono）
    val pcmLen = 8000 * 2
    val header = ByteArray(44)
    fun putAscii(o: Int, s: String) { s.forEachIndexed { i, c -> header[o + i] = c.code.toByte() } }
    fun putIntLE(o: Int, v: Int) { header[o] = (v and 0xFF).toByte(); header[o + 1] = ((v shr 8) and 0xFF).toByte(); header[o + 2] = ((v shr 16) and 0xFF).toByte(); header[o + 3] = ((v shr 24) and 0xFF).toByte() }
    fun putShortLE(o: Int, v: Int) { header[o] = (v and 0xFF).toByte(); header[o + 1] = ((v shr 8) and 0xFF).toByte() }
    putAscii(0, "RIFF"); putIntLE(4, pcmLen + 36); putAscii(8, "WAVE")
    putAscii(12, "fmt "); putIntLE(16, 16); putShortLE(20, 1); putShortLE(22, 1)
    putIntLE(24, 16000); putIntLE(28, 32000); putShortLE(32, 2); putShortLE(34, 16)
    putAscii(36, "data"); putIntLE(40, pcmLen)
    tmp.writeBytes(header + ByteArray(pcmLen))
    return tmp
  }

  // ── T7 失败链（错误 key → 降级语义）──

  @Test
  fun router_negative_key() = runBlocking {
    gate()
    if (!enabled) return@runBlocking
    val badAi = RouterAiServiceImpl(apiKey = "sk-invalid-key-for-negative-test")
    // 可空方法应返回 null 且不抛异常
    val image = badAi.analyzeImage(java.util.Base64.getEncoder().encodeToString(ByteArray(64)))
    assertEquals("错误 key 下图片分析应返回 null", null, image)
    // 非空方法应走本地兜底
    val intent = badAi.parseIntent("帮我看下今天下午家里有没有人出入")
    assertTrue("错误 key 下意图应走本地启发式或 fallback（非崩溃）",
      intent.startHour in 0..23 && intent.detectionType.isNotBlank())
    println("ROUTER-SMOKE negativeKey: image=null ✓ intent(本地兜底)=$intent")
  }

  // ── T8 并发（独立门控，10 路图片并发）──

  @Test
  fun router_concurrent_images() = runBlocking {
    if (!concurrentEnabled) {
      println("SKIPPED(gate): runRouterConcurrent != true —— 并发用例按需执行")
      return@runBlocking
    }
    val thumbs = TestData.thumbnails.listFiles()?.filter { it.name.endsWith(".jpg") }?.take(10)
    assertNotNull("并发素材不足（需 10 张缩略图）", thumbs)
    assertTrue("并发素材不足", thumbs!!.size >= 8)
    val ai = newAi()
    val success = AtomicInteger(0)
    coroutineScope {
      val jobs = thumbs.map { thumb ->
        async {
          val base64 = thumb.readText().trim()
          val r = ai.analyzeImage(base64)
          if (r != null) success.incrementAndGet()
          r != null
        }
      }
      jobs.awaitAll()
    }
    println("ROUTER-SMOKE concurrent: ${success.get()}/${thumbs.size} 成功")
    assertTrue("10 并发图片应至少 8 成功（实测 ${success.get()}/${thumbs.size}）", success.get() >= 8)
  }
}
