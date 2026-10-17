package com.anker.esiotkit.sample.main.station.agent

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Credentials never enter the public StateFlow, diagnostics, or system backups. */
@Serializable
internal data class TraceAgentSnapshot(
  val baseUrl: String = "",
  val bearerToken: String = "",
  val conversationId: String? = null,
  val recordingId: String? = null,
  val zoneId: String? = null,
  val pending: TracePending? = null,
  val lastRun: JsonObject? = null
) {
  val configured get() = normalizeTraceBaseUrl(baseUrl) != null && bearerToken.isNotBlank() && bearerToken.none { it.isWhitespace() }
}

@Serializable
internal data class TracePending(
  val messageId: String,
  val question: String,
  val query: JsonObject,
  val plan: JsonObject? = null,
  val runId: String? = null
)

internal fun newTraceId(prefix: String): String = prefix + UUID.randomUUID().toString().replace("-", "")

/** Called before the first network mutation; retry reuses exactly this value. */
internal fun beginTraceQuestion(snapshot: TraceAgentSnapshot, question: String, query: JsonObject): TraceAgentSnapshot {
  val content = question.trim()
  if (content.isBlank() || content.length > 2000) throw TraceApiFailure(0, "invalid_question")
  return snapshot.copy(conversationId = snapshot.conversationId ?: newTraceId("conv_"),
    pending = TracePending(newTraceId("msg_"), content, query), lastRun = null)
}

internal fun allocateTraceRun(snapshot: TraceAgentSnapshot, newExecution: Boolean = false): TraceAgentSnapshot {
  val pending = snapshot.pending ?: throw TraceApiFailure(0, "invalid_plan")
  if (pending.plan == null) throw TraceApiFailure(0, "invalid_plan")
  return snapshot.copy(pending = pending.copy(runId = if (newExecution) newTraceId("run_") else pending.runId ?: newTraceId("run_")),
    lastRun = if (newExecution) null else snapshot.lastRun)
}

internal fun confirmTracePlan(snapshot: TraceAgentSnapshot, queryText: String, steps: List<String>): TraceAgentSnapshot {
  val pending = snapshot.pending ?: throw TraceApiFailure(0, "invalid_plan")
  if (pending.runId != null) return snapshot
  val edited = tracePlan(buildJsonObject {
    put("query", pending.query); put("query_text", queryText.trim())
    put("steps", JsonArray(steps.map { JsonPrimitive(it.trim()) }))
  }, pending.query)
  return snapshot.copy(pending = pending.copy(plan = edited))
}

internal interface TraceAgentStore {
  fun read(): TraceAgentSnapshot
  fun write(value: TraceAgentSnapshot)
}

internal class AndroidTraceAgentStore(private val context: Context) : TraceAgentStore {
  private val file = AtomicFile(File(context.noBackupFilesDir, "trace-agent.json"))
  override fun read(): TraceAgentSnapshot {
    if (!file.baseFile.exists()) {
      val bootstrap = runCatching { context.assets.open("partrace-agent.json").bufferedReader().use { it.readText() } }.getOrNull()
      if (bootstrap == null) return TraceAgentSnapshot()
      val data = traceJson.parseToJsonElement(bootstrap) as? JsonObject ?: throw IOException("invalid_bootstrap")
      return TraceAgentSnapshot(baseUrl = data.string("base_url").orEmpty(), bearerToken = data.string("bearer_token").orEmpty())
    }
    if (file.baseFile.length() > 4 * 1024 * 1024) throw IOException("invalid_local_state")
    return file.openRead().bufferedReader().use { traceJson.decodeFromString<TraceAgentSnapshot>(it.readText()) }
  }

  override fun write(value: TraceAgentSnapshot) {
    val bytes = traceJson.encodeToString(value).toByteArray(Charsets.UTF_8)
    if (bytes.size > 4 * 1024 * 1024) throw IOException("local_state_too_large")
    val output = file.startWrite()
    try {
      output.write(bytes)
      file.finishWrite(output)
    } catch (failure: Exception) {
      file.failWrite(output)
      throw failure
    }
  }
}
