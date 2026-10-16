package com.anker.esiotkit.sample.main.station.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TraceAgentNetworkTest {
  private class MemoryStore(@Volatile var value: TraceAgentSnapshot) : TraceAgentStore {
    override fun read() = value
    override fun write(value: TraceAgentSnapshot) { this.value = value }
  }

  private class LocalApi(val handler: (HttpExchange) -> Unit) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { createContext("/") { handler(it) }; start() }
    val origin = "http://127.0.0.1:${server.address.port}"
    override fun close() { server.stop(0) }
  }

  private fun HttpExchange.respond(status: Int, value: JsonObject) {
    val bytes = value.toString().toByteArray(Charsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
  }
  private fun envelope(body: JsonObject) = buildJsonObject { put("api_version", "0.3-mvp-draft"); body.forEach { (key, value) -> put(key, value) } }
  private fun HttpExchange.jsonBody() = traceJson.parseToJsonElement(requestBody.bufferedReader().use { it.readText() }).jsonObject
  private fun error(code: String) = envelope(buildJsonObject { put("error", buildJsonObject { put("code", code); put("message", "安全错误"); put("request_id", "test_request") }) })
  private val recording = buildJsonObject {
    put("recording_id", "rec_1"); put("camera_id", "cam_1"); put("config_version", "v1")
    put("duration_ms", 120000L); put("source_mode", "prerecorded"); put("publication_status", "published")
  }
  private val config = envelope(buildJsonObject { put("config", buildJsonObject {
    put("config_version", "v1"); put("cameras", JsonArray(listOf(buildJsonObject {
      put("camera_id", "cam_1"); put("zones", JsonArray(listOf(buildJsonObject { put("zone_id", "E3") })))
    })))
  }) })

  private fun run(body: JsonObject, conversation: String, status: String = "running") = envelope(buildJsonObject {
    put("run_id", body.getValue("run_id")); put("input_message_id", body.getValue("input_message_id"))
    put("conversation_id", conversation); put("status", status); put("plan", body.getValue("plan"))
    put("summary", JsonNull); put("references", JsonArray(emptyList())); put("error", JsonNull)
    put("created_at", "2026-10-17T00:00:00Z"); put("finished_at", JsonNull)
    // Older deployed service has no cards field. Polling must remain compatible.
  })

  @Test fun `network retry replays durable run and confirmed plan without creating a second execution`() = runBlocking {
    lateinit var store: MemoryStore
    var conversation = ""
    val starts = CopyOnWriteArrayList<JsonObject>()
    val seenAuthorization = CopyOnWriteArrayList<String>()
    LocalApi { exchange ->
      seenAuthorization.add(exchange.requestHeaders.getFirst("Authorization"))
      when {
        exchange.requestURI.path == "/api/v1/recordings" -> exchange.respond(200, envelope(buildJsonObject {
          val pending = buildJsonObject { recording.forEach { (key, value) -> put(key, value) }; put("recording_id", "not_ready"); put("publication_status", "pending") }
          put("recordings", JsonArray(listOf(pending, recording)))
        }))
        exchange.requestURI.path == "/api/v1/configs/v1" -> exchange.respond(200, config)
        exchange.requestURI.path == "/api/v1/agent/conversations" -> {
          val body = exchange.jsonBody(); conversation = body.string("conversation_id")!!
          assertEquals(store.value.conversationId, conversation)
          exchange.respond(201, envelope(buildJsonObject { put("conversation", body) }))
        }
        exchange.requestURI.path.endsWith("/messages") && exchange.requestMethod == "POST" -> {
          val body = exchange.jsonBody()
          assertEquals(store.value.pending?.messageId, body.string("message_id"))
          exchange.respond(201, envelope(buildJsonObject { put("message", buildJsonObject {
            body.forEach { (key, value) -> put(key, value) }; put("conversation_id", conversation); put("role", "user"); put("run_id", JsonNull)
          }) }))
        }
        exchange.requestURI.path == "/api/v1/agent/plans" -> {
          val body = exchange.jsonBody()
          exchange.respond(200, envelope(buildJsonObject { put("query", body.getValue("scope")); put("query_text", "草稿检索"); put("steps", JsonArray(listOf(JsonPrimitive("核对关键帧")))) }))
        }
        exchange.requestURI.path == "/api/v1/agent/runs" -> {
          val body = exchange.jsonBody(); starts.add(body)
          assertEquals(store.value.pending?.runId, body.string("run_id"))
          assertEquals(store.value.pending?.plan, body.objectValue("plan"))
          if (starts.size == 1) exchange.respond(503, error("agent_busy")) else exchange.respond(202, run(body, conversation))
        }
        else -> exchange.respond(404, error("run_not_found"))
      }
    }.use { server ->
      store = MemoryStore(TraceAgentSnapshot(server.origin, "synthetic-viewer"))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val controller = TraceAgentController(scope, store)
        controller.refreshRecordings()
        withTimeout(5000) { controller.state.first { !it.busy && it.recordings.isNotEmpty() } }
        assertEquals(listOf("rec_1"), controller.state.value.recordings.map { it.id })
        controller.submit("检查这一段录像")
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "plan_ready" } }
        controller.confirmPlan(" 用户确认检索 ", listOf("检索候选", "回读证据"))
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "error" } }
        val stableId = store.value.pending!!.runId
        controller.confirmPlan("重试时修改的内容应忽略", listOf("错误覆盖"))
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "running" } }
        assertEquals(2, starts.size)
        assertEquals(starts[0], starts[1])
        assertEquals(stableId, starts[1].string("run_id"))
        assertEquals("用户确认检索", starts[1].objectValue("plan")!!.string("query_text"))
        assertTrue(seenAuthorization.all { it == "Bearer synthetic-viewer" })
      } finally { scope.cancel() }
    }
  }

  @Test fun `revoked restore never publishes persisted answer and retains stable run ID`() = runBlocking {
    val reads = AtomicInteger()
    LocalApi { exchange ->
      when (exchange.requestURI.path) {
        "/api/v1/recordings" -> exchange.respond(200, envelope(buildJsonObject { put("recordings", JsonArray(listOf(recording))) }))
        "/api/v1/configs/v1" -> exchange.respond(200, config)
        else -> { reads.incrementAndGet(); exchange.respond(403, error("forbidden")) }
      }
    }.use { server ->
      val query = traceQuery(TraceRecording("rec_1", "cam_1", "v1", 120000, "prerecorded"), "E3")
      val pending = TracePending("msg_1", "旧问题", query, buildJsonObject { put("query", query); put("query_text", "旧问题"); put("steps", JsonArray(listOf(JsonPrimitive("检索")))) }, "run_1")
      val cached = buildJsonObject { put("run_id", "run_1"); put("status", "completed"); put("summary", "不应显示的旧事实") }
      val store = MemoryStore(TraceAgentSnapshot(server.origin, "synthetic-viewer", "conv_1", "rec_1", "E3", pending, cached))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val controller = TraceAgentController(scope, store)
        controller.initialize()
        withTimeout(5000) { controller.state.first { it.configured } }
        assertNull(controller.state.value.run)
        assertNull(controller.state.value.plan)
        controller.setActive(true)
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "error" } }
        assertNull(controller.state.value.run)
        assertTrue(controller.state.value.messages.isEmpty())
        assertEquals("run_1", store.value.pending?.runId)
        assertEquals(1, reads.get())
        controller.setActive(false); controller.setActive(true)
        withTimeout(5000) { controller.state.first { !it.busy } }
        assertEquals("403 pauses automatic resume", 1, reads.get())
      } finally { scope.cancel() }
    }
  }

  @Test fun `redirect cannot forward Bearer to another endpoint`() = runBlocking {
    val forwarded = AtomicInteger()
    LocalApi { exchange ->
      if (exchange.requestURI.path == "/api/v1/user") {
        exchange.responseHeaders.add("Location", "/unexpected")
        exchange.sendResponseHeaders(302, -1); exchange.close()
      } else { forwarded.incrementAndGet(); exchange.respond(200, envelope(buildJsonObject {})) }
    }.use { server ->
      val failure = try { TraceAgentClient(server.origin, "synthetic-viewer").get("/api/v1/user"); null } catch (error: TraceApiFailure) { error }
      assertEquals(302, failure?.status)
      assertEquals(0, forwarded.get())
    }
  }

  @Test fun `stop accepts completed race and cards then evidence stays on authenticated origin`() = runBlocking {
    val query = traceQuery(TraceRecording("rec_1", "cam_1", "v1", 120000L, "prerecorded"), "E3")
    val plan = buildJsonObject {
      put("query", query); put("query_text", "回读证据"); put("steps", JsonArray(listOf(JsonPrimitive("核对关键帧"))))
    }
    val reference = buildJsonObject { put("event_id", "evt_1"); put("evidence_id", "evd_1") }
    val frame = buildJsonObject {
      reference.forEach { (key, value) -> put(key, value) }; put("offset_ms", 1500L); put("media_status", "available")
    }
    val cards = buildJsonObject {
      put("version", 1); put("run_id", "run_1"); put("query", query)
      put("cards", JsonArray(listOf(
        buildJsonObject {
          put("kind", "run_summary"); put("title", "分析结论"); put("summary", "有一条候选事件")
          put("references", JsonArray(listOf(frame)))
        },
        buildJsonObject {
          put("kind", "event_evidence"); put("title", "事件证据"); put("event_id", "evt_1")
          put("event_type", "shelf_interaction"); put("observation_status", "candidate"); put("detection_source", "manual")
          put("start_offset_ms", 1000L); put("end_offset_ms", 2000L); put("evidence", JsonArray(listOf(frame)))
        },
        buildJsonObject {
          put("kind", "coverage_gap"); put("title", "检索覆盖缺口"); put("gap", "unindexed"); put("detail", "部分事件未索引")
          put("eligible_event_count", 2); put("indexed_event_count", 1)
        }
      )))
    }
    val completed = envelope(buildJsonObject {
      put("run_id", "run_1"); put("conversation_id", "conv_1"); put("input_message_id", "msg_1")
      put("status", "completed"); put("plan", plan); put("summary", "有一条候选事件")
      put("references", JsonArray(listOf(reference))); put("cards", cards); put("error", JsonNull)
      put("created_at", "2026-10-17T00:00:00Z"); put("finished_at", "2026-10-17T00:00:01Z")
    })
    val imageBytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6FVEAAAAASUVORK5CYII=")
    val authenticatedPaths = CopyOnWriteArrayList<String>()
    LocalApi { exchange ->
      assertEquals("Bearer synthetic-viewer", exchange.requestHeaders.getFirst("Authorization"))
      authenticatedPaths.add(exchange.requestURI.path)
      when (exchange.requestURI.path) {
        "/api/v1/agent/runs/run_1/stop" -> {
          assertEquals("POST", exchange.requestMethod)
          assertEquals(buildJsonObject {}, exchange.jsonBody())
          // Completion committed just before stop. The client must honor this terminal result.
          exchange.respond(200, completed)
        }
        "/api/v1/agent/conversations/conv_1/messages" -> exchange.respond(200, envelope(buildJsonObject {
          put("messages", JsonArray(listOf(buildJsonObject {
            put("message_id", "reply_1"); put("conversation_id", "conv_1"); put("role", "assistant")
            put("content", "有一条候选事件"); put("run_id", "run_1")
          }))); put("next_cursor", JsonNull)
        }))
        "/api/v1/evidence/evd_1" -> exchange.respond(200, envelope(buildJsonObject { put("evidence", buildJsonObject {
          frame.forEach { (key, value) -> put(key, value) }
          put("media_url", "https://untrusted.invalid/do-not-forward-credentials")
        }) }))
        "/api/v1/evidence/evd_1/media" -> {
          exchange.responseHeaders.add("Content-Type", "image/png")
          exchange.sendResponseHeaders(200, imageBytes.size.toLong())
          exchange.responseBody.use { it.write(imageBytes) }
        }
        else -> exchange.respond(404, error("run_not_found"))
      }
    }.use { server ->
      val store = MemoryStore(TraceAgentSnapshot(server.origin, "synthetic-viewer", "conv_1", "rec_1", "E3",
        TracePending("msg_1", "回读证据", query, plan, "run_1")))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val controller = TraceAgentController(scope, store)
        controller.stop()
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "completed" } }
        assertEquals("completed", controller.state.value.run?.string("status"))
        assertEquals(cards, controller.state.value.run?.objectValue("cards"))
        assertEquals(listOf("run_summary", "event_evidence", "coverage_gap"),
          controller.state.value.run!!.objectValue("cards")!!.objects("cards").map { it.string("kind") })
        assertEquals(cards, store.value.lastRun?.objectValue("cards"))
        val client = TraceAgentClient(server.origin, "synthetic-viewer")
        val metadata = client.get("/api/v1/evidence/evd_1").objectValue("evidence")!!
        assertEquals("available", metadata.string("media_status"))
        assertArrayEquals(imageBytes, client.evidenceBytes("evd_1"))
        assertEquals(listOf("/api/v1/agent/runs/run_1/stop", "/api/v1/agent/conversations/conv_1/messages",
          "/api/v1/evidence/evd_1", "/api/v1/evidence/evd_1/media"), authenticatedPaths.toList())
      } finally { scope.cancel() }
    }
  }
}
