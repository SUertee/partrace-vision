@file:OptIn(com.anker.esiotkit.support.InternalIotApi::class)

package com.anker.esiotkit.sample.main.station.agent

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.anker.esiotkit.sample.support.applicationContext
import java.net.URLEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

actual object TraceAgent {
  private val controller by lazy {
    TraceAgentController(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), AndroidTraceAgentStore(applicationContext as Context))
  }
  actual val state: StateFlow<TraceAgentState> get() = controller.state
  actual fun initialize() = controller.initialize()
  actual fun requestNewConversationOnEntry() = controller.requestNewConversationOnEntry()
  actual fun setActive(active: Boolean) = controller.setActive(active)
  actual fun refreshRecordings() = controller.refreshRecordings()
  actual fun selectRecording(recordingId: String) = controller.selectRecording(recordingId)
  actual fun selectZone(zoneId: String) = controller.selectZone(zoneId)
  actual fun submit(question: String) = controller.submit(question)
  actual fun confirmPlan(queryText: String, steps: List<String>) = controller.confirmPlan(queryText, steps)
  actual fun stop() = controller.stop()
  actual fun retry() = controller.retry()
  actual fun loadHistory() = controller.loadHistory()
  actual fun openConversation(conversationId: String) = controller.openConversation(conversationId)
  actual fun loadMoreMessages() = controller.loadMoreMessages()
  actual fun loadMoreConversations() = controller.loadMoreConversations()
  actual fun newConversation() = controller.newConversation()
  actual fun openRun(runId: String) = controller.openRun(runId)
  actual fun openEvidence(evidenceId: String) = controller.openEvidence(evidenceId)
  actual fun closeEvidence() = controller.closeEvidence()
  actual fun configure(baseUrl: String, bearerToken: String) = controller.configure(baseUrl, bearerToken)
}

internal class TraceAgentController(private val scope: CoroutineScope, private val store: TraceAgentStore) {
  private val mutableState = MutableStateFlow(TraceAgentState())
  val state: StateFlow<TraceAgentState> = mutableState
  private val initLock = Mutex()
  private val storeLock = Mutex()
  private var initialized = false
  private var recordingsLoaded = false
  private var snapshot = TraceAgentSnapshot()
  private var active = false
  private var freshEntryRequested = false
  private var authPaused = false
  private var operation: Job? = null
  private var operationSequence = 0L
  private var polling: Job? = null
  private var evidenceJob: Job? = null
  private var inactiveJobs: List<Job> = emptyList()
  private var conversationCursor: String? = null
  private var messageCursor: String? = null
  private fun api() = TraceAgentClient(snapshot.baseUrl, snapshot.bearerToken)

  fun initialize() { scope.launch { runCatching { ensureInitialized() }.onFailure { showFailure(it) } } }

  private suspend fun ensureInitialized() = initLock.withLock {
    if (initialized) return@withLock
    try { snapshot = withContext(Dispatchers.IO) { store.read() } }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { initialized = true; throw failure }
    initialized = true
    mutableState.update { it.copy(configured = snapshot.configured, baseUrl = normalizeTraceBaseUrl(snapshot.baseUrl).orEmpty(),
      conversationId = snapshot.conversationId, selectedRecordingId = snapshot.recordingId,
      selectedZoneId = snapshot.zoneId, plan = null, run = null, phase = "idle") }
  }

  private suspend fun save(value: TraceAgentSnapshot) = storeLock.withLock {
    // Persistence must succeed before requests that create history or execution.
    // A lifecycle cancellation cannot leave memory behind an already committed AtomicFile.
    withContext(NonCancellable) {
      withContext(Dispatchers.IO) { store.write(value) }
      snapshot = value
    }
    currentCoroutineContext().ensureActive()
  }

  /** A tab click is distinct from lifecycle activation or returning from evidence. */
  fun requestNewConversationOnEntry() {
    freshEntryRequested = true
  }

  fun setActive(value: Boolean) {
    active = value
    if (!value) {
      operationSequence++
      inactiveJobs = (inactiveJobs + listOfNotNull(operation, polling, evidenceJob))
        .distinct().filterNot { it.isCompleted }
      operation?.cancel(); operation = null; polling?.cancel(); polling = null
      evidenceJob?.cancel(); evidenceJob = null
      mutableState.update { it.copy(busy = false, run = null, plan = null, messages = emptyList(),
        conversations = emptyList(), evidence = null, evidenceBytes = null) }
    } else {
      // An entry request must not be dropped by perform's busy guard. Wait for
      // cancelled durable writes before clearing the previous conversation.
      val previousJobs = if (freshEntryRequested) {
        operationSequence++
        (inactiveJobs + listOfNotNull(operation, polling, evidenceJob)).distinct()
          .also { jobs -> jobs.forEach { it.cancel() } }
          .also { operation = null; polling = null; evidenceJob = null }
      } else inactiveJobs
      perform {
        previousJobs.joinAll()
        inactiveJobs = inactiveJobs.filterNot { it in previousJobs }
        if (freshEntryRequested) {
          // Keep an accepted/ambiguous execution addressable until its terminal
          // state is known. Completed history is only reopened explicitly.
          if (!hasUnfinishedRun()) clearCurrentConversation()
          freshEntryRequested = false
        }
        if (!snapshot.configured || authPaused) return@perform
        // Cached facts are only displayed again after current authorization succeeds.
        mutableState.update { it.copy(run = null, plan = null, messages = emptyList(), evidence = null, evidenceBytes = null) }
        if (state.value.recordings.isEmpty()) refreshRecordingData()
        when {
          snapshot.pending?.runId != null -> recoverRun()
          snapshot.pending?.plan != null -> preparePlan()
          snapshot.pending != null -> preparePlan()
          snapshot.lastRun != null -> {
            val id = requireTraceId(snapshot.lastRun!!.string("run_id") ?: "")
            val run = validateTraceRun(api().get("/api/v1/agent/runs/$id"), id, snapshot.conversationId)
            applyRun(run)
            if (run.string("status") == "running") startPolling(id)
            if (run.string("status") != "completed" && snapshot.conversationId != null) readMessages(false)
          }
          snapshot.conversationId != null -> readMessages(false)
        }
      }
    }
  }

  private fun perform(action: suspend () -> Unit) {
    if (operation?.isActive == true) return
    val sequence = ++operationSequence
    mutableState.update { it.copy(busy = true, error = null) }
    operation = scope.launch {
      try { ensureInitialized(); action() }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: Exception) { showFailure(failure) }
      finally {
        if (sequence == operationSequence) {
          operation = null
          mutableState.update { it.copy(busy = false) }
        }
      }
    }
  }

  private fun requireConfiguration() {
    if (!snapshot.configured) throw TraceApiFailure(0, "invalid_configuration")
  }

  private fun hasUnfinishedRun(): Boolean = snapshot.pending?.runId != null &&
    snapshot.lastRun?.string("status") !in setOf("completed", "stopped", "failed")

  private suspend fun clearCurrentConversation() {
    polling?.cancel(); evidenceJob?.cancel()
    messageCursor = null; conversationCursor = null
    save(snapshot.copy(conversationId = null, pending = null, lastRun = null))
    mutableState.update { it.copy(conversationId = null, messages = emptyList(), conversations = emptyList(),
      hasMoreMessages = false, hasMoreConversations = false, plan = null, run = null, phase = "idle",
      evidence = null, evidenceBytes = null) }
  }

  fun configure(baseUrl: String, bearerToken: String) {
    operationSequence++
    operation?.cancel(); polling?.cancel(); evidenceJob?.cancel(); operation = null
    perform {
      val origin = normalizeTraceBaseUrl(baseUrl) ?: throw TraceApiFailure(0, "invalid_configuration")
      val token = bearerToken.trim().ifEmpty {
        if (normalizeTraceBaseUrl(snapshot.baseUrl) == origin) snapshot.bearerToken else ""
      }
      if (token.isBlank() || token.length > 4096 || token.any { it.isWhitespace() }) throw TraceApiFailure(0, "invalid_configuration")
      val originChanged = origin != snapshot.baseUrl
      val changed = originChanged || token != snapshot.bearerToken
      authPaused = false
      save(if (originChanged) TraceAgentSnapshot(origin, token) else snapshot.copy(baseUrl = origin, bearerToken = token))
      if (changed) {
        recordingsLoaded = false
        conversationCursor = null; messageCursor = null
        mutableState.value = TraceAgentState(configured = true, baseUrl = origin, conversationId = snapshot.conversationId)
      } else mutableState.update { it.copy(configured = true, baseUrl = origin, error = null) }
      refreshRecordingData()
      if (snapshot.pending?.runId != null) recoverRun()
      else if (snapshot.pending != null) preparePlan()
    }
  }

  fun refreshRecordings() = perform { requireConfiguration(); refreshRecordingData() }

  private suspend fun refreshRecordingData() {
    requireConfiguration()
    val published = api().get("/api/v1/recordings").objects("recordings")
      .filter { it.string("publication_status")?.let { value -> value == "published" } ?: true }
      .map { recording ->
        val id = requireTraceId(recording.string("recording_id") ?: "")
        val camera = requireTraceId(recording.string("camera_id") ?: "")
        val config = requireTraceId(recording.string("config_version") ?: "")
        val duration = (recording["duration_ms"] as? JsonPrimitive)?.longOrNull ?: throw TraceApiFailure(0, "invalid_response")
        val source = recording.string("source_mode") ?: throw TraceApiFailure(0, "invalid_response")
        if (duration <= 0 || source !in setOf("mock", "sdk", "prerecorded")) throw TraceApiFailure(0, "invalid_response")
        TraceRecording(id, camera, config, duration, source)
      }
    val mockIds = published.filter { it.sourceMode == "mock" }.map { it.id }.toSet()
    // The active execution owns its original scope. A stale selected mock must
    // not erase a different real run's durable ID or bind it to another recording.
    val activeRecording = snapshot.pending?.query?.string("recording_id")
      ?: snapshot.lastRun?.objectValue("plan")?.objectValue("query")?.string("recording_id")
      ?: snapshot.recordingId
    if (activeRecording in mockIds) {
      polling?.cancel(); evidenceJob?.cancel()
      conversationCursor = null; messageCursor = null
      save(snapshot.copy(conversationId = null, recordingId = null, zoneId = null, pending = null, lastRun = null))
      mutableState.update { it.copy(conversationId = null, messages = emptyList(), conversations = emptyList(),
        hasMoreMessages = false, hasMoreConversations = false, plan = null, run = null, phase = "idle",
        evidence = null, evidenceBytes = null, selectedRecordingId = null, selectedZoneId = null, zones = emptyList()) }
    }
    val recordings = published.filter { it.isRealAnalysisSource }
    val selected = recordings.firstOrNull { it.id == snapshot.recordingId } ?: recordings.firstOrNull()
    mutableState.update { it.copy(recordings = recordings, selectedRecordingId = selected?.id) }
    recordingsLoaded = true
    if (selected != null) loadZones(selected)
    else {
      save(snapshot.copy(recordingId = null, zoneId = null))
      mutableState.update { it.copy(zones = emptyList(), selectedZoneId = null) }
    }
  }

  private suspend fun recordingZones(recording: TraceRecording): List<String> {
    val response = api().get("/api/v1/configs/${requireTraceId(recording.configVersion)}")
    val config = response.objectValue("config") ?: throw TraceApiFailure(0, "invalid_response")
    if (config.string("config_version") != recording.configVersion) throw TraceApiFailure(0, "invalid_response")
    val camera = config.objects("cameras").singleOrNull { it.string("camera_id") == recording.cameraId }
      ?: throw TraceApiFailure(0, "invalid_scope")
    return camera.objects("zones").map { requireTraceId(it.string("zone_id") ?: "") }.distinct()
  }

  private suspend fun loadZones(recording: TraceRecording) {
    val zones = recordingZones(recording)
    val zone = snapshot.zoneId?.takeIf { it in zones } ?: zones.firstOrNull()
    save(snapshot.copy(recordingId = recording.id, zoneId = zone))
    mutableState.update { it.copy(selectedRecordingId = recording.id, zones = zones, selectedZoneId = zone) }
  }

  private suspend fun requireRealQuery(query: JsonObject) {
    val recording = state.value.recordings.firstOrNull {
      it.id == query.string("recording_id") && it.isRealAnalysisSource
    } ?: throw TraceApiFailure(0, "real_recording_required")
    val start = (query["start_offset_ms"] as? JsonPrimitive)?.longOrNull
    val end = (query["end_offset_ms"] as? JsonPrimitive)?.longOrNull
    if (query.string("camera_id") != recording.cameraId || start == null || end == null ||
      start < 0 || end <= start || end > recording.durationMs ||
      query.string("zone_id") !in recordingZones(recording)) throw TraceApiFailure(0, "invalid_scope")
  }

  fun selectRecording(recordingId: String) = perform {
    requireConfiguration()
    if (hasUnfinishedRun()) throw TraceApiFailure(0, "run_in_progress")
    val selected = state.value.recordings.firstOrNull { it.id == recordingId } ?: throw TraceApiFailure(0, "invalid_scope")
    save(snapshot.copy(pending = null, lastRun = null, zoneId = null))
    mutableState.update { it.copy(plan = null, run = null, phase = "idle") }
    loadZones(selected)
  }

  fun selectZone(zoneId: String) = perform {
    if (hasUnfinishedRun()) throw TraceApiFailure(0, "run_in_progress")
    if (zoneId !in state.value.zones) throw TraceApiFailure(0, "invalid_scope")
    save(snapshot.copy(zoneId = zoneId, pending = null, lastRun = null))
    mutableState.update { it.copy(selectedZoneId = zoneId, plan = null, run = null, phase = "idle") }
  }

  fun submit(question: String) = perform {
    requireConfiguration()
    if (hasUnfinishedRun()) throw TraceApiFailure(0, "run_in_progress")
    val recording = state.value.recordings.firstOrNull { it.id == snapshot.recordingId } ?: throw TraceApiFailure(0, "invalid_scope")
    val zone = snapshot.zoneId?.takeIf { it in state.value.zones } ?: throw TraceApiFailure(0, "invalid_scope")
    val value = beginTraceQuestion(snapshot, question, traceQuery(recording, zone))
    save(value)
    mutableState.update { it.copy(conversationId = value.conversationId, plan = null, run = null, phase = "planning") }
    preparePlan()
  }

  private suspend fun preparePlan() {
    val pending = snapshot.pending ?: return
    requireRealQuery(pending.query)
    val conversation = snapshot.conversationId ?: throw TraceApiFailure(0, "invalid_response")
    mutableState.update { it.copy(phase = "planning", error = null) }
    val created = api().post("/api/v1/agent/conversations", buildJsonObject { put("conversation_id", conversation) }).objectValue("conversation")
    if (created?.string("conversation_id") != conversation) throw TraceApiFailure(0, "invalid_response")
    val message = api().post("/api/v1/agent/conversations/$conversation/messages", buildJsonObject {
      put("message_id", pending.messageId); put("content", pending.question)
    }).objectValue("message") ?: throw TraceApiFailure(0, "invalid_response")
    if (message.string("message_id") != pending.messageId || message.string("conversation_id") != conversation ||
      message.string("role") != "user" || message.string("content") != pending.question) throw TraceApiFailure(0, "invalid_response")
    mutableState.update { it.copy(messages = (it.messages.filterNot { item -> item.string("message_id") == pending.messageId } + message)) }
    val plan = tracePlan(api().post("/api/v1/agent/plans", buildJsonObject {
      put("input_message_id", pending.messageId); put("scope", pending.query)
    }), pending.query)
    save(snapshot.copy(pending = pending.copy(plan = plan)))
    mutableState.update { it.copy(phase = "plan_ready", plan = plan) }
  }

  fun confirmPlan(queryText: String, steps: List<String>) = perform {
    requireConfiguration()
    requireRealQuery(snapshot.pending?.query ?: throw TraceApiFailure(0, "invalid_plan"))
    // A retry with an allocated ID must always replay its original payload.
    save(confirmTracePlan(snapshot, queryText, steps))
    save(allocateTraceRun(snapshot))
    startRun()
  }

  private suspend fun startRun() {
    val pending = snapshot.pending ?: return
    requireRealQuery(pending.query)
    val id = pending.runId ?: throw TraceApiFailure(0, "invalid_plan")
    val run = api().post("/api/v1/agent/runs", buildJsonObject {
      put("run_id", id); put("input_message_id", pending.messageId); put("plan", pending.plan ?: throw TraceApiFailure(0, "invalid_plan"))
    })
    if (run.string("input_message_id") != pending.messageId || run.objectValue("plan") != pending.plan) throw TraceApiFailure(0, "invalid_response")
    applyRun(validateTraceRun(run, id, snapshot.conversationId))
    if (run.string("status") == "running") startPolling(id)
  }

  private suspend fun recoverRun() {
    val id = snapshot.pending?.runId ?: return
    try {
      val run = validateTraceRun(api().get("/api/v1/agent/runs/$id"), id, snapshot.conversationId)
      applyRun(run)
      if (run.string("status") == "running") startPolling(id)
    } catch (failure: TraceApiFailure) {
      if (failure.status == 404 && failure.code == "run_not_found") startRun() else throw failure
    }
  }

  private suspend fun applyRun(run: JsonObject) {
    save(snapshot.copy(lastRun = run))
    // A failed execution is a valid server result, displayed by the run card.
    // The separate error state is reserved for failed requests or local operations.
    mutableState.update { it.copy(run = run, plan = run.objectValue("plan"), phase = run.string("status")!!,
      conversationId = run.string("conversation_id"), error = null) }
    if (run.string("status") == "completed") readMessages(false)
  }

  private fun startPolling(id: String) {
    polling?.cancel()
    val query = state.value.run?.objectValue("plan")?.objectValue("query")
    // Read-only legacy history must not keep a poll alive that can overwrite a
    // new real question's plan. The user may explicitly reload that history.
    if (!active || state.value.recordings.none {
      it.isRealAnalysisSource && it.id == query?.string("recording_id")
    }) return
    polling = scope.launch {
      while (isActive && active) {
        delay(2_000)
        try {
          val run = validateTraceRun(api().get("/api/v1/agent/runs/$id"), id, snapshot.conversationId)
          applyRun(run)
          if (run.string("status") != "running") return@launch
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { showFailure(failure); return@launch }
      }
    }
  }

  fun stop() {
    operationSequence++
    operation?.cancel(); polling?.cancel(); operation = null
    perform {
      requireConfiguration()
      val id = snapshot.pending?.runId ?: state.value.run?.string("run_id") ?: return@perform
      val stopped = validateTraceRun(api().post("/api/v1/agent/runs/${requireTraceId(id)}/stop", buildJsonObject {}), id, snapshot.conversationId)
      applyRun(stopped)
      // POST remains backward compatible. Re-read actual server telemetry after
      // stop; never infer individual tool cancellation from the run status.
      if (stopped.string("status") == "stopped") {
        try { applyRun(validateTraceRun(api().get("/api/v1/agent/runs/${requireTraceId(id)}"), id, snapshot.conversationId)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: TraceApiFailure) { if (failure.status in setOf(401, 403)) throw failure }
        catch (_: Exception) { /* The committed stop is already durable. */ }
        // A telemetry refresh failure must not turn a committed stop into an
        // execution retry action. The authoritative terminal result stays visible.
      }
    }
  }

  fun retry() = perform {
    requireConfiguration()
    authPaused = false
    // A failed initial list request does not establish that the saved source is
    // simulated or unavailable. Re-authorize/load metadata before applying policy.
    if (!recordingsLoaded) refreshRecordingData()
    val retryQuery = snapshot.pending?.query
      ?: snapshot.lastRun?.objectValue("plan")?.objectValue("query")
    if (retryQuery != null) requireRealQuery(retryQuery)
    when {
      snapshot.pending?.runId != null && snapshot.lastRun?.string("status") in setOf("failed", "stopped") -> {
        save(allocateTraceRun(snapshot, newExecution = true)); startRun()
      }
      snapshot.pending?.runId != null -> recoverRun()
      snapshot.pending?.plan != null -> preparePlan()
      snapshot.pending != null -> preparePlan()
      else -> refreshRecordingData()
    }
  }

  fun loadHistory() = perform { requireConfiguration(); readConversations(false) }
  fun loadMoreConversations() = perform { requireConfiguration(); if (conversationCursor != null) readConversations(true) }

  private suspend fun readConversations(append: Boolean) {
    val path = pagePath("/api/v1/agent/conversations", if (append) conversationCursor else null)
    val result = api().get(path)
    val items = result.objects("conversations")
    items.forEach { requireTraceId(it.string("conversation_id") ?: "") }
    conversationCursor = result.string("next_cursor")
    mutableState.update { it.copy(conversations = distinctItems(if (append) it.conversations + items else items, "conversation_id"),
      hasMoreConversations = conversationCursor != null) }
  }

  fun openConversation(conversationId: String) = perform {
    requireConfiguration(); requireTraceId(conversationId)
    if (hasUnfinishedRun()) throw TraceApiFailure(0, "run_in_progress")
    polling?.cancel()
    save(snapshot.copy(conversationId = conversationId, pending = null, lastRun = null))
    messageCursor = null
    mutableState.update { it.copy(conversationId = conversationId, messages = emptyList(), plan = null, run = null, phase = "idle") }
    readMessages(false)
  }

  fun loadMoreMessages() = perform { requireConfiguration(); if (messageCursor != null) readMessages(true) }

  private suspend fun readMessages(append: Boolean) {
    val conversation = snapshot.conversationId ?: return
    val response = api().get(pagePath("/api/v1/agent/conversations/${requireTraceId(conversation)}/messages", if (append) messageCursor else null))
    val messages = response.objects("messages")
    messages.forEach {
      if (it.string("conversation_id") != conversation || it.string("role") !in setOf("user", "assistant")) throw TraceApiFailure(0, "invalid_response")
      requireTraceId(it.string("message_id") ?: "")
    }
    messageCursor = response.string("next_cursor")
    mutableState.update { it.copy(messages = distinctItems(if (append) it.messages + messages else messages, "message_id"), hasMoreMessages = messageCursor != null) }
  }

  fun newConversation() = perform {
    if (hasUnfinishedRun() && state.value.phase != "error") throw TraceApiFailure(0, "run_in_progress")
    clearCurrentConversation()
  }

  fun openRun(runId: String) = perform {
    requireConfiguration(); requireTraceId(runId)
    if (hasUnfinishedRun() && runId != snapshot.pending?.runId) throw TraceApiFailure(0, "run_in_progress")
    val run = validateTraceRun(api().get("/api/v1/agent/runs/$runId"), runId, snapshot.conversationId)
    if (snapshot.pending?.runId != runId) {
      val inputId = requireTraceId(run.string("input_message_id") ?: "")
      val plan = run.objectValue("plan") ?: throw TraceApiFailure(0, "invalid_response")
      val question = state.value.messages.firstOrNull { it.string("message_id") == inputId }?.string("content").orEmpty()
      // The server already owns this input; this pointer permits safe GET/stop/retry after restart.
      val query = plan.objectValue("query")!!
      val executable = state.value.recordings.any { it.id == query.string("recording_id") && it.isRealAnalysisSource }
      save(snapshot.copy(pending = if (executable) TracePending(inputId, question, query, plan, runId) else null))
    }
    applyRun(run)
    if (run.string("status") == "running") startPolling(runId)
  }

  fun openEvidence(evidenceId: String) {
    evidenceJob?.cancel()
    mutableState.update { it.copy(evidence = null, evidenceBytes = null, error = null) }
    evidenceJob = scope.launch {
      try {
        ensureInitialized(); requireConfiguration(); requireTraceId(evidenceId)
        val evidence = api().get("/api/v1/evidence/$evidenceId").objectValue("evidence") ?: throw TraceApiFailure(0, "invalid_response")
        if (evidence.string("evidence_id") != evidenceId) throw TraceApiFailure(0, "invalid_response")
        mutableState.update { it.copy(evidence = evidence) }
        if (evidence.string("media_status") == "available") {
          // Resolve by ID against the configured origin, never follow an arbitrary media_url.
          val bytes = api().evidenceBytes(evidenceId)
          val valid = withContext(Dispatchers.Default) { validTraceImage(bytes) }
          if (!valid) throw TraceApiFailure(0, "invalid_media")
          mutableState.update { it.copy(evidenceBytes = bytes) }
        }
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: Exception) { showFailure(failure) }
    }
  }

  fun closeEvidence() { evidenceJob?.cancel(); evidenceJob = null; mutableState.update { it.copy(evidence = null, evidenceBytes = null) } }

  private fun showFailure(failure: Throwable) {
    if (failure is TraceApiFailure && failure.status in setOf(401, 403)) {
      authPaused = true
      recordingsLoaded = false
      polling?.cancel(); evidenceJob?.cancel()
      mutableState.update { it.copy(error = traceFailureMessage(failure), phase = "error", busy = false,
        run = null, plan = null, messages = emptyList(), conversations = emptyList(), evidence = null, evidenceBytes = null,
        recordings = emptyList(), zones = emptyList()) }
    } else mutableState.update { it.copy(error = traceFailureMessage(failure), phase = "error", busy = false) }
  }
}

private fun pagePath(path: String, cursor: String?): String = path + "?limit=20" +
  (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
private fun distinctItems(items: List<JsonObject>, id: String): List<JsonObject> = items.distinctBy { it.string(id) }

internal fun traceFailureMessage(error: Throwable): String {
  val failure = error as? TraceApiFailure
  val message = when {
    failure?.status == 401 -> "后端凭据无效，请更新独立 Bearer 配置。"
    failure?.status == 403 -> "当前账号无权访问此会话、录像或证据，请检查后端授权。"
    failure?.status == 404 && failure.code == "media_missing" -> "关键帧当前缺失，原始引用仍保留。"
    failure?.status == 404 -> "资源或 Agent 接口不存在，请确认服务已启用 Agent。"
    failure?.status == 409 -> "稳定 ID 对应的内容发生冲突，请新建问题后再试。"
    failure?.code == "invalid_configuration" -> "请配置后端 HTTPS 基址与独立 Bearer；开发 HTTP 仅允许模拟器或本机回环。"
    failure?.code == "invalid_question" -> "请输入 1 至 2000 字的问题。"
    failure?.code == "invalid_scope" -> "请先选择已发布录像及其有效区域。"
    failure?.code == "real_recording_required" -> "请选择已发布的真实录像；历史模拟分析仅供查看。"
    failure?.code == "run_in_progress" -> "当前分析仍在执行，请先停止或等待完成。"
    failure?.code == "model_unavailable" -> "服务端模型尚未就绪，请检查 Agent 与 Embedding 配置。"
    failure?.code == "agent_busy" -> "服务端分析已达并发限额，请稍后重试。"
    failure?.code == "service_restarted" -> "服务已重启，原分析未完成，可重新执行。"
    failure?.code == "invalid_media" -> "关键帧文件不可显示，原始引用仍保留。"
    failure?.code in setOf("invalid_response", "invalid_plan") -> "服务端返回的范围或数据不符合接口约定，请重试。"
    failure?.status == 422 -> "请求范围或配置不符合后端要求，请检查录像和区域。"
    failure != null -> "分析服务暂时不可用，请重试。"
    else -> "本地分析记录无法读取或保存，请检查设备存储。"
  }
  val code = failure?.code?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,99}")) }
  return message + (code?.let { "（$it）" } ?: "")
}

internal fun validTraceImage(bytes: ByteArray): Boolean {
  val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
  return options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 16_000_000L
}

@Composable
actual fun TraceEvidenceImage(bytes: ByteArray, modifier: Modifier) {
  var bitmap by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
  var loaded by remember(bytes) { mutableStateOf(false) }
  LaunchedEffect(bytes) {
    bitmap = withContext(Dispatchers.Default) {
      runCatching {
        if (!validTraceImage(bytes)) null else {
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
          val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 2048 && bounds.outHeight / it <= 2048 }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
      }.getOrNull()
    }
    loaded = true
  }
  val image = bitmap
  if (image == null) Text(if (loaded) "关键帧文件不可显示" else "正在打开关键帧…", modifier = modifier)
  else Image(image, contentDescription = "分析引用关键帧", modifier = modifier, contentScale = ContentScale.Fit)
}
