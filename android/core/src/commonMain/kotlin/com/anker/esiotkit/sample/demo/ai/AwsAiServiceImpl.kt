package com.anker.esiotkit.sample.demo.ai

import com.anker.esiotkit.sample.demo.AwsConfig
import com.anker.esiotkit.sample.demo.AwsSigV4Signer
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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * AWS AI 服务（Bedrock Nova / Transcribe / Polly）REST 实现。
 *
 * 双账号：
 * - 账号1（Bedrock）：Nova Pro 图片/视频理解 + Nova Lite 意图/摘要/日报
 * - 账号2（Transcribe + Polly）：ASR 与 TTS
 */
class AwsAiServiceImpl(
  private val client: HttpClient = createHttpClient(),
) : IAiAnalysisService {

  companion object {
    private const val TAG = "AwsAiServiceImpl"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  }

  internal var lastError: String? = null

  override suspend fun parseIntent(userText: String): IntentResult {
    val local = IntentParser.parseLocal(userText)
    httpLogger.i("$TAG/parseIntent/start: userText=$userText")

    val raw = requestBedrockText(
      AwsConfig.NOVA_LITE_MODEL_ID,
      intentPrompt(userText),
      maxTokens = 300,
      temperature = 0.1,
      timeoutMs = AwsConfig.AI_REQUEST_TIMEOUT_MS,
    )
    val ai = IntentParser.parseAiJson(raw)
    val result = when {
      ai != null -> ai.copy(dayOffset = local?.dayOffset ?: 0)
      local != null -> local
      else -> IntentResult(0, 24, "all", "ALL", "fallback", 0)
    }
    httpLogger.i("$TAG/parseIntent/success: startHour=${result.startHour} endHour=${result.endHour} target=${result.queryTarget} detection=${result.detectionType} dayOffset=${result.dayOffset}")
    return result
  }

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
      add(buildJsonObject { put("text", imagePrompt()) })
      add(
        buildJsonObject {
          put("image", buildJsonObject {
            put("format", "jpeg")
            put("source", buildJsonObject { put("bytes", normalized) })
          })
        }
      )
    }

    val raw = requestBedrockContent(
      AwsConfig.NOVA_PRO_MODEL_ID, content,
      maxTokens = 300, temperature = 0.2,
      timeoutMs = AwsConfig.AI_IMAGE_TIMEOUT_MS,
    ) ?: run {
      if (lastError == null) lastError = "bedrock_request_null"
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
    val content = buildJsonArray {
      add(buildJsonObject { put("text", videoPrompt()) })
      add(
        buildJsonObject {
          put("video", buildJsonObject {
            put("format", "mp4")
            put("source", buildJsonObject { put("bytes", videoBase64) })
          })
        }
      )
    }
    val raw = requestBedrockContent(
      AwsConfig.NOVA_PRO_MODEL_ID, content,
      maxTokens = 500, temperature = 0.3,
      timeoutMs = AwsConfig.AI_VIDEO_TIMEOUT_MS,
    ) ?: run {
      httpLogger.e("$TAG/analyzeVideo/failure: bedrock request returned null (timeout or request error)")
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

  override suspend fun summarize(query: String, eventSummaries: List<EventMatchResult>): SummaryResult {
    httpLogger.i("$TAG/summarize/start: query=$query eventCount=${eventSummaries.size}")
    if (eventSummaries.isEmpty()) {
      return SummaryResult("该时段没有检测到活动", AnalysisStatus.EMPTY)
    }
    val raw = requestBedrockText(
      AwsConfig.NOVA_LITE_MODEL_ID,
      summaryPrompt(query, eventSummaries),
      maxTokens = 300, temperature = 0.3,
      timeoutMs = AwsConfig.AI_REQUEST_TIMEOUT_MS,
    )
    val cleaned = JsonCleaner.cleanJsonOrNull(raw)
    if (cleaned != null) {
      try {
        val obj = json.parseToJsonElement(cleaned).jsonObject
        val answer = obj["answer"]?.jsonPrimitive?.content
        if (!answer.isNullOrBlank()) {
          httpLogger.i("$TAG/summarize/success: answer=$answer")
          return SummaryResult(answer, AnalysisStatus.SUCCESS)
        }
      } catch (e: Exception) {
        httpLogger.e("$TAG/summarize/failure: reason=parse_error error=${e.message} raw=${raw?.take(120)}")
      }
    }
    val lenientAnswer = extractJsonStringField(raw, "answer")
    if (lenientAnswer != null) {
      httpLogger.w("$TAG/summarize/fallback: reason=lenient_extract, answer=$lenientAnswer")
      return SummaryResult(lenientAnswer, AnalysisStatus.SUCCESS)
    }
    val fallback = localSummary(eventSummaries)
    httpLogger.w("$TAG/summarize/degraded: ${fallback.answer}")
    return fallback
  }

  override suspend fun generateDailyReport(events: List<EventMatchResult>): DailyReportResult {
    httpLogger.i("$TAG/generateDailyReport/start: eventCount=${events.size}")
    if (events.isEmpty()) {
      return DailyReportResult(
        dailyReport = "今天没有检测到任何活动，家里一切正常",
        status = AnalysisStatus.EMPTY,
        confirmedCount = 0,
        falseAlarmCount = 0,
        keyEvents = emptyList(),
        timeAnalysis = "",
      )
    }
    val confirmed = events.count { it.matchStatus == MatchStatus.CONFIRMED }
    val falseAlarm = events.count { it.matchStatus == MatchStatus.FALSE_ALARM }
    val timeAnalysis = buildTimeAnalysis(events)

    val raw = requestBedrockText(
      AwsConfig.NOVA_LITE_MODEL_ID,
      dailyReportPrompt(events, confirmed, falseAlarm, timeAnalysis),
      maxTokens = 600, temperature = 0.3,
      timeoutMs = AwsConfig.AI_REQUEST_TIMEOUT_MS,
    )
    val cleaned = JsonCleaner.cleanJsonOrNull(raw)
    if (cleaned != null) {
      try {
        val obj = json.parseToJsonElement(cleaned).jsonObject
        val report = obj["daily_report"]?.jsonPrimitive?.content
        if (!report.isNullOrBlank()) {
          httpLogger.i("$TAG/generateDailyReport/success: reportLength=${report.length}")
          return DailyReportResult(
            dailyReport = report,
            status = AnalysisStatus.SUCCESS,
            confirmedCount = confirmed,
            falseAlarmCount = falseAlarm,
            keyEvents = events.take(5).map { "${it.timeText} ${it.aiDescription}" },
            timeAnalysis = timeAnalysis,
          )
        }
      } catch (e: Exception) {
        httpLogger.e("$TAG/generateDailyReport/failure: reason=parse_error error=${e.message} raw=${raw?.take(120)}")
      }
    }
    val lenientReport = extractJsonStringField(raw, "daily_report")
    if (lenientReport != null) {
      httpLogger.w("$TAG/generateDailyReport/fallback: reason=lenient_extract, reportLength=${lenientReport.length}")
      return DailyReportResult(
        dailyReport = lenientReport,
        status = AnalysisStatus.SUCCESS,
        confirmedCount = confirmed,
        falseAlarmCount = falseAlarm,
        keyEvents = events.take(5).map { "${it.timeText} ${it.aiDescription}" },
        timeAnalysis = timeAnalysis,
      )
    }
    return DailyReportResult(
      dailyReport = localDailyReport(events, confirmed, falseAlarm),
      status = AnalysisStatus.FAILED,
      confirmedCount = confirmed,
      falseAlarmCount = falseAlarm,
      keyEvents = events.take(5).map { "${it.timeText} ${it.aiDescription}" },
      timeAnalysis = timeAnalysis,
    )
  }

  override suspend fun transcribeAudio(audioData: ByteArray): String? {
    val bucket = AwsConfig.TRANSCRIBE_S3_BUCKET
    httpLogger.i("$TAG/transcribeAudio/start: audioLength=${audioData.size} bucket=${bucket.ifBlank { "<unset>" }}")
    if (bucket.isBlank()) {
      httpLogger.w("$TAG/transcribeAudio/skip: TRANSCRIBE_S3_BUCKET not configured (degrade to text input)")
      return null
    }

    val startMs = currentTimeMillis()
    val key = "esiot-aidemo/${currentTimeMillis()}.wav"

    val upload = withTimeoutOrNull(AwsConfig.AI_REQUEST_TIMEOUT_MS) {
      val path = "/$bucket/$key"
      val url = "https://${AwsConfig.S3_HOST}$path"
      try {
        val sign = AwsSigV4Signer.sign(
          "PUT", AwsConfig.S3_HOST, path, "",
          "s3", AwsConfig.REGION,
          AwsConfig.TRANSCRIBE_ACCESS_KEY, AwsConfig.TRANSCRIBE_SECRET_KEY,
          audioData, "audio/wav",
        )
        val response = client.put(url) {
          applySignedHeaders(sign.headers)
          setBody(audioData)
        }
        httpLogger.i("$TAG/transcribeAudio/s3Put: status=${response.status.value}")
        response.status.isSuccess()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        httpLogger.e("$TAG/transcribeAudio/failure: s3 put url=$url error=${e.message}")
        false
      }
    } ?: false

    if (!upload) {
      httpLogger.e("$TAG/transcribeAudio/failure: s3 upload failed")
      return null
    }

    val jobName = "esiot-aidemo-${currentTimeMillis()}"
    val startBody = buildJsonObject {
      put("TranscriptionJobName", jobName)
      put("Media", buildJsonObject { put("MediaFileUri", "s3://$bucket/$key") })
      put("MediaFormat", "wav")
      put("LanguageCode", "zh-CN")
    }.toString()

    val (startCode, startResp) = transcribeJsonRequest("TranscribeService.StartTranscriptionJob", startBody)
      ?: return null
    if (startCode !in 200..299) {
      httpLogger.e("$TAG/transcribeAudio/failure: start job code=$startCode body=${startResp.take(200)}")
      return null
    }

    val pollStart = currentTimeMillis()
    while (currentTimeMillis() - pollStart < AwsConfig.TRANSCRIBE_POLL_MAX_MS) {
      delay(AwsConfig.TRANSCRIBE_POLL_INTERVAL_MS)
      val (code, body) = transcribeJsonRequest(
        "TranscribeService.GetTranscriptionJob",
        buildJsonObject { put("TranscriptionJobName", jobName) }.toString(),
      ) ?: continue
      if (code !in 200..299) continue
      val job = runCatching { json.parseToJsonElement(body).jsonObject["TranscriptionJob"]?.jsonObject }.getOrNull() ?: continue
      when (job["TranscriptionJobStatus"]?.jsonPrimitive?.content) {
        "COMPLETED" -> {
          val uri = job["Transcript"]?.jsonObject?.get("TranscriptFileUri")?.jsonPrimitive?.content
          if (uri.isNullOrBlank()) {
            httpLogger.e("$TAG/transcribeAudio/failure: transcript uri missing")
            return null
          }
          val text = fetchTranscriptText(uri)
          val elapsed = currentTimeMillis() - startMs
          if (text != null) {
            httpLogger.i("$TAG/transcribeAudio/success: elapsed=${elapsed}ms text=$text")
          } else {
            httpLogger.e("$TAG/transcribeAudio/failure: transcript fetch failed")
          }
          return text
        }
        "FAILED" -> {
          httpLogger.e("$TAG/transcribeAudio/failure: job FAILED body=${body.take(200)}")
          return null
        }
      }
    }
    httpLogger.e("$TAG/transcribeAudio/failure: poll timeout ${AwsConfig.TRANSCRIBE_POLL_MAX_MS}ms")
    return null
  }

  override suspend fun synthesizeSpeech(text: String): ByteArray? {
    httpLogger.i("$TAG/synthesizeSpeech/start: textLength=${text.length}")
    val startMs = currentTimeMillis()
    val body = buildJsonObject {
      put("Text", text)
      put("VoiceId", "Zhiyu")
      put("Engine", "neural")
      put("OutputFormat", "mp3")
      put("LanguageCode", "cmn-CN")
    }.toString()
    val bytes = awsPostBytes(
      host = AwsConfig.POLLY_HOST,
      path = "/v1/speech",
      service = "polly",
      accessKey = AwsConfig.TRANSCRIBE_ACCESS_KEY,
      secretKey = AwsConfig.TRANSCRIBE_SECRET_KEY,
      body = body,
      contentType = "application/json",
      timeoutMs = AwsConfig.AI_REQUEST_TIMEOUT_MS,
    )
    val elapsed = currentTimeMillis() - startMs
    if (bytes != null) {
      httpLogger.i("$TAG/synthesizeSpeech/success: elapsed=${elapsed}ms audioSize=${bytes.size}")
    } else {
      httpLogger.e("$TAG/synthesizeSpeech/failure: elapsed=${elapsed}ms")
    }
    return bytes
  }

  private suspend fun requestBedrockText(
    modelId: String,
    prompt: String,
    maxTokens: Int,
    temperature: Double,
    timeoutMs: Long,
  ): String? {
    val content = buildJsonArray {
      add(buildJsonObject { put("text", prompt) })
    }
    return requestBedrockContent(modelId, content, maxTokens, temperature, timeoutMs)
  }

  private suspend fun requestBedrockContent(
    modelId: String,
    content: JsonArray,
    maxTokens: Int,
    temperature: Double,
    timeoutMs: Long,
  ): String? {
    val startMs = currentTimeMillis()
    val body = buildJsonObject {
      put("messages", buildJsonArray {
        add(
          buildJsonObject {
            put("role", "user")
            put("content", content)
          }
        )
      })
      put("inferenceConfig", buildJsonObject {
        put("maxTokens", maxTokens)
        put("temperature", temperature)
      })
    }.toString()

    val path = "/model/$modelId/converse"
    val result = withTimeoutOrNull(timeoutMs) {
      awsPostText(
        host = AwsConfig.BEDROCK_HOST,
        path = path,
        service = "bedrock",
        accessKey = AwsConfig.BEDROCK_ACCESS_KEY,
        secretKey = AwsConfig.BEDROCK_SECRET_KEY,
        body = body,
        contentType = "application/json",
      )
    }

    if (result == null) {
      httpLogger.e("$TAG/bedrock/timeout: model=$modelId timeout=${timeoutMs}ms")
      lastError = "timeout(model=$modelId, ${timeoutMs}ms)"
      return null
    }
    val (code, respBody) = result
    val elapsed = currentTimeMillis() - startMs
    if (code !in 200..299) {
      httpLogger.e("$TAG/bedrock/failure: model=$modelId code=$code elapsed=${elapsed}ms body=${respBody.take(200)}")
      lastError = "http_$code: ${respBody.take(300)}"
      return null
    }
    httpLogger.i("$TAG/bedrock/success: model=$modelId elapsed=${elapsed}ms responseLength=${respBody.length}")

    return runCatching {
      json.parseToJsonElement(respBody).jsonObject["output"]
        ?.jsonObject?.get("message")
        ?.jsonObject?.get("content")
        ?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("text")
        ?.jsonPrimitive?.content
    }.getOrNull()
  }

  private suspend fun awsPostText(
    host: String,
    path: String,
    service: String,
    accessKey: String,
    secretKey: String,
    body: String,
    contentType: String,
    extraSignedHeaders: Map<String, String> = emptyMap(),
  ): Pair<Int, String>? {
    val url = "https://$host$path"
    return try {
      val bodyBytes = body.encodeToByteArray()
      val sign = AwsSigV4Signer.sign(
        "POST", host, path, "", service, AwsConfig.REGION,
        accessKey, secretKey, bodyBytes, contentType, extraSignedHeaders,
      )
      val response = client.post(url) {
        applySignedHeaders(sign.headers)
        setBody(body)
      }
      response.status.value to response.bodyAsText()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      httpLogger.e("$TAG/awsPostText/failure: url=$url error=${e.message}")
      null
    }
  }

  private suspend fun awsPostBytes(
    host: String,
    path: String,
    service: String,
    accessKey: String,
    secretKey: String,
    body: String,
    contentType: String,
    timeoutMs: Long,
  ): ByteArray? = withTimeoutOrNull(timeoutMs) {
    val url = "https://$host$path"
    try {
      val bodyBytes = body.encodeToByteArray()
      val sign = AwsSigV4Signer.sign(
        "POST", host, path, "", service, AwsConfig.REGION,
        accessKey, secretKey, bodyBytes, contentType,
      )
      val response = client.post(url) {
        applySignedHeaders(sign.headers)
        setBody(body)
      }
      if (response.status.isSuccess()) response.readRawBytes() else null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      httpLogger.e("$TAG/awsPostBytes/failure: url=$url error=${e.message}")
      null
    }
  }

  private suspend fun transcribeJsonRequest(target: String, body: String): Pair<Int, String>? =
    try {
      awsPostText(
        host = AwsConfig.TRANSCRIBE_HOST,
        path = "/",
        service = "transcribe",
        accessKey = AwsConfig.TRANSCRIBE_ACCESS_KEY,
        secretKey = AwsConfig.TRANSCRIBE_SECRET_KEY,
        body = body,
        contentType = "application/x-amz-json-1.1",
        extraSignedHeaders = mapOf("x-amz-target" to target),
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      httpLogger.e("$TAG/transcribeJsonRequest/failure: target=$target error=${e.message}")
      null
    }

  private suspend fun fetchTranscriptText(uri: String): String? = withTimeoutOrNull(AwsConfig.AI_REQUEST_TIMEOUT_MS) {
    runCatching {
      val text = client.get(uri).bodyAsText()
      json.parseToJsonElement(text).jsonObject["results"]
        ?.jsonObject?.get("transcripts")
        ?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("transcript")
        ?.jsonPrimitive?.content
    }.getOrNull()
  }

  private fun HttpRequestBuilder.applySignedHeaders(headers: Map<String, String>) {
    headers.forEach { (k, v) ->
      if (k.equals("Content-Type", ignoreCase = true)) {
        contentType(ContentType.parse(v))
      } else {
        header(k, v)
      }
    }
  }

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
        i++
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

  private fun localSummary(events: List<EventMatchResult>): SummaryResult {
    val confirmed = events.count { it.matchStatus == MatchStatus.CONFIRMED }
    val answer = if (confirmed > 0) {
      "该时段检测到 ${events.size} 条活动，其中 $confirmed 条已确认有目标出现"
    } else {
      "该时段检测到 ${events.size} 条活动，但均未确认有目标出现"
    }
    return SummaryResult(answer, AnalysisStatus.SUCCESS)
  }

  private fun localDailyReport(events: List<EventMatchResult>, confirmed: Int, falseAlarm: Int): String =
    "今日共 ${events.size} 条事件：确认 $confirmed 条，疑似误报 $falseAlarm 条。"

  private fun buildTimeAnalysis(events: List<EventMatchResult>): String {
    val hours = events.mapNotNull { e -> e.timeText.substringBefore(":").toIntOrNull() }
      .groupingBy { it }.eachCount()
    return hours.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}时×${it.value}" }
  }

  private fun intentPrompt(userText: String): String = """
    你是家庭安防助手的意图解析器。用户说：「${JsonCleaner.escapeForJson(userText)}」。
    请提取：1) 时间范围（小时，0-24，如"下午"=14到18）2) 查询目标（people/package/all）3) 检测类型（BODY/PET/CAR/ALL）。
    只输出 JSON，不要任何其他文字：{"start_hour":14,"end_hour":18,"query_target":"people","detection_type":"BODY","understood_intent":"查看下午是否有人出入"}
  """.trimIndent()

  private fun imagePrompt(): String =
    "你是家庭安防分析助手。请分析这张监控缩略图，判断画面中是否有人。" +
      "严格输出 JSON：{\"description\":\"画面简要描述\",\"has_person\":true或false,\"labels\":[\"标签\"],\"scene_type\":\"室内/室外\"}"

  private fun videoPrompt(): String =
    "你是家庭安防分析助手。请分析这段监控视频，判断画面中是否有人及其行为。" +
      "严格输出 JSON：{\"description\":\"画面与行为描述\",\"has_person\":true或false,\"behavior\":\"靠近/远离/停留/经过/无活动\",\"activity_type\":\"工作/经过/异常/无\"}"

  private fun summaryPrompt(query: String, events: List<EventMatchResult>): String {
    val lines = events.joinToString("\n") {
      "- ${it.timeText} 设备=${it.event.deviceName} SDK类型=${it.sdkType} AI判断有人=${it.aiHasPerson} 描述=${it.aiDescription} 匹配=${it.matchStatus}"
    }
    return """
      你是家庭安防助手。用户问题：「${JsonCleaner.escapeForJson(query)}」。
      以下是事件与 AI 分析结果：
      $lines
      请给出一句话中文结论（包含时间点与是否有人）。
      严格输出 JSON：{"answer":"今天下午15:26有人在家里","status":"safe或attention"}
    """.trimIndent()
  }

  private fun dailyReportPrompt(
    events: List<EventMatchResult>,
    confirmed: Int,
    falseAlarm: Int,
    timeAnalysis: String,
  ): String {
    val lines = events.joinToString("\n") {
      "- ${it.timeText} 设备=${it.event.deviceName} SDK类型=${it.sdkType} AI判断有人=${it.aiHasPerson} 描述=${it.aiDescription} 匹配=${it.matchStatus}"
    }
    return """
      你是家庭安防助手，请生成今日安全日报（中文，3-5 句话，包含分类统计、时间线特征与总体结论）。
      事件列表：
      $lines
      统计：确认 $confirmed 条，疑似误报 $falseAlarm 条；时间分布：$timeAnalysis
      严格输出 JSON：{"daily_report":"...","status":"safe或attention","confirmed_count":$confirmed,"false_alarm_count":$falseAlarm,"key_events":["..."],"time_analysis":"$timeAnalysis"}
    """.trimIndent()
  }
}
