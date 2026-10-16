package com.anker.esiotkit.sample.main.station.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val traceJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
internal val traceIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}")
internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.objectValue(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray)?.map {
  it as? JsonObject ?: throw TraceApiFailure(0, "invalid_response")
} ?: throw TraceApiFailure(0, "invalid_response")

/** An API origin, never an arbitrary URL to which a Bearer can be forwarded. */
internal fun normalizeTraceBaseUrl(value: String): String? = runCatching {
  val uri = URI(value.trim())
  val host = uri.host?.lowercase() ?: return null
  val loopbacks = setOf("10.0.2.2", "localhost", "127.0.0.1", "::1", "[::1]")
  if (uri.scheme != "https" && !(uri.scheme == "http" && host in loopbacks)) return null
  if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
    uri.rawPath !in listOf("", "/") || uri.port !in -1..65535 || uri.port == 0) return null
  "${uri.scheme}://${uri.rawAuthority}"
}.getOrNull()

internal class TraceApiFailure(val status: Int, val code: String) : IOException("trace_api_failure")

internal class TraceAgentClient(baseUrl: String, private val bearer: String, private val client: OkHttpClient = defaultClient) {
  private val origin = normalizeTraceBaseUrl(baseUrl) ?: throw TraceApiFailure(0, "invalid_configuration")

  suspend fun get(path: String): JsonObject = json(path, null)
  suspend fun post(path: String, value: JsonObject): JsonObject = json(path, value)

  private suspend fun json(path: String, value: JsonObject?): JsonObject {
    val bytes = request(path, value, 4 * 1024 * 1024, false)
    val result = runCatching { traceJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
      .getOrElse { throw TraceApiFailure(0, "invalid_response") }
    if (result.string("api_version") != "0.3-mvp-draft") throw TraceApiFailure(0, "invalid_response")
    return result
  }

  suspend fun evidenceBytes(id: String): ByteArray {
    requireTraceId(id)
    return request("/api/v1/evidence/$id/media", null, 16 * 1024 * 1024, true)
  }

  private suspend fun request(path: String, value: JsonObject?, limit: Int, image: Boolean): ByteArray {
    if (!path.startsWith("/api/v1/") || path.startsWith("//") || path.contains("#") || path.contains("\\"))
      throw TraceApiFailure(0, "invalid_request")
    val builder = Request.Builder().url(origin + path).header("Authorization", "Bearer $bearer")
      .header("Accept", if (image) "image/png, image/jpeg" else "application/json")
    if (value == null) builder.get() else builder.post(value.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
    return suspendCancellableCoroutine { continuation ->
      val call = client.newCall(builder.build())
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          if (continuation.isActive) continuation.resumeWithException(TraceApiFailure(0, "network_unavailable"))
        }
        override fun onResponse(call: Call, response: Response) {
          try {
            val bytes = response.use {
              val body = it.body ?: throw TraceApiFailure(it.code, "invalid_response")
              val maxBytes = if (it.isSuccessful) limit else 64 * 1024
              if (body.contentLength() > maxBytes) throw TraceApiFailure(it.code, "response_too_large")
              val output = ByteArrayOutputStream()
              body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                  val count = input.read(buffer)
                  if (count < 0) break
                  if (output.size() + count > maxBytes) throw TraceApiFailure(it.code, "response_too_large")
                  output.write(buffer, 0, count)
                }
              }
              val content = output.toByteArray()
              if (!it.isSuccessful) {
                val code = runCatching { traceJson.parseToJsonElement(content.toString(Charsets.UTF_8)).jsonObject.objectValue("error")?.string("code") }
                  .getOrNull()?.takeIf { text -> text.matches(Regex("[a-z][a-z0-9_]{0,99}")) } ?: "http_error"
                throw TraceApiFailure(it.code, code)
              }
              if (image && body.contentType()?.toString()?.substringBefore(';') !in setOf("image/png", "image/jpeg"))
                throw TraceApiFailure(it.code, "invalid_media")
              content
            }
            if (continuation.isActive) continuation.resume(bytes)
          } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure as? TraceApiFailure ?: TraceApiFailure(0, "network_unavailable"))
          }
        }
      })
    }
  }

  companion object {
    private val defaultClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
      .connectTimeout(15, TimeUnit.SECONDS).readTimeout(80, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
  }
}

internal fun requireTraceId(value: String): String = value.also {
  if (!it.matches(traceIdPattern)) throw TraceApiFailure(0, "invalid_response")
}

internal fun traceQuery(recording: TraceRecording, zone: String): JsonObject {
  requireTraceId(recording.id); requireTraceId(recording.cameraId); requireTraceId(zone)
  if (recording.durationMs <= 0) throw TraceApiFailure(0, "invalid_scope")
  return buildJsonObject {
    put("recording_id", recording.id); put("camera_id", recording.cameraId); put("zone_id", zone)
    put("start_offset_ms", 0L); put("end_offset_ms", recording.durationMs)
  }
}

internal fun tracePlan(response: JsonObject, scope: JsonObject): JsonObject {
  if (response.objectValue("query") != scope || response.string("query_text").isNullOrBlank() ||
    response.string("query_text")!!.length > 2000) throw TraceApiFailure(0, "invalid_plan")
  val steps = response["steps"] as? JsonArray ?: throw TraceApiFailure(0, "invalid_plan")
  if (steps.isEmpty() || steps.any { (it as? JsonPrimitive)?.contentOrNull.isNullOrBlank() }) throw TraceApiFailure(0, "invalid_plan")
  return buildJsonObject { put("query", scope); put("query_text", response.getValue("query_text")); put("steps", steps) }
}

internal fun validateTraceRun(run: JsonObject, runId: String, conversationId: String? = null): JsonObject {
  if (run.string("run_id") != runId || (conversationId != null && run.string("conversation_id") != conversationId) ||
    run.string("status") !in setOf("running", "completed", "stopped", "failed")) throw TraceApiFailure(0, "invalid_response")
  requireTraceId(run.string("conversation_id") ?: "")
  requireTraceId(run.string("input_message_id") ?: "")
  val plan = run.objectValue("plan") ?: throw TraceApiFailure(0, "invalid_response")
  val query = plan.objectValue("query") ?: throw TraceApiFailure(0, "invalid_response")
  if (query.keys != setOf("recording_id", "camera_id", "zone_id", "start_offset_ms", "end_offset_ms")) throw TraceApiFailure(0, "invalid_scope")
  listOf("recording_id", "camera_id", "zone_id").forEach { requireTraceId(query.string(it) ?: "") }
  val start = (query["start_offset_ms"] as? JsonPrimitive)?.longOrNull ?: throw TraceApiFailure(0, "invalid_scope")
  val end = (query["end_offset_ms"] as? JsonPrimitive)?.longOrNull ?: throw TraceApiFailure(0, "invalid_scope")
  if (start < 0 || end <= start) throw TraceApiFailure(0, "invalid_scope")
  tracePlan(plan, query)
  val status = run.string("status")
  if (status == "completed" && run.string("summary").isNullOrBlank()) throw TraceApiFailure(0, "invalid_response")
  if (status != "completed" && run["summary"] != JsonNull) throw TraceApiFailure(0, "invalid_response")
  val references = run.objects("references")
  if (status != "completed" && references.isNotEmpty()) throw TraceApiFailure(0, "invalid_response")
  references.forEach { requireTraceId(it.string("event_id") ?: ""); requireTraceId(it.string("evidence_id") ?: "") }
  val cards = run.objectValue("cards")
  if (cards != null && (status != "completed" || (cards["version"] as? JsonPrimitive)?.intOrNull != 1 ||
    cards.string("run_id") != runId || cards.objectValue("query") != query))
    throw TraceApiFailure(0, "invalid_response")
  cards?.objects("cards")?.forEach {
    if (it.string("kind") !in setOf("run_summary", "event_evidence", "coverage_gap")) throw TraceApiFailure(0, "invalid_response")
  }
  return run
}
