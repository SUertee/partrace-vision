package com.anker.esiotkit.sample.main.station.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.anker.esiotkit.sample.main.station.agent.AndroidTraceAgentStore
import com.anker.esiotkit.sample.main.station.agent.TraceAgentClient
import com.anker.esiotkit.sample.main.station.agent.TraceAgentSnapshot
import com.anker.esiotkit.sample.main.station.agent.TraceAgentStore
import com.anker.esiotkit.sample.main.station.agent.TraceApiFailure
import com.anker.esiotkit.sample.main.station.agent.normalizeTraceBaseUrl
import com.anker.esiotkit.sample.main.station.agent.objectValue
import com.anker.esiotkit.sample.main.station.agent.objects
import com.anker.esiotkit.sample.main.station.agent.requireTraceId
import com.anker.esiotkit.sample.main.station.agent.string
import com.anker.esiotkit.sample.main.station.pipeline.PeoplePipeline
import com.anker.esiotkit.sample.main.station.pipeline.PeopleSettings
import com.anker.esiotkit.sample.main.station.pipeline.peopleDigest
import com.anker.esiotkit.sample.main.station.pipeline.peopleJobId
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

@Composable
actual fun rememberStationEventDetailSource(): StationEventDetailSource {
  val context = LocalContext.current.applicationContext
  return remember(context) { AndroidStationEventDetailSource(AndroidTraceAgentStore(context),
    recordingLoader = { origin, bearer, recordingId -> downloadStationRecording(context.cacheDir, origin, bearer, recordingId) }) }
}

/** Read-only facts adapter. It never uses the pipeline's upload Bearer or invokes a model. */
internal class AndroidStationEventDetailSource(
  private val store: TraceAgentStore,
  private val pipelineSettings: suspend () -> PeopleSettings = { PeoplePipeline.settings() },
  private val clientFactory: (String, String) -> TraceAgentClient = { origin, token -> TraceAgentClient(origin, token) },
  private val recordingLoader: (suspend (String, String, String) -> StationRecordingPlayback)? = null
) : StationEventDetailSource {
  private data class Boundary(val origin: String, val credentialDigest: String)
  private data class ReadSession(val boundary: Boundary, val evidenceIds: Set<String>, val recording: StationDetailRecording)
  private val sessions = linkedMapOf<String, ReadSession>()
  private val sessionLock = Mutex()

  override suspend fun load(target: StationEventDetailTarget): StationEventDetailsResult = withContext(Dispatchers.IO) {
    try {
      val config = store.read()
      if (!config.configured) return@withContext unavailable(StationEventDetailAvailability.NotConfigured, "请先在服务设置中连接后端，摄像头原始记录仍可查看。")
      val boundary = boundaryOf(config)
      val api = clientFactory(boundary.origin, config.bearerToken)
      val result = when (target) {
        is StationEventDetailTarget.BackendEvent -> loadBackend(target, api, boundary)
        is StationEventDetailTarget.SdkDetection -> loadSdk(target, api, boundary)
      }
      if (boundaryOf(store.read()) != boundary) throw TraceApiFailure(0, "source_changed")
      result
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { StationEventDetailsResult.Failed(detailFailure(failure)) }
  }

  private suspend fun loadBackend(target: StationEventDetailTarget.BackendEvent, api: TraceAgentClient, boundary: Boundary): StationEventDetailsResult {
    requireTraceId(target.eventId); requireTraceId(target.expectedRecordingId)
    target.focusedEvidenceId?.let(::requireTraceId)
    val bundle = readBundle(api, target.eventId, target.expectedRecordingId)
    if (target.focusedEvidenceId != null && bundle.second.none { it.id == target.focusedEvidenceId }) invalid()
    val recording = readRecording(api, target.expectedRecordingId)
    if (recording.publicationStatus != "published") return unavailable(StationEventDetailAvailability.NotPublished, "原始录像已接收，分析结果尚未发布。")
    checkEventRange(bundle.first, recording)
    checkEvidenceRanges(bundle.second, recording)
    // Agent runs are scoped to one zone; retain that exact zone for sibling events.
    val timeline = readTimeline(api, recording, bundle.first.zoneId)
    if (!timeline.limited && timeline.events.none { it.id == bundle.first.id }) invalid()
    val sdkJob = readOptionalSdkJob(api, recording, boundary)
    return loaded(recording, bundle.first, timeline.events, bundle.second, target.focusedEvidenceId, sdkJob, boundary, timeline.limited)
  }

  private suspend fun loadSdk(target: StationEventDetailTarget.SdkDetection, api: TraceAgentClient, boundary: Boundary): StationEventDetailsResult {
    if (target.deviceSn.isBlank() || target.sdkEventId.isBlank() || target.sdkEventId.any { it.isISOControl() }) invalid()
    val settings = pipelineSettings()
    if (!settings.configured) return unavailable(StationEventDetailAvailability.NotConfigured, "尚未配置该摄像头的解析服务；这里只显示 SDK 检测记录。")
    if (normalizeTraceBaseUrl(settings.baseUrl) != boundary.origin) return unavailable(StationEventDetailAvailability.DifferentTarget, "聊天服务与摄像头解析服务地址不同，暂不能关联解析结果。")
    val expectedCamera = settings.cameraMapping[target.deviceSn]
      ?: return unavailable(StationEventDetailAvailability.DifferentTarget, "该摄像头尚未配置后端镜头映射。")
    val id = peopleJobId(target.deviceSn, target.sdkEventId)
    val response = try { api.get("/api/v1/recording-jobs/$id") }
    catch (failure: TraceApiFailure) {
      if (failure.status == 404 && failure.code == "recording_job_not_found") return unavailable(StationEventDetailAvailability.NotImported, "尚未找到这条 SDK 检测对应的解析任务；不代表没有检测事件。")
      throw failure
    }
    val raw = response.objectValue("job") ?: invalid()
    if (raw.string("job_id") != id || raw.string("recording_id") != id || raw.string("sdk_event_id") != target.sdkEventId) invalid()
    if (raw.string("camera_id") != expectedCamera || raw.string("config_version") != settings.configVersion) {
      return unavailable(StationEventDetailAvailability.DifferentTarget, "已有任务的镜头或配置标识与当前映射不一致，暂不能关联。")
    }
    if (raw.string("event_type") != "people" || raw.long("duration_ms") <= 0) invalid()
    if (pipelineSettings().targetKey(target.deviceSn) != settings.targetKey(target.deviceSn)) return unavailable(StationEventDetailAvailability.DifferentTarget, "解析服务配置已变化，请刷新后重新打开。")
    val status = raw.string("status") ?: invalid()
    if (status !in setOf("queued", "processing", "completed", "failed")) invalid()
    val job = StationDetailJob(id, status, target.sdkEventId, raw.optionalInt("event_count"), raw.optionalInt("evidence_count"), target.deviceSn, raw.optionalLong("sdk_timestamp_ms"))
    when (status) {
      "queued" -> return unavailable(StationEventDetailAvailability.Queued, "原始录像已接收，等待解析；暂无已发布过程结果。", job)
      "processing" -> return unavailable(StationEventDetailAvailability.Processing, "正在解析这段录像，过程结果尚未发布。", job)
      "failed" -> return unavailable(StationEventDetailAvailability.AnalysisFailed, "这段录像解析失败，请在人物录像任务中检查状态；不能判断为无事件。", job)
    }
    val recording = readRecording(api, id)
    if (recording.cameraId != expectedCamera || recording.configVersion != settings.configVersion || recording.sourceMode != "sdk" || recording.durationMs != raw.long("duration_ms")) invalid()
    if (recording.publicationStatus != "published") return unavailable(StationEventDetailAvailability.NotPublished, "任务已返回，但分析事实尚未发布，请稍后刷新。", job)
    val timeline = try { readTimeline(api, recording, null) }
    catch (_: NoConfiguredZones) {
      return unavailable(StationEventDetailAvailability.NoConfiguredZones, "该镜头尚未配置区域，暂无区域过程；SDK 原片仍可回放。", job)
    }
    val selected = timeline.events.firstOrNull()
    val evidence = if (selected == null) emptyList() else readBundle(api, selected.id, recording.id).let { bundle ->
      if (bundle.first != selected) invalid()
      checkEvidenceRanges(bundle.second, recording)
      bundle.second
    }
    // A configuration change during any request invalidates this correlation.
    val current = pipelineSettings()
    if (current.targetKey(target.deviceSn) != settings.targetKey(target.deviceSn)) return unavailable(StationEventDetailAvailability.DifferentTarget, "解析服务配置已变化，请刷新后重新打开。")
    return loaded(recording, selected, timeline.events, evidence, null, job, boundary, timeline.limited)
  }

  private suspend fun readOptionalSdkJob(api: TraceAgentClient, recording: StationDetailRecording, boundary: Boundary): StationDetailJob? {
    if (recording.sourceMode != "sdk") return null
    val settings = pipelineSettings()
    if (!settings.configured || normalizeTraceBaseUrl(settings.baseUrl) != boundary.origin || settings.configVersion != recording.configVersion) return null
    val candidates = settings.cameraMapping.filterValues { it == recording.cameraId }.keys
    if (candidates.isEmpty()) return null
    val response = try { api.get("/api/v1/recording-jobs/${requireTraceId(recording.id)}") }
    catch (failure: TraceApiFailure) {
      if (failure.status == 404 && failure.code == "recording_job_not_found") return null
      throw failure
    }
    val raw = response.objectValue("job") ?: invalid()
    if (raw.string("job_id") != recording.id || raw.string("recording_id") != recording.id ||
      raw.string("camera_id") != recording.cameraId || raw.string("config_version") != recording.configVersion ||
      raw.string("event_type") != "people" || raw.long("duration_ms") != recording.durationMs) invalid()
    val sdkId = raw.required("sdk_event_id")
    if (sdkId.any { it.isISOControl() }) invalid()
    val sn = candidates.singleOrNull { peopleJobId(it, sdkId) == recording.id } ?: invalid()
    val status = raw.required("status").also { if (it !in setOf("queued", "processing", "completed", "failed")) invalid() }
    if (pipelineSettings().targetKey(sn) != settings.targetKey(sn)) throw TraceApiFailure(0, "source_changed")
    return StationDetailJob(recording.id, status, sdkId, raw.optionalInt("event_count"), raw.optionalInt("evidence_count"), sn, raw.optionalLong("sdk_timestamp_ms"))
  }

  private suspend fun readRecording(api: TraceAgentClient, id: String): StationDetailRecording {
    val raw = api.get("/api/v1/recordings/${requireTraceId(id)}").objectValue("recording") ?: invalid()
    if (raw.string("recording_id") != id) invalid()
    val duration = raw.long("duration_ms").also { if (it <= 0) invalid() }
    val source = raw.required("source_mode").also { if (it !in setOf("sdk", "prerecorded", "mock")) invalid() }
    val publication = raw.required("publication_status").also { if (it !in setOf("pending", "published")) invalid() }
    val media = raw.mediaStatus()
    return StationDetailRecording(id, requireTraceId(raw.required("camera_id")), requireTraceId(raw.required("config_version")),
      source, duration, publication, media, raw.string("started_at"))
  }

  private suspend fun readBundle(api: TraceAgentClient, id: String, recordingId: String): Pair<StationDetailEvent, List<StationDetailEvidence>> {
    val response = api.get("/api/v1/events/${requireTraceId(id)}")
    val event = parseEvent(response.objectValue("event") ?: invalid(), recordingId)
    if (event.id != id) invalid()
    val evidence = response.objects("evidence").map { raw ->
      val eventId = requireTraceId(raw.required("event_id"))
      if (eventId != id) invalid()
      StationDetailEvidence(requireTraceId(raw.required("evidence_id")), eventId, raw.long("offset_ms").also { if (it < 0) invalid() }, raw.mediaStatus())
    }
    if (evidence.size > 200 || evidence.map { it.id }.toSet().size != evidence.size || evidence.map { it.id }.toSet() != event.evidenceIds.toSet()) invalid()
    return event to evidence.sortedWith(compareBy<StationDetailEvidence> { it.offsetMs }.thenBy { it.id })
  }

  private data class Timeline(val events: List<StationDetailEvent>, val limited: Boolean)
  private class NoConfiguredZones : Exception()
  private suspend fun readTimeline(api: TraceAgentClient, recording: StationDetailRecording, onlyZone: String?): Timeline {
    val config = api.get("/api/v1/configs/${requireTraceId(recording.configVersion)}").objectValue("config") ?: invalid()
    if (config.string("config_version") != recording.configVersion) invalid()
    val camera = config.objects("cameras").singleOrNull { it.string("camera_id") == recording.cameraId } ?: invalid()
    val allZones = camera.objects("zones").map { requireTraceId(it.required("zone_id")) }.distinct().sorted()
    if (allZones.isEmpty()) {
      if (onlyZone == null) throw NoConfiguredZones()
      invalid()
    }
    if (onlyZone != null && onlyZone !in allZones) invalid()
    val zones = if (onlyZone == null) allZones.take(32) else listOf(onlyZone)
    var limited = onlyZone == null && allZones.size > 32
    val byId = linkedMapOf<String, StationDetailEvent>()
    for (zone in zones) {
      val path = "/api/v1/events?recording_id=${encoded(recording.id)}&camera_id=${encoded(recording.cameraId)}&zone_id=${encoded(zone)}&start_offset_ms=0&end_offset_ms=${recording.durationMs}"
      val response = api.get(path)
      val query = response.objectValue("query") ?: invalid()
      if (query.string("recording_id") != recording.id || query.string("camera_id") != recording.cameraId ||
        query.string("zone_id") != zone || query.long("start_offset_ms") != 0L || query.long("end_offset_ms") != recording.durationMs) invalid()
      if (response.string("query_status") !in setOf("matched", "no_detections")) invalid()
      val events = response.objects("events")
      if (events.size >= 200) limited = true
      for (raw in events) {
        val event = parseEvent(raw, recording.id)
        if (event.zoneId != zone) invalid()
        checkEventRange(event, recording)
        val previous = byId[event.id]
        if (previous != null && previous != event) invalid()
        if (previous == null && byId.size < 200) byId[event.id] = event
        else if (previous == null) limited = true
      }
      if (byId.size >= 200) { limited = true; break }
    }
    return Timeline(byId.values.sortedWith(compareBy<StationDetailEvent> { it.startOffsetMs }.thenBy { it.endOffsetMs }.thenBy { it.id }), limited)
  }

  private fun parseEvent(raw: JsonObject, recordingId: String): StationDetailEvent {
    if (raw.string("recording_id") != recordingId) invalid()
    val observation = raw.required("observation_status").also { if (it !in setOf("observed", "candidate")) invalid() }
    val detection = raw.required("detection_source").also { if (it !in setOf("automatic", "manual", "mock")) invalid() }
    val ids = (raw["evidence_ids"] as? JsonArray ?: invalid()).map { requireTraceId((it as? JsonPrimitive)?.contentOrNull ?: invalid()) }
    if (ids.size > 200 || ids.toSet().size != ids.size) invalid()
    return StationDetailEvent(requireTraceId(raw.required("event_id")), recordingId, requireTraceId(raw.required("zone_id")), raw.required("event_type"),
      observation, detection, raw.long("start_offset_ms"), raw.long("end_offset_ms"), raw.string("summary"), raw.string("person_id"), ids)
  }

  private fun checkEventRange(event: StationDetailEvent, recording: StationDetailRecording) {
    if (event.startOffsetMs < 0 || event.endOffsetMs < event.startOffsetMs || event.endOffsetMs > recording.durationMs) invalid()
  }
  private fun checkEvidenceRanges(evidence: List<StationDetailEvidence>, recording: StationDetailRecording) {
    if (evidence.any { it.offsetMs !in 0..recording.durationMs }) invalid()
  }

  private suspend fun loaded(recording: StationDetailRecording, selected: StationDetailEvent?, timeline: List<StationDetailEvent>,
    evidence: List<StationDetailEvidence>, focused: String?, job: StationDetailJob?, boundary: Boundary, limited: Boolean): StationEventDetailsResult {
    if (boundaryOf(store.read()) != boundary) throw TraceApiFailure(0, "source_changed")
    val id = UUID.randomUUID().toString()
    sessionLock.withLock {
      sessions[id] = ReadSession(boundary, evidence.filter { it.mediaStatus == "available" }.map { it.id }.toSet(), recording)
      while (sessions.size > 8) sessions.remove(sessions.keys.first())
    }
    return StationEventDetailsResult.Loaded(StationEventDetails(recording, selected, timeline, evidence, focused, job,
      StationEventDetailSession(boundary.origin, id), limited))
  }

  override suspend fun evidence(session: StationEventDetailSession, evidenceId: String): StationEventImageResult = withContext(Dispatchers.IO) {
    try {
      requireTraceId(evidenceId)
      val readSession = sessionLock.withLock { sessions[session.id] } ?: throw TraceApiFailure(0, "source_changed")
      val config = store.read()
      if (!config.configured || readSession.boundary != boundaryOf(config) || session.origin != readSession.boundary.origin) throw TraceApiFailure(0, "source_changed")
      if (evidenceId !in readSession.evidenceIds) throw TraceApiFailure(0, "invalid_scope")
      val bytes = clientFactory(readSession.boundary.origin, config.bearerToken).evidenceBytes(evidenceId)
      if (boundaryOf(store.read()) != readSession.boundary) throw TraceApiFailure(0, "source_changed")
      StationEventImageResult.Loaded(bytes)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { StationEventImageResult.Failed(detailFailure(failure)) }
  }

  override suspend fun recording(session: StationEventDetailSession, recordingId: String): StationEventRecordingResult {
    var playback: StationRecordingPlayback? = null
    return try { withContext(Dispatchers.IO) {
      requireTraceId(recordingId)
      val readSession = sessionLock.withLock { sessions[session.id] } ?: throw TraceApiFailure(0, "source_changed")
      val config = store.read()
      if (!config.configured || readSession.boundary != boundaryOf(config) || session.origin != readSession.boundary.origin)
        throw TraceApiFailure(0, "source_changed")
      if (readSession.recording.id != recordingId || readSession.recording.sourceMode !in setOf("sdk", "prerecorded") ||
        readSession.recording.mediaStatus != "available") throw TraceApiFailure(0, "invalid_scope")
      val loader = recordingLoader ?: throw TraceApiFailure(0, "playback_unavailable")
      playback = loader(readSession.boundary.origin, config.bearerToken, recordingId)
      if (boundaryOf(store.read()) != readSession.boundary) throw TraceApiFailure(0, "source_changed")
      StationEventRecordingResult.Loaded(playback!!)
    } } catch (cancelled: CancellationException) { playback?.release(); throw cancelled }
    catch (failure: Exception) {
      playback?.release()
      StationEventRecordingResult.Failed(when {
        failure is TraceApiFailure && failure.code == "media_missing" -> "录像原片当前不可用，请刷新后重试。"
        failure is TraceApiFailure && failure.code == "response_too_large" -> "原片超过本次回看大小限制，已停止读取。"
        failure is TraceApiFailure && failure.code == "invalid_media" -> "后端未返回可播放的 MP4 原片。"
        failure is TraceApiFailure && failure.code == "playback_unavailable" -> "当前平台暂不支持原片回看。"
        else -> detailFailure(failure)
      })
    }
  }

  private fun boundaryOf(config: TraceAgentSnapshot) = Boundary(normalizeTraceBaseUrl(config.baseUrl) ?: throw TraceApiFailure(0, "invalid_configuration"), peopleDigest(config.bearerToken))
  private fun unavailable(kind: StationEventDetailAvailability, message: String, job: StationDetailJob? = null) = StationEventDetailsResult.Unavailable(kind, message, job)
  private fun invalid(): Nothing = throw TraceApiFailure(0, "invalid_response")
  private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
  private fun JsonObject.required(key: String) = string(key)?.takeIf { it.isNotBlank() } ?: invalid()
  private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: invalid()
  private fun JsonObject.optionalInt(key: String): Int? = when (val value = get(key)) {
    null, kotlinx.serialization.json.JsonNull -> null
    is JsonPrimitive -> (value.intOrNull ?: invalid()).also { if (it < 0) invalid() }
    else -> invalid()
  }
  private fun JsonObject.optionalLong(key: String): Long? = when (val value = get(key)) {
    null, kotlinx.serialization.json.JsonNull -> null
    is JsonPrimitive -> (value.longOrNull ?: invalid()).also { if (it < 0) invalid() }
    else -> invalid()
  }
  private fun JsonObject.mediaStatus() = required("media_status").also { if (it !in setOf("available", "missing")) invalid() }
}

private fun detailFailure(failure: Exception): String = when {
  failure is TraceApiFailure && failure.status == 401 -> "后端凭据已失效，请更新服务设置。"
  failure is TraceApiFailure && failure.status == 403 -> "当前账号无权读取这段录像、事件或证据。"
  failure is TraceApiFailure && failure.code == "source_changed" -> "服务配置已变化，请重新打开详情。"
  failure is TraceApiFailure && failure.status == 404 && failure.code == "media_missing" -> "关键帧当前不可用，原始引用仍保留。"
  failure is TraceApiFailure && failure.status == 404 -> "对应的录像或事件暂不可访问，请刷新后重试。"
  failure is TraceApiFailure && failure.status == 409 && failure.code == "recording_not_ready" -> "分析结果尚未发布，请稍后刷新。"
  failure is TraceApiFailure && failure.code in setOf("invalid_response", "invalid_scope") -> "事件、录像或证据归属校验失败，已停止显示。"
  else -> "事件详情暂时无法读取，请检查服务和网络后重试。"
}
