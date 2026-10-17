package com.anker.esiotkit.sample.main.station.agent

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

data class TraceRecording(
  val id: String,
  val cameraId: String,
  val configVersion: String,
  val durationMs: Long,
  val sourceMode: String
)

val TraceRecording.isRealAnalysisSource: Boolean
  get() = sourceMode == "sdk" || sourceMode == "prerecorded"

data class TraceAgentState(
  val configured: Boolean = false,
  val baseUrl: String = "",
  val busy: Boolean = false,
  val phase: String = "idle",
  val error: String? = null,
  val recordings: List<TraceRecording> = emptyList(),
  val selectedRecordingId: String? = null,
  val zones: List<String> = emptyList(),
  val selectedZoneId: String? = null,
  val plan: JsonObject? = null,
  val run: JsonObject? = null,
  val messages: List<JsonObject> = emptyList(),
  val conversations: List<JsonObject> = emptyList(),
  val conversationId: String? = null,
  val hasMoreMessages: Boolean = false,
  val hasMoreConversations: Boolean = false,
  val evidence: JsonObject? = null,
  val evidenceBytes: ByteArray? = null
)

/** Backend credentials and model configuration never enter the shared UI state. */
expect object TraceAgent {
  val state: StateFlow<TraceAgentState>
  fun initialize()
  fun requestNewConversationOnEntry()
  fun setActive(active: Boolean)
  fun refreshRecordings()
  fun selectRecording(recordingId: String)
  fun selectZone(zoneId: String)
  fun submit(question: String)
  fun confirmPlan(queryText: String, steps: List<String>)
  fun stop()
  fun retry()
  fun loadHistory()
  fun openConversation(conversationId: String)
  fun openRun(runId: String)
  fun loadMoreMessages()
  fun loadMoreConversations()
  fun newConversation()
  fun openEvidence(evidenceId: String)
  fun closeEvidence()
  fun configure(baseUrl: String, bearerToken: String)
}

@Composable
expect fun TraceEvidenceImage(bytes: ByteArray, modifier: Modifier = Modifier)
