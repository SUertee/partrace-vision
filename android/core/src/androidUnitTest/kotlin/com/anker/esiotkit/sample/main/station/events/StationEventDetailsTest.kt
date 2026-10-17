package com.anker.esiotkit.sample.main.station.events

import com.anker.esiotkit.sample.main.station.agent.TraceAgentSnapshot
import com.anker.esiotkit.sample.main.station.agent.TraceAgentStore
import com.anker.esiotkit.sample.main.station.pipeline.PeopleSettings
import com.anker.esiotkit.sample.main.station.pipeline.peopleJobId
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StationEventDetailsTest {
  private class MemoryStore(@Volatile var value: TraceAgentSnapshot) : TraceAgentStore {
    override fun read() = value
    override fun write(value: TraceAgentSnapshot) { this.value = value }
  }

  private fun envelope(vararg values: Pair<String, JsonElement>) = buildJsonObject {
    put("api_version", "0.3-mvp-draft"); values.forEach { (key, value) -> put(key, value) }
  }
  private fun HttpExchange.reply(code: Int, body: JsonObject) = bytes(code, "application/json", body.toString().toByteArray())
  private fun HttpExchange.bytes(code: Int, type: String, value: ByteArray) {
    responseHeaders.add("Content-Type", type); sendResponseHeaders(code, value.size.toLong())
    responseBody.use { it.write(value) }
  }
  private fun HttpExchange.fail(code: Int, value: String) = reply(code, envelope("error" to buildJsonObject { put("code", value); put("message", "读取失败") }))
  private fun HttpExchange.parameters(): Map<String, String> = requestURI.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.associate {
    val pieces = it.split('=', limit = 2)
    URLDecoder.decode(pieces.first(), "UTF-8") to URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
  }

  private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6FVEAAAAASUVORK5CYII=")

  private inner class Fixture : AutoCloseable {
    val requests = CopyOnWriteArrayList<String>()
    val authorization = CopyOnWriteArrayList<String>()
    var recordingId = "rec_1"
    var sourceMode = "prerecorded"
    var publication = "published"
    var zones = listOf("zone_a", "zone_b")
    var events: Map<String, List<JsonObject>> = mapOf("zone_a" to listOf(event("evt_1", "zone_a", 1000, listOf("frame_1"))), "zone_b" to emptyList())
    var intercept: ((HttpExchange) -> Boolean)? = null
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/") { exchange ->
        requests.add("${exchange.requestMethod} ${exchange.requestURI.path}" + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""))
        authorization.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
        if (intercept?.invoke(exchange) != true) respond(exchange)
      }
      start()
    }
    val origin get() = "http://127.0.0.1:${server.address.port}"
    val store by lazy { MemoryStore(TraceAgentSnapshot(origin, "viewer-token")) }
    fun settings() = PeopleSettings(origin, "upload-token-never-use", "v1", mapOf("sdk_sn" to "cam_1"))
    fun source(settings: suspend () -> PeopleSettings = { PeopleSettings() }) = AndroidStationEventDetailSource(store, settings)
    fun event(id: String, zone: String, start: Long, evidence: List<String> = emptyList()) = buildJsonObject {
      put("event_id", id); put("recording_id", recordingId); put("zone_id", zone); put("track_key", JsonNull)
      put("event_type", "shelf_interaction"); put("observation_status", "candidate"); put("detection_source", "automatic")
      put("start_offset_ms", start); put("end_offset_ms", start + 500); put("summary", "候选事件"); put("person_id", JsonNull)
      put("evidence_ids", JsonArray(evidence.map(::JsonPrimitive)))
    }
    fun frame(id: String, eventId: String = "evt_1") = buildJsonObject {
      put("evidence_id", id); put("event_id", eventId); put("offset_ms", 1200); put("media_status", "available")
    }
    fun job(status: String = "completed", sdkId: String = "opaque/event:1", camera: String = "cam_1", config: String = "v1") = buildJsonObject {
      put("job_id", recordingId); put("recording_id", recordingId); put("camera_id", camera); put("config_version", config)
      put("sdk_event_id", sdkId); put("event_type", "people"); put("duration_ms", 10000)
      put("status", status); put("event_count", 1); put("evidence_count", 1); put("sdk_timestamp_ms", 1720000000000L)
    }
    fun recording() = buildJsonObject {
      put("recording_id", recordingId); put("camera_id", "cam_1"); put("config_version", "v1"); put("source_mode", sourceMode)
      put("started_at", JsonNull); put("duration_ms", 10000); put("publication_status", publication); put("media_status", "available")
    }
    fun bundle(event: JsonObject, frames: List<JsonObject> = emptyList()) = envelope("event" to event, "evidence" to JsonArray(frames), "observed_points" to JsonArray(emptyList()))
    fun useSdk() {
      recordingId = peopleJobId("sdk_sn", "opaque/event:1"); sourceMode = "sdk"
      events = mapOf("zone_a" to listOf(event("evt_1", "zone_a", 1000, listOf("frame_1"))), "zone_b" to emptyList())
    }
    private fun respond(exchange: HttpExchange) {
      when (exchange.requestURI.path) {
        "/api/v1/recordings/$recordingId" -> exchange.reply(200, envelope("recording" to recording()))
        "/api/v1/recording-jobs/$recordingId" -> exchange.reply(200, envelope("job" to job()))
        "/api/v1/configs/v1" -> exchange.reply(200, envelope("config" to buildJsonObject {
          put("config_version", "v1"); put("cameras", JsonArray(listOf(buildJsonObject {
            put("camera_id", "cam_1"); put("zones", JsonArray(zones.map { zone -> buildJsonObject { put("zone_id", zone) } }))
          })))
        }))
        "/api/v1/events" -> {
          val query = exchange.parameters()
          val found = events[query["zone_id"]].orEmpty()
          exchange.reply(200, envelope("query" to buildJsonObject {
            put("recording_id", query["recording_id"].orEmpty()); put("camera_id", query["camera_id"].orEmpty()); put("zone_id", query["zone_id"].orEmpty())
            put("start_offset_ms", query["start_offset_ms"]?.toLongOrNull() ?: -1L); put("end_offset_ms", query["end_offset_ms"]?.toLongOrNull() ?: -1L)
          }, "query_status" to JsonPrimitive(if (found.isEmpty()) "no_detections" else "matched"), "events" to JsonArray(found)))
        }
        "/api/v1/evidence/frame_1/media" -> exchange.bytes(200, "image/png", png)
        else -> {
          val id = exchange.requestURI.path.removePrefix("/api/v1/events/")
          val event = events.values.flatten().firstOrNull { (it["event_id"] as? JsonPrimitive)?.content == id }
          if (event == null) exchange.fail(404, "event_not_found")
          else exchange.reply(200, bundle(event, if (id == "evt_1") listOf(frame("frame_1")) else emptyList()))
        }
      }
    }
    override fun close() { server.stop(0) }
  }

  @Test fun `backend detail validates ownership reads only its real zone and fetches authenticated evidence`() = runBlocking {
    Fixture().use { fixture ->
      val source = fixture.source()
      val result = source.load(StationEventDetailTarget.BackendEvent("evt_1", "rec_1", "frame_1")) as StationEventDetailsResult.Loaded
      assertEquals("rec_1", result.detail.recording.id)
      assertEquals("evt_1", result.detail.selectedEvent?.id)
      assertEquals(listOf("evt_1"), result.detail.timeline.map { it.id })
      assertEquals("candidate", result.detail.selectedEvent?.observationStatus)
      assertEquals("frame_1", result.detail.focusedEvidenceId)
      val image = source.evidence(result.detail.session, "frame_1") as StationEventImageResult.Loaded
      assertArrayEquals(png, image.bytes)
      val queries = fixture.requests.filter { it.startsWith("GET /api/v1/events?") }
      assertEquals(1, queries.size)
      assertTrue(queries.single().contains("zone_id=zone_a"))
      assertTrue(queries.single().contains("start_offset_ms=0&end_offset_ms=10000"))
      assertTrue(fixture.requests.all { it.startsWith("GET ") })
      assertTrue(fixture.authorization.all { it == "Bearer viewer-token" })
    }
  }

  @Test fun `wrong recording never reaches recording or sibling queries`() = runBlocking {
    Fixture().use { fixture ->
      val result = fixture.source().load(StationEventDetailTarget.BackendEvent("evt_1", "another_recording"))
      assertTrue(result is StationEventDetailsResult.Failed)
      assertEquals(listOf("GET /api/v1/events/evt_1"), fixture.requests.toList())
    }
  }

  @Test fun `evidence owned by another event or an unrelated focused ID is rejected`() = runBlocking {
    Fixture().use { fixture ->
      fixture.intercept = { exchange ->
        if (exchange.requestURI.path == "/api/v1/events/evt_1") {
          exchange.reply(200, fixture.bundle(fixture.events.getValue("zone_a").single(), listOf(fixture.frame("frame_1", "evt_other"))))
          true
        } else false
      }
      assertTrue(fixture.source().load(StationEventDetailTarget.BackendEvent("evt_1", "rec_1")) is StationEventDetailsResult.Failed)
      fixture.intercept = null
      assertTrue(fixture.source().load(StationEventDetailTarget.BackendEvent("evt_1", "rec_1", "frame_unrelated")) is StationEventDetailsResult.Failed)
      assertTrue(fixture.requests.none { it.contains("/recordings/") || it.contains("/evidence/") })
    }
  }

  @Test fun `sdk target requires the same configured service before requesting a job`() = runBlocking {
    Fixture().use { fixture ->
      fixture.useSdk()
      val noSettings = fixture.source().load(StationEventDetailTarget.SdkDetection("sdk_sn", "opaque/event:1")) as StationEventDetailsResult.Unavailable
      assertEquals(StationEventDetailAvailability.NotConfigured, noSettings.kind)
      val different = fixture.source { fixture.settings().copy(baseUrl = "https://different.invalid") }
        .load(StationEventDetailTarget.SdkDetection("sdk_sn", "opaque/event:1")) as StationEventDetailsResult.Unavailable
      assertEquals(StationEventDetailAvailability.DifferentTarget, different.kind)
      assertTrue(fixture.requests.isEmpty())
    }
  }

  @Test fun `sdk job validates opaque native ID camera mapping and configuration`() = runBlocking {
    Fixture().use { fixture ->
      fixture.useSdk()
      for ((sdkId, camera, config) in listOf(Triple("wrong_event", "cam_1", "v1"), Triple("opaque/event:1", "cam_other", "v1"), Triple("opaque/event:1", "cam_1", "v_other"))) {
        fixture.intercept = { exchange ->
          exchange.reply(200, envelope("job" to fixture.job(sdkId = sdkId, camera = camera, config = config))); true
        }
        val result = fixture.source { fixture.settings() }.load(StationEventDetailTarget.SdkDetection("sdk_sn", "opaque/event:1"))
        if (sdkId == "wrong_event") assertTrue(result is StationEventDetailsResult.Failed)
        else assertEquals(StationEventDetailAvailability.DifferentTarget, (result as StationEventDetailsResult.Unavailable).kind)
      }
      assertTrue(fixture.requests.all { it.startsWith("GET /api/v1/recording-jobs/") })
      assertTrue(fixture.authorization.all { it == "Bearer viewer-token" })
    }
  }

  @Test fun `missing sdk job is not a no detections result and never starts an import`() = runBlocking {
    Fixture().use { fixture ->
      fixture.useSdk()
      fixture.intercept = { exchange -> exchange.fail(404, "recording_job_not_found"); true }
      val result = fixture.source { fixture.settings() }.load(StationEventDetailTarget.SdkDetection("sdk_sn", "opaque/event:1")) as StationEventDetailsResult.Unavailable
      assertEquals(StationEventDetailAvailability.NotImported, result.kind)
      assertEquals(1, fixture.requests.size)
      assertTrue(fixture.requests.single().startsWith("GET /api/v1/recording-jobs/"))
    }
  }

  @Test fun `queued processing and failed jobs are distinct and do not query published facts`() = runBlocking {
    Fixture().use { fixture ->
      fixture.useSdk()
      for ((status, expected) in listOf("queued" to StationEventDetailAvailability.Queued, "processing" to StationEventDetailAvailability.Processing, "failed" to StationEventDetailAvailability.AnalysisFailed)) {
        fixture.intercept = { exchange -> exchange.reply(200, envelope("job" to fixture.job(status))); true }
        val result = fixture.source { fixture.settings() }.load(StationEventDetailTarget.SdkDetection("sdk_sn", "opaque/event:1")) as StationEventDetailsResult.Unavailable
        assertEquals(expected, result.kind)
        assertEquals(status, result.job?.status)
      }
      assertEquals(3, fixture.requests.size)
      assertTrue(fixture.requests.all { it.startsWith("GET /api/v1/recording-jobs/") })
    }
  }

  @Test fun `completed sdk job with pending recording does not query events`() = runBlocking {
    Fixture().use { fixture ->
      fixture.useSdk(); fixture.publication = "pending"
      val result = fixture.source { fixture.settings() }.load(StationEventDetailTarget.SdkDetection("sdk_sn", "opaque/event:1")) as StationEventDetailsResult.Unavailable
      assertEquals(StationEventDetailAvailability.NotPublished, result.kind)
      assertTrue(fixture.requests.none { it.contains("/events") || it.contains("/configs/") })
      assertTrue(fixture.authorization.all { it == "Bearer viewer-token" })
    }
  }

  @Test fun `sdk published detail uses configured zones and orders the bounded timeline by offsets`() = runBlocking {
    Fixture().use { fixture ->
      fixture.useSdk()
      fixture.events = mapOf("zone_a" to listOf(fixture.event("evt_later", "zone_a", 6000)), "zone_b" to listOf(fixture.event("evt_earlier", "zone_b", 2000)))
      val result = fixture.source { fixture.settings() }.load(StationEventDetailTarget.SdkDetection("sdk_sn", "opaque/event:1")) as StationEventDetailsResult.Loaded
      assertEquals(listOf("evt_earlier", "evt_later"), result.detail.timeline.map { it.id })
      assertEquals("sdk_sn", result.detail.job?.deviceSn)
      assertEquals("evt_earlier", result.detail.selectedEvent?.id)
      assertFalse(result.detail.limitReached)
      val queryPaths = fixture.requests.filter { it.startsWith("GET /api/v1/events?") }
      assertEquals(2, queryPaths.size)
      assertTrue(queryPaths.any { it.contains("zone_id=zone_a") })
      assertTrue(queryPaths.any { it.contains("zone_id=zone_b") })
      assertTrue(queryPaths.none { it.contains("E3") })
      assertTrue(fixture.requests.all { it.startsWith("GET ") })
    }
  }

  @Test fun `backend sdk source reverse mapping is verified by native ID hash`() = runBlocking {
    Fixture().use { fixture ->
      fixture.useSdk()
      val result = fixture.source { fixture.settings() }.load(StationEventDetailTarget.BackendEvent("evt_1", fixture.recordingId)) as StationEventDetailsResult.Loaded
      assertEquals("sdk_sn", result.detail.job?.deviceSn)
      assertEquals("opaque/event:1", result.detail.job?.sdkEventId)
      assertEquals(1720000000000L, result.detail.job?.sdkTimestampMs)
      assertEquals(fixture.recordingId, result.detail.job?.id)
      assertTrue(fixture.requests.contains("GET /api/v1/recording-jobs/${fixture.recordingId}"))
    }
  }

  @Test fun `backend sdk reverse mapping rejects an unrelated recording hash`() = runBlocking {
    Fixture().use { fixture ->
      fixture.sourceMode = "sdk"
      val result = fixture.source { fixture.settings() }.load(StationEventDetailTarget.BackendEvent("evt_1", "rec_1"))
      assertTrue(result is StationEventDetailsResult.Failed)
      assertTrue(fixture.requests.contains("GET /api/v1/recording-jobs/rec_1"))
    }
  }

  @Test fun `sdk camera without configured zones has no regional process instead of an ownership error`() = runBlocking {
    Fixture().use { fixture ->
      fixture.useSdk(); fixture.zones = emptyList()
      val result = fixture.source { fixture.settings() }.load(StationEventDetailTarget.SdkDetection("sdk_sn", "opaque/event:1")) as StationEventDetailsResult.Unavailable
      assertEquals(StationEventDetailAvailability.NoConfiguredZones, result.kind)
      assertTrue(result.message.contains("尚未配置区域"))
      assertEquals("sdk_sn", result.job?.deviceSn)
      assertEquals("opaque/event:1", result.job?.sdkEventId)
      assertEquals(1720000000000L, result.job?.sdkTimestampMs)
      assertTrue(fixture.requests.contains("GET /api/v1/configs/v1"))
      assertTrue(fixture.requests.none { it.contains("/api/v1/events") })
      // A backend event still has to belong to a real configured zone.
      val invalidBackend = fixture.source { fixture.settings() }.load(StationEventDetailTarget.BackendEvent("evt_1", fixture.recordingId))
      assertTrue(invalidBackend is StationEventDetailsResult.Failed)
    }
  }

  @Test fun `SDK zone and total event bounds remain explicit`() = runBlocking {
    Fixture().use { fixture ->
      fixture.useSdk(); fixture.zones = (0..32).map { "zone_${it.toString().padStart(2, '0')}" }; fixture.events = emptyMap()
      val result = fixture.source { fixture.settings() }.load(StationEventDetailTarget.SdkDetection("sdk_sn", "opaque/event:1")) as StationEventDetailsResult.Loaded
      assertTrue(result.detail.limitReached)
      assertTrue(result.detail.timeline.isEmpty())
      assertEquals(32, fixture.requests.count { it.startsWith("GET /api/v1/events?") })
    }
    Fixture().use { fixture ->
      fixture.useSdk(); fixture.zones = listOf("zone_a")
      fixture.events = mapOf("zone_a" to (0 until 200).map { fixture.event("evt_$it", "zone_a", it * 10L) })
      val result = fixture.source { fixture.settings() }.load(StationEventDetailTarget.SdkDetection("sdk_sn", "opaque/event:1")) as StationEventDetailsResult.Loaded
      assertTrue(result.detail.limitReached)
      assertEquals(200, result.detail.timeline.size)
    }
  }

  @Test fun `source credential changes block old session images before sending another request`() = runBlocking {
    Fixture().use { fixture ->
      val source = fixture.source()
      val result = source.load(StationEventDetailTarget.BackendEvent("evt_1", "rec_1")) as StationEventDetailsResult.Loaded
      val previousRequests = fixture.requests.size
      fixture.store.value = fixture.store.value.copy(bearerToken = "changed-viewer-token")
      assertTrue(source.evidence(result.detail.session, "frame_1") is StationEventImageResult.Failed)
      assertEquals(previousRequests, fixture.requests.size)
    }
  }

  @Test fun `image session rejects unrelated IDs and preserves image authorization failures`() = runBlocking {
    Fixture().use { fixture ->
      val source = fixture.source()
      val detail = (source.load(StationEventDetailTarget.BackendEvent("evt_1", "rec_1")) as StationEventDetailsResult.Loaded).detail
      val before = fixture.requests.size
      assertTrue(source.evidence(detail.session, "unrelated_frame") is StationEventImageResult.Failed)
      assertEquals(before, fixture.requests.size)
      fixture.intercept = { exchange ->
        if (exchange.requestURI.path == "/api/v1/evidence/frame_1/media") { exchange.fail(403, "forbidden"); true } else false
      }
      val failure = source.evidence(detail.session, "frame_1") as StationEventImageResult.Failed
      assertTrue(failure.message.contains("无权"))
      assertEquals(before + 1, fixture.requests.size)
      assertTrue(fixture.authorization.all { it == "Bearer viewer-token" })
    }
  }

  @Test fun `cancelling evidence loading cancels the request instead of returning a failure card`() = runBlocking {
    Fixture().use { fixture ->
      val source = fixture.source()
      val detail = (source.load(StationEventDetailTarget.BackendEvent("evt_1", "rec_1")) as StationEventDetailsResult.Loaded).detail
      val entered = CompletableDeferred<Unit>()
      val release = CountDownLatch(1)
      fixture.intercept = { exchange ->
        if (exchange.requestURI.path == "/api/v1/evidence/frame_1/media") {
          entered.complete(Unit)
          release.await(5, TimeUnit.SECONDS)
          runCatching { exchange.bytes(200, "image/png", png) }
          true
        } else false
      }
      val image = async { source.evidence(detail.session, "frame_1") }
      try {
        withTimeout(2000) { entered.await() }
        image.cancelAndJoin()
        assertTrue(image.isCancelled)
      } finally { release.countDown() }
    }
  }

  @Test fun `authorization failures are not downgraded to missing task or empty events`() = runBlocking {
    Fixture().use { fixture ->
      fixture.useSdk()
      fixture.intercept = { exchange -> exchange.fail(403, "forbidden"); true }
      val result = fixture.source { fixture.settings() }.load(StationEventDetailTarget.SdkDetection("sdk_sn", "opaque/event:1")) as StationEventDetailsResult.Failed
      assertTrue(result.message.contains("无权"))
      assertEquals(1, fixture.requests.size)
      assertEquals(listOf("Bearer viewer-token"), fixture.authorization.toList())
    }
  }

  private class PlaybackHandle : StationRecordingPlayback {
    var released = false
    override fun release() { released = true }
  }

  @Test fun `recording playback reuses the validated session recording and viewer identity`() = runBlocking {
    Fixture().use { fixture ->
      val handle = PlaybackHandle()
      var requests = 0
      val source = AndroidStationEventDetailSource(fixture.store, recordingLoader = { origin, bearer, recordingId ->
        assertEquals(fixture.origin, origin)
        assertEquals("viewer-token", bearer)
        assertEquals("rec_1", recordingId)
        requests++
        handle
      })
      val detail = (source.load(StationEventDetailTarget.BackendEvent("evt_1", "rec_1")) as StationEventDetailsResult.Loaded).detail
      val result = source.recording(detail.session, "rec_1") as StationEventRecordingResult.Loaded
      assertSame(handle, result.playback)
      assertEquals(1, requests)
      result.playback.release()
      assertTrue(handle.released)
    }
  }

  @Test fun `recording playback rejects another recording and changed credentials before downloading`() = runBlocking {
    Fixture().use { fixture ->
      var requests = 0
      val source = AndroidStationEventDetailSource(fixture.store, recordingLoader = { _, _, _ -> requests++; PlaybackHandle() })
      val detail = (source.load(StationEventDetailTarget.BackendEvent("evt_1", "rec_1")) as StationEventDetailsResult.Loaded).detail
      assertTrue(source.recording(detail.session, "another_recording") is StationEventRecordingResult.Failed)
      assertTrue(source.recording(detail.session.copy(id = "unknown"), "rec_1") is StationEventRecordingResult.Failed)
      fixture.store.value = fixture.store.value.copy(bearerToken = "changed-token")
      assertTrue(source.recording(detail.session, "rec_1") is StationEventRecordingResult.Failed)
      assertEquals(0, requests)
    }
  }

  @Test fun `recording configuration changes during download release the private media handle`() = runBlocking {
    Fixture().use { fixture ->
      val handle = PlaybackHandle()
      val source = AndroidStationEventDetailSource(fixture.store, recordingLoader = { _, _, _ ->
        fixture.store.value = fixture.store.value.copy(bearerToken = "changed-token")
        handle
      })
      val detail = (source.load(StationEventDetailTarget.BackendEvent("evt_1", "rec_1")) as StationEventDetailsResult.Loaded).detail
      val result = source.recording(detail.session, "rec_1") as StationEventRecordingResult.Failed
      assertTrue(result.message.contains("配置已变化"))
      assertTrue(handle.released)
    }
  }

}
