package com.anker.esiotkit.sample.demo.ai

import com.anker.esiotkit.sample.demo.RouterConfig
import com.anker.esiotkit.sample.demo.model.AnalysisStatus
import com.anker.esiotkit.sample.demo.model.DailyReportResult
import com.anker.esiotkit.sample.demo.model.EventMatchResult
import com.anker.esiotkit.sample.demo.model.ImageAnalysisResult
import com.anker.esiotkit.sample.demo.model.IntentResult
import com.anker.esiotkit.sample.demo.model.MatchStatus
import com.anker.esiotkit.sample.demo.model.SummaryResult
import com.anker.esiotkit.sample.demo.model.VideoAnalysisResult
import com.anker.esiotkit.sample.demo.net.createHttpClient
import com.anker.esiotkit.sample.demo.util.JsonCleaner
import com.anker.esiotkit.support.log.httpLogger
import com.anker.esiotkit.support.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 黑客松 Router AI 服务实现（nova-pro 文本/图片/视频 + qwen-audio TTS/ASR）。
 *
 * 关键实现规范（105+ 次实测背书）：
 * 1. 视频/图片统一走 /chat/completions 的 image_url 字段（⚠️ 旧 video_url 字段被网关静默丢弃返回假分析）
 * 2. max_tokens≥2000（实测规范，防推理型模型空响应；nova-pro 主力实测 0 次）
 * 3. TTS 返回 OSS URL（http 明文）→ 下载前强制 http→https 改写（iOS ATS 禁明文，实测 https TLS 可用）
 * 4. TTS 下载与合成分步计时，合成后立即下载（URL 有效期分钟级，实测下载毫秒级）
 * 5. 失败语义与 AWS 实现逐字对齐（见各方法降级链）
 */
class RouterAiServiceImpl(
  private val client: HttpClient = createHttpClient(),
  private val apiKey: String = RouterConfig.resolveApiKey(),
) : IAiAnalysisService {

  companion object {
    private const val TAG = "RouterAiServiceImpl"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  }

  internal var lastError: String? = null

  // ───────────────────────── 3a 文本三方法 ─────────────────────────

  override suspend fun parseIntent(userText: String): IntentResult {
    val local = IntentParser.parseLocal(userText)
    httpLogger.i("$TAG/parseIntent/start: userText=$userText")

    val raw = chatText(AiPrompts.intent(userText), RouterConfig.MODEL_TEXT,
      RouterConfig.TEMP_INTENT, RouterConfig.TEXT_TIMEOUT_MS)
    val ai = raw?.let { IntentParser.parseAiJson(it) }
    val result = when {
      ai != null -> ai.copy(dayOffset = local?.dayOffset ?: 0)
      local != null -> local
      else -> AiResponseParsers.fallbackIntent()
    }
    httpLogger.i("$TAG/parseIntent/success: startHour=${result.startHour} endHour=${result.endHour} target=${result.queryTarget} detection=${result.detectionType} dayOffset=${result.dayOffset}")
    return result
  }

  override suspend fun summarize(query: String, eventSummaries: List<EventMatchResult>): SummaryResult {
    httpLogger.i("$TAG/summarize/start: query=$query eventCount=${eventSummaries.size}")
    if (eventSummaries.isEmpty()) {
      return SummaryResult("该时段没有检测到活动", AnalysisStatus.EMPTY)
    }
    val raw = chatText(AiPrompts.summary(query, eventSummaries), RouterConfig.MODEL_TEXT,
      RouterConfig.TEMP_SUMMARY, RouterConfig.TEXT_TIMEOUT_MS)
    val cleaned = raw?.let { JsonCleaner.cleanJsonOrNull(it) }
    if (cleaned != null) {
      try {
        val obj = json.parseToJsonElement(cleaned).jsonObject
        val answer = obj["answer"]?.jsonPrimitive?.content
        if (!answer.isNullOrBlank()) {
          httpLogger.i("$TAG/summarize/success: answer=$answer")
          return SummaryResult(answer, AnalysisStatus.SUCCESS)
        }
      } catch (e: Exception) {
        httpLogger.e("$TAG/summarize/failure: reason=parse_error error=${e.message} raw=${raw.take(120)}")
      }
    }
    val lenientAnswer = raw?.let { extractJsonStringField(it, "answer") }
    if (lenientAnswer != null) {
      httpLogger.w("$TAG/summarize/fallback: reason=lenient_extract, answer=$lenientAnswer")
      return SummaryResult(lenientAnswer, AnalysisStatus.SUCCESS)
    }
    val fallback = AiResponseParsers.fallbackSummary(eventSummaries)
    httpLogger.w("$TAG/summarize/degraded: ${fallback.answer}")
    return fallback
  }

  override suspend fun generateDailyReport(events: List<EventMatchResult>): DailyReportResult {
    httpLogger.i("$TAG/generateDailyReport/start: eventCount=${events.size}")
    if (events.isEmpty()) {
      return DailyReportResult(
        dailyReport = "今天没有检测到任何活动，家里一切正常",
        status = AnalysisStatus.EMPTY,
        confirmedCount = 0, falseAlarmCount = 0, keyEvents = emptyList(), timeAnalysis = "",
      )
    }
    val confirmed = events.count { it.matchStatus == MatchStatus.CONFIRMED }
    val falseAlarm = events.count { it.matchStatus == MatchStatus.FALSE_ALARM }
    val timeAnalysis = AiResponseParsers.timeAnalysis(events)

    val raw = chatText(AiPrompts.dailyReport(events, confirmed, falseAlarm, timeAnalysis),
      RouterConfig.MODEL_TEXT, RouterConfig.TEMP_SUMMARY, RouterConfig.TEXT_TIMEOUT_MS)
    val cleaned = raw?.let { JsonCleaner.cleanJsonOrNull(it) }
    if (cleaned != null) {
      try {
        val obj = json.parseToJsonElement(cleaned).jsonObject
        val report = obj["daily_report"]?.jsonPrimitive?.content
        if (!report.isNullOrBlank()) {
          httpLogger.i("$TAG/generateDailyReport/success: reportLength=${report.length}")
          return DailyReportResult(
            dailyReport = report, status = AnalysisStatus.SUCCESS,
            confirmedCount = confirmed, falseAlarmCount = falseAlarm,
            keyEvents = events.take(5).map { "${it.timeText} ${it.aiDescription}" },
            timeAnalysis = timeAnalysis,
          )
        }
      } catch (e: Exception) {
        httpLogger.e("$TAG/generateDailyReport/failure: reason=parse_error error=${e.message} raw=${raw.take(120)}")
      }
    }
    val lenientReport = raw?.let { extractJsonStringField(it, "daily_report") }
    if (lenientReport != null) {
      httpLogger.w("$TAG/generateDailyReport/fallback: reason=lenient_extract, reportLength=${lenientReport.length}")
      return DailyReportResult(
        dailyReport = lenientReport, status = AnalysisStatus.SUCCESS,
        confirmedCount = confirmed, falseAlarmCount = falseAlarm,
        keyEvents = events.take(5).map { "${it.timeText} ${it.aiDescription}" },
        timeAnalysis = timeAnalysis,
      )
    }
    return DailyReportResult(
      dailyReport = AiResponseParsers.fallbackDailyReport(events, confirmed, falseAlarm),
      status = AnalysisStatus.FAILED,
      confirmedCount = confirmed, falseAlarmCount = falseAlarm,
      keyEvents = events.take(5).map { "${it.timeText} ${it.aiDescription}" },
      timeAnalysis = timeAnalysis,
    )
  }

  // ───────────────────────── 3b 图片/视频 ─────────────────────────

  override suspend fun analyzeImage(imageBase64: String): ImageAnalysisResult? {
    httpLogger.i("$TAG/analyzeImage/start: base64Length=${imageBase64.length}")
    val startMs = currentTimeMillis()
    lastError = null

    val normalized = normalizeImageBase64(imageBase64) ?: run {
      httpLogger.e("$TAG/analyzeImage/failure: base64 decode failed")
      lastError = "base64_decode_failed(len=${imageBase64.length})"
      return null
    }

    val content = buildJsonArray {
      add(buildJsonObject { put("type", "text"); put("text", AiPrompts.image()) })
      add(buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
          put("url", "data:image/jpeg;base64,$normalized")
        })
      })
    }

    val raw = chatContent(content, RouterConfig.MODEL_IMAGE, RouterConfig.TEMP_IMAGE,
      RouterConfig.IMAGE_TIMEOUT_MS) ?: run {
      if (lastError == null) lastError = "router_request_null"
      return null
    }

    val parsed = parseImageResult(raw) ?: run {
      httpLogger.e("$TAG/analyzeImage/failure: json parse failed raw=${raw.take(120)}")
      lastError = "parse_failed: ${raw.take(200)}"
      return null
    }

    val elapsed = currentTimeMillis() - startMs
    httpLogger.i("$TAG/analyzeImage/success: elapsed=${elapsed}ms responseLength=${raw.length}")
    httpLogger.i("$TAG/analyzeImage/result: hasPerson=${parsed.hasPerson} description=${parsed.description}")
    return parsed
  }

  override suspend fun analyzeVideo(videoBase64: String): VideoAnalysisResult? {
    httpLogger.i("$TAG/analyzeVideo/start: base64Length=${videoBase64.length}")
    val startMs = currentTimeMillis()
    lastError = null

    // ⚠️ 必须用 image_url 字段承载视频（实测 18/18；旧 video_url 字段被网关静默丢弃返回假分析）
    val content = buildJsonArray {
      add(buildJsonObject { put("type", "text"); put("text", AiPrompts.video()) })
      add(buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
          put("url", "data:video/mp4;base64,$videoBase64")
        })
      })
    }

    val raw = chatContent(content, RouterConfig.MODEL_VIDEO, RouterConfig.TEMP_VIDEO,
      RouterConfig.VIDEO_TIMEOUT_MS) ?: run {
      httpLogger.e("$TAG/analyzeVideo/failure: router request returned null (timeout or request error)")
      return null
    }

    val parsed = parseVideoResult(raw) ?: run {
      httpLogger.e("$TAG/analyzeVideo/failure: json parse failed raw=${raw.take(120)}")
      return null
    }
    val elapsed = currentTimeMillis() - startMs
    httpLogger.i("$TAG/analyzeVideo/success: elapsed=${elapsed}ms")
    httpLogger.i("$TAG/analyzeVideo/result: hasPerson=${parsed.hasPerson} behavior=${parsed.behavior}")
    return parsed
  }

  // ───────────────────────── 3c 语音（TTS/ASR） ─────────────────────────

  override suspend fun transcribeAudio(audioData: ByteArray): String? {
    httpLogger.i("$TAG/transcribeAudio/start: audioLength=${audioData.size}")
    if (apiKey.isBlank()) {
      httpLogger.w("$TAG/transcribeAudio/skip: API key not configured")
      return null
    }
    val startMs = currentTimeMillis()

    val audioB64 = kotlin.io.encoding.Base64.Default.encode(audioData)
    val body = buildJsonObject {
      put("model", RouterConfig.MODEL_ASR)
      put("input", buildJsonObject {
        put("messages", buildJsonArray {
          add(buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
              add(buildJsonObject {
                put("type", "input_audio")
                put("input_audio", buildJsonObject {
                  put("data", "data:audio/wav;base64,$audioB64")
                })
              })
            })
          })
        })
      })
      put("parameters", buildJsonObject {
        put("format", "wav")
        put("sample_rate", "16000")
      })
    }

    val result = withTimeoutOrNull(RouterConfig.AUDIO_TIMEOUT_MS) {
      try {
        val response = client.post("${RouterConfig.BASE_URL}${RouterConfig.ENDPOINT_ASR}") {
          header("Authorization", "Bearer $apiKey")
          contentType(ContentType.Application.Json)
          header("x-model-provider", RouterConfig.HEADER_AUDIO_PROVIDER)
          setBody(body.toString())
        }
        if (response.status.isSuccess()) {
          val txt = response.bodyAsText()
          json.parseToJsonElement(txt).jsonObject.let { obj ->
            (obj["output"]?.jsonObject?.get("text") ?: obj["text"])?.jsonPrimitive?.content
          }
        } else {
          httpLogger.e("$TAG/transcribeAudio/failure: code=${response.status.value} body=${response.bodyAsText().take(200)}")
          null
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        httpLogger.e("$TAG/transcribeAudio/failure: error=${e.message}")
        null
      }
    }

    val elapsed = currentTimeMillis() - startMs
    if (result != null) {
      httpLogger.i("$TAG/transcribeAudio/success: elapsed=${elapsed}ms text=$result")
    } else {
      httpLogger.e("$TAG/transcribeAudio/failure: elapsed=${elapsed}ms")
    }
    return result
  }

  override suspend fun synthesizeSpeech(text: String): ByteArray? {
    httpLogger.i("$TAG/synthesizeSpeech/start: textLength=${text.length}")
    if (apiKey.isBlank()) {
      httpLogger.w("$TAG/synthesizeSpeech/skip: API key not configured")
      return null
    }
    val startMs = currentTimeMillis()

    val body = buildJsonObject {
      put("model", RouterConfig.MODEL_TTS)
      put("input", buildJsonObject {
        put("text", text)
        put("voice", RouterConfig.TTS_VOICE)
        put("format", RouterConfig.TTS_FORMAT)
        put("sample_rate", RouterConfig.TTS_SAMPLE_RATE)
      })
    }

    // 1) 合成
    val audioUrl = withTimeoutOrNull(RouterConfig.AUDIO_TIMEOUT_MS) {
      try {
        val response = client.post("${RouterConfig.BASE_URL}${RouterConfig.ENDPOINT_TTS}") {
          header("Authorization", "Bearer $apiKey")
          contentType(ContentType.Application.Json)
          header("x-model-provider", RouterConfig.HEADER_AUDIO_PROVIDER)
          setBody(body.toString())
        }
        if (response.status.isSuccess()) {
          val respJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
          val output = respJson["output"]?.jsonObject
          output?.get("audio")?.jsonObject?.get("url")?.jsonPrimitive?.content
        } else {
          httpLogger.e("$TAG/synthesizeSpeech/failure: code=${response.status.value} body=${response.bodyAsText().take(200)}")
          null
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        httpLogger.e("$TAG/synthesizeSpeech/failure: error=${e.message}")
        null
      }
    } ?: run {
      httpLogger.e("$TAG/synthesizeSpeech/failure: elapsed=${currentTimeMillis() - startMs}ms")
      return null
    }

    // 2) 立即下载（合成后毫秒级下载 vs URL 分钟级有效期，时序上不可能过期）
    // ⚠️ 强制 http→https：OSS 返回 http 明文 URL，iOS ATS 默认拒绝（实测同 host https TLS 可用）
    val downloadUrl = audioUrl.replaceFirst("http://", "https://")
    val bytes = withTimeoutOrNull(RouterConfig.TTS_DOWNLOAD_TIMEOUT_MS) {
      try {
        val response = client.get(downloadUrl)
        if (response.status.isSuccess()) response.readRawBytes() else null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        httpLogger.e("$TAG/synthesizeSpeech/download/failure: error=${e.message}")
        null
      }
    }

    val elapsed = currentTimeMillis() - startMs
    if (bytes != null) {
      httpLogger.i("$TAG/synthesizeSpeech/success: elapsed=${elapsed}ms audioSize=${bytes.size}")
    } else {
      httpLogger.e("$TAG/synthesizeSpeech/download/failure: elapsed=${elapsed}ms url=${downloadUrl.take(80)}")
    }
    return bytes
  }

  // ───────────────────────── HTTP 基础设施 ─────────────────────────

  private suspend fun chatText(prompt: String, model: String, temperature: Double, timeoutMs: Long): String? {
    val content = buildJsonArray {
      add(buildJsonObject { put("type", "text"); put("text", prompt) })
    }
    return chatContent(content, model, temperature, timeoutMs)
  }

  private suspend fun chatContent(content: JsonArray, model: String, temperature: Double, timeoutMs: Long): String? {
    if (apiKey.isBlank()) {
      httpLogger.w("$TAG/chat/skip: API key not configured")
      lastError = "api_key_blank"
      return null
    }
    val startMs = currentTimeMillis()
    val body = buildJsonObject {
      put("model", model)
      put("max_tokens", RouterConfig.MAX_TOKENS)
      put("messages", buildJsonArray {
        add(buildJsonObject {
          put("role", "user")
          put("content", content)
        })
      })
    }

    val result = withTimeoutOrNull(timeoutMs) {
      try {
        val response = client.post("${RouterConfig.BASE_URL}${RouterConfig.ENDPOINT_CHAT}") {
          header("Authorization", "Bearer $apiKey")
          contentType(ContentType.Application.Json)
          setBody(body.toString())
        }
        val code = response.status.value
        val respBody = response.bodyAsText()
        code to respBody
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        httpLogger.e("$TAG/chat/failure: error=${e.message}")
        null
      }
    }

    if (result == null) {
      httpLogger.e("$TAG/chat/timeout: model=$model timeout=${timeoutMs}ms")
      lastError = "timeout(model=$model, ${timeoutMs}ms)"
      return null
    }
    val (code, respBody) = result
    val elapsed = currentTimeMillis() - startMs
    if (code !in 200..299) {
      httpLogger.e("$TAG/chat/failure: model=$model code=$code elapsed=${elapsed}ms body=${respBody.take(200)}")
      lastError = "http_$code: ${respBody.take(300)}"
      return null
    }
    httpLogger.i("$TAG/chat/success: model=$model elapsed=${elapsed}ms responseLength=${respBody.length}")

    return runCatching {
      json.parseToJsonElement(respBody).jsonObject["choices"]
        ?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("message")
        ?.jsonObject?.get("content")
        ?.jsonPrimitive?.content
    }.getOrNull()
  }

  // ───────────────────────── 解析/工具（共享语义） ─────────────────────────

  private fun normalizeImageBase64(base64Text: String): String? =
    runCatching {
      val bytes = kotlin.io.encoding.Base64.Default.decode(base64Text.trim())
      kotlin.io.encoding.Base64.Default.encode(bytes)
    }.getOrNull()

  private fun extractJsonStringField(raw: String?, field: String): String? {
    if (raw.isNullOrBlank()) return null
    val match = Regex("\"?$field\"?\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(raw) ?: return null
    return unescapeJsonString(match.groupValues[1]).trim().takeIf { it.isNotBlank() }
  }

  private fun unescapeJsonString(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
      val c = text[i]
      if (c == '\\' && i + 1 < text.length) {
        when (text[i + 1]) {
          'n' -> sb.append('\n')
          't' -> sb.append('\t')
          'r' -> sb.append('\r')
          '"' -> sb.append('"')
          '\\' -> sb.append('\\')
          '/' -> sb.append('/')
          else -> sb.append(text[i + 1])
        }
        i += 2
      } else {
        sb.append(c)
        i += 1
      }
    }
    return sb.toString()
  }

  private fun parseImageResult(raw: String): ImageAnalysisResult? {
    val cleaned = JsonCleaner.cleanJsonOrNull(raw) ?: return null
    return runCatching {
      val obj = json.parseToJsonElement(cleaned).jsonObject
      ImageAnalysisResult(
        description = obj["description"]?.jsonPrimitive?.content ?: "",
        hasPerson = obj["has_person"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
        labels = obj["labels"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList(),
        sceneType = obj["scene_type"]?.jsonPrimitive?.content ?: "",
      )
    }.getOrNull()
  }

  private fun parseVideoResult(raw: String): VideoAnalysisResult? {
    val cleaned = JsonCleaner.cleanJsonOrNull(raw) ?: return null
    return runCatching {
      val obj = json.parseToJsonElement(cleaned).jsonObject
      VideoAnalysisResult(
        description = obj["description"]?.jsonPrimitive?.content ?: "",
        hasPerson = obj["has_person"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
        behavior = obj["behavior"]?.jsonPrimitive?.content ?: "",
        activityType = obj["activity_type"]?.jsonPrimitive?.content ?: "",
      )
    }.getOrNull()
  }
}
