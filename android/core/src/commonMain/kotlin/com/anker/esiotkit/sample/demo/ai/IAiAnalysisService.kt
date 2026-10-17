package com.anker.esiotkit.sample.demo.ai

import com.anker.esiotkit.sample.demo.model.DailyReportResult
import com.anker.esiotkit.sample.demo.model.EventMatchResult
import com.anker.esiotkit.sample.demo.model.ImageAnalysisResult
import com.anker.esiotkit.sample.demo.model.IntentResult
import com.anker.esiotkit.sample.demo.model.SummaryResult
import com.anker.esiotkit.sample.demo.model.VideoAnalysisResult

/**
 * AWS AI 能力层（Bedrock Nova / Transcribe / Polly）。
 *
 * 全部通过 REST API + SigV4 直接调用（不引入 AWS SDK）。
 * 失败/超时返回 null（由业务层降级处理）。
 */
interface IAiAnalysisService {

  /** Bedrock Nova Lite — 文本意图识别（失败时回退本地启发式） */
  suspend fun parseIntent(userText: String): IntentResult

  /** Bedrock Nova Pro — 图片理解；失败/超时返回 null（业务降级为文本） */
  suspend fun analyzeImage(imageBase64: String): ImageAnalysisResult?

  /** Bedrock Nova Pro — 视频理解；失败/超时返回 null（业务降级为文本） */
  suspend fun analyzeVideo(videoBase64: String): VideoAnalysisResult?

  /** Bedrock Nova Lite — 汇总生成回答 */
  suspend fun summarize(query: String, eventSummaries: List<EventMatchResult>): SummaryResult

  /** Bedrock Nova Lite — 日报生成 */
  suspend fun generateDailyReport(events: List<EventMatchResult>): DailyReportResult

  /** Transcribe（批量 S3 模式）— 语音转文字；未配置或失败返回 null */
  suspend fun transcribeAudio(audioData: ByteArray): String?

  /** Polly — 文字转语音（MP3 字节）；失败返回 null */
  suspend fun synthesizeSpeech(text: String): ByteArray?
}
