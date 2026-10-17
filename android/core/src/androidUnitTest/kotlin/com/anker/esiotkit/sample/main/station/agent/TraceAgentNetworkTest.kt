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
  private val mockRecording = buildJsonObject {
    recording.forEach { (key, value) -> put(key, value) }
    put("recording_id", "rec_mock_001"); put("source_mode", "mock")
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
          // A published mock used to become the automatic selection when it came first.
          put("recordings", JsonArray(listOf(mockRecording, pending, recording)))
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
        assertEquals("rec_1", controller.state.value.selectedRecordingId)
        assertEquals("rec_1", store.value.recordingId)
        controller.submit("检查这一段录像")
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "plan_ready" } }
        controller.confirmPlan(" 用户确认检索 ", listOf("检索候选", "回读证据"))
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "error" } }
        assertNotNull("A failed API request still needs a separate error prompt", controller.state.value.error)
        assertNull(controller.state.value.run)
        val stableId = store.value.pending!!.runId
        controller.confirmPlan("重试时修改的内容应忽略", listOf("错误覆盖"))
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "running" } }
        assertEquals(2, starts.size)
        assertEquals(starts[0], starts[1])
        assertEquals(stableId, starts[1].string("run_id"))
        assertEquals("用户确认检索", starts[1].objectValue("plan")!!.string("query_text"))
        assertNull(controller.state.value.error)
        assertTrue(seenAuthorization.all { it == "Bearer synthetic-viewer" })
      } finally { scope.cancel() }
    }
  }

  @Test fun `failed run restore stays in run card and explicit retry keeps confirmed payload with new run ID`() = runBlocking {
    val query = traceQuery(TraceRecording("rec_1", "cam_1", "v1", 120000L, "prerecorded"), "E3")
    val plan = buildJsonObject {
      put("query", query); put("query_text", "用户确认的检索")
      put("steps", JsonArray(listOf(JsonPrimitive("检索候选"), JsonPrimitive("回读证据"))))
    }
    val originalBody = buildJsonObject { put("run_id", "run_failed"); put("input_message_id", "msg_1"); put("plan", plan) }
    fun failedRun(body: JsonObject) = JsonObject(run(body, "conv_1", "failed") + mapOf(
      "error" to buildJsonObject { put("code", "agent_execution_failed"); put("message", "分析未完成，未保存助手答复。") },
      "finished_at" to JsonPrimitive("2026-10-17T00:00:01Z")
    ))
    val originalRun = failedRun(originalBody)
    val starts = CopyOnWriteArrayList<JsonObject>()
    lateinit var store: MemoryStore
    LocalApi { exchange ->
      when (exchange.requestURI.path) {
        "/api/v1/recordings" -> exchange.respond(200, envelope(buildJsonObject { put("recordings", JsonArray(listOf(recording))) }))
        "/api/v1/configs/v1" -> exchange.respond(200, config)
        "/api/v1/agent/runs/run_failed" -> exchange.respond(200, originalRun)
        "/api/v1/agent/runs" -> {
          val body = exchange.jsonBody(); starts.add(body)
          assertEquals(store.value.pending?.runId, body.string("run_id"))
          exchange.respond(202, failedRun(body))
        }
        else -> exchange.respond(404, error("run_not_found"))
      }
    }.use { server ->
      store = MemoryStore(TraceAgentSnapshot(server.origin, "synthetic-viewer", "conv_1", "rec_1", "E3",
        TracePending("msg_1", "原问题", query, plan, "run_failed"), originalRun))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val controller = TraceAgentController(scope, store)
        controller.setActive(true)
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "failed" } }
        assertEquals(originalRun, controller.state.value.run)
        assertNull("A restored failed run must not also show a service error", controller.state.value.error)
        assertTrue("Restore must not silently rerun a failed execution", starts.isEmpty())
        controller.retry()
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "failed" && starts.isNotEmpty() } }
        assertEquals(1, starts.size)
        val retriedBody = starts.single()
        assertNotEquals("run_failed", retriedBody.string("run_id"))
        assertEquals(originalBody - "run_id", retriedBody - "run_id")
        assertEquals(retriedBody.string("run_id"), controller.state.value.run?.string("run_id"))
        assertEquals("agent_execution_failed", controller.state.value.run?.objectValue("error")?.string("code"))
        assertNull("A newly failed execution also belongs only to its run card", controller.state.value.error)
        assertEquals("failed", store.value.lastRun?.string("status"))
      } finally { scope.cancel() }
    }
  }

  @Test fun `recording list failure preserves real pending run and retry reloads before recovering its stable ID`() = runBlocking {
    val requests = CopyOnWriteArrayList<String>()
    val recordingReads = AtomicInteger()
    val query = traceQuery(TraceRecording("rec_1", "cam_1", "v1", 120000L, "prerecorded"), "E3")
    val plan = buildJsonObject {
      put("query", query); put("query_text", "真实录像的确认计划")
      put("steps", JsonArray(listOf(JsonPrimitive("回读证据"))))
    }
    val pending = TracePending("msg_existing", "真实录像问题", query, plan, "run_existing")
    val originalBody = buildJsonObject { put("run_id", "run_existing"); put("input_message_id", "msg_existing"); put("plan", plan) }
    val existingRun = run(originalBody, "conv_existing", "failed")
    LocalApi { exchange ->
      requests.add("${exchange.requestMethod} ${exchange.requestURI.path}")
      when (exchange.requestURI.path) {
        "/api/v1/recordings" -> if (recordingReads.incrementAndGet() == 1) exchange.respond(503, error("service_unavailable"))
          else exchange.respond(200, envelope(buildJsonObject { put("recordings", JsonArray(listOf(recording))) }))
        "/api/v1/configs/v1" -> exchange.respond(200, config)
        "/api/v1/agent/runs/run_existing" -> exchange.respond(200, existingRun)
        else -> exchange.respond(404, error("run_not_found"))
      }
    }.use { server ->
      val store = MemoryStore(TraceAgentSnapshot(server.origin, "synthetic-viewer", "conv_existing", "rec_1", "E3", pending))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val controller = TraceAgentController(scope, store)
        controller.setActive(true)
        withTimeout(5000) { controller.state.first { !it.busy && it.error != null } }
        assertEquals(pending, store.value.pending)
        assertEquals("conv_existing", store.value.conversationId)
        assertEquals("rec_1", store.value.recordingId)
        assertNull(store.value.lastRun)
        assertEquals(listOf("GET /api/v1/recordings"), requests.toList())

        controller.retry()
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "failed" } }
        assertEquals(2, recordingReads.get())
        assertEquals(pending, store.value.pending)
        assertEquals("msg_existing", store.value.pending?.messageId)
        assertEquals("run_existing", store.value.pending?.runId)
        assertEquals(plan, store.value.pending?.plan)
        assertEquals(existingRun, controller.state.value.run)
        assertEquals(existingRun, store.value.lastRun)
        assertNull(controller.state.value.error)
        // Configuration may be read again to validate the saved query's own
        // region. Recovery must only read, keep its ID, and never create a run.
        assertTrue(requests.contains("GET /api/v1/configs/v1"))
        assertEquals(listOf("GET /api/v1/recordings", "GET /api/v1/recordings",
          "GET /api/v1/agent/runs/run_existing"), requests.filterNot { it == "GET /api/v1/configs/v1" })
      } finally { scope.cancel() }
    }
  }

  @Test fun `stale mock selection does not discard a pending real recording execution during refresh`() = runBlocking {
    val requests = CopyOnWriteArrayList<String>()
    val query = traceQuery(TraceRecording("rec_1", "cam_1", "v1", 120000L, "prerecorded"), "E3")
    val plan = buildJsonObject {
      put("query", query); put("query_text", "真实录像的已确认问题")
      put("steps", JsonArray(listOf(JsonPrimitive("检索候选"), JsonPrimitive("回读证据"))))
    }
    val originalPending = TracePending("msg_real", "真实录像的原问题", query, plan, "run_real")
    val originalBody = buildJsonObject { put("run_id", "run_real"); put("input_message_id", "msg_real"); put("plan", plan) }
    val originalRun = run(originalBody, "conv_real", "failed")
    LocalApi { exchange ->
      requests.add("${exchange.requestMethod} ${exchange.requestURI.path}")
      when (exchange.requestURI.path) {
        "/api/v1/recordings" -> exchange.respond(200, envelope(buildJsonObject {
          put("recordings", JsonArray(listOf(mockRecording, recording)))
        }))
        "/api/v1/configs/v1" -> exchange.respond(200, config)
        else -> exchange.respond(404, error("run_not_found"))
      }
    }.use { server ->
      val store = MemoryStore(TraceAgentSnapshot(server.origin, "synthetic-viewer", "conv_real", "rec_mock_001", "E3",
        originalPending, originalRun))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val controller = TraceAgentController(scope, store)
        controller.refreshRecordings()
        withTimeout(5000) { controller.state.first { !it.busy && it.selectedRecordingId == "rec_1" } }
        assertEquals(listOf("rec_1"), controller.state.value.recordings.map { it.id })
        assertEquals("rec_1", store.value.recordingId)
        assertEquals("E3", store.value.zoneId)
        assertEquals("conv_real", store.value.conversationId)
        assertEquals("conv_real", controller.state.value.conversationId)
        assertEquals(originalPending, store.value.pending)
        assertEquals("msg_real", store.value.pending?.messageId)
        assertEquals("run_real", store.value.pending?.runId)
        assertEquals(query, store.value.pending?.query)
        assertEquals(plan, store.value.pending?.plan)
        assertEquals(originalRun, store.value.lastRun)
        assertNull(controller.state.value.error)
        assertEquals(listOf("GET /api/v1/recordings", "GET /api/v1/configs/v1"), requests.toList())
      } finally { scope.cancel() }
    }
  }

  @Test fun `mock only recordings discard persisted mock execution without posting a plan or run`() = runBlocking {
    val requests = CopyOnWriteArrayList<String>()
    val query = traceQuery(TraceRecording("rec_mock_001", "cam_1", "v1", 120000L, "mock"), "E3")
    val plan = buildJsonObject {
      put("query", query); put("query_text", "旧的模拟问题")
      put("steps", JsonArray(listOf(JsonPrimitive("检索候选"))))
    }
    val originalBody = buildJsonObject { put("run_id", "run_mock"); put("input_message_id", "msg_mock"); put("plan", plan) }
    val oldRun = run(originalBody, "conv_mock", "failed")
    LocalApi { exchange ->
      requests.add("${exchange.requestMethod} ${exchange.requestURI.path}")
      when (exchange.requestURI.path) {
        "/api/v1/recordings" -> exchange.respond(200, envelope(buildJsonObject {
          put("recordings", JsonArray(listOf(mockRecording)))
        }))
        else -> exchange.respond(404, error("run_not_found"))
      }
    }.use { server ->
      val store = MemoryStore(TraceAgentSnapshot(server.origin, "synthetic-viewer", "conv_mock", "rec_mock_001", "E3",
        TracePending("msg_mock", "旧的模拟问题", query, plan, "run_mock"), oldRun))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val controller = TraceAgentController(scope, store)
        controller.setActive(true)
        withTimeout(5000) { controller.state.first { !it.busy } }
        assertTrue(controller.state.value.recordings.isEmpty())
        assertNull(controller.state.value.selectedRecordingId)
        assertNull(controller.state.value.selectedZoneId)
        assertNull(controller.state.value.conversationId)
        assertNull(controller.state.value.plan)
        assertNull(controller.state.value.run)
        assertNull(store.value.recordingId)
        assertNull(store.value.zoneId)
        assertNull(store.value.conversationId)
        assertNull(store.value.pending)
        assertNull(store.value.lastRun)
        assertEquals(listOf("GET /api/v1/recordings"), requests.toList())

        controller.submit("重新检查录像")
        withTimeout(5000) { controller.state.first { !it.busy && it.error != null } }
        assertNull(store.value.pending)
        assertTrue("A mock-only account cannot create conversation, plan, or run history", requests.none { it.startsWith("POST ") })
        assertTrue("Removing a local mock pointer must not delete server history", requests.none { it.startsWith("DELETE ") })
      } finally { scope.cancel() }
    }
  }

  @Test fun `historical mock run remains readable but retry never posts a plan or run`() = runBlocking {
    val requests = CopyOnWriteArrayList<String>()
    val query = traceQuery(TraceRecording("rec_mock_001", "cam_1", "v1", 120000L, "mock"), "E3")
    val plan = buildJsonObject {
      put("query", query); put("query_text", "历史模拟问题")
      put("steps", JsonArray(listOf(JsonPrimitive("检索候选"))))
    }
    val originalBody = buildJsonObject { put("run_id", "run_mock"); put("input_message_id", "msg_mock"); put("plan", plan) }
    val oldRun = run(originalBody, "conv_mock", "failed")
    LocalApi { exchange ->
      requests.add("${exchange.requestMethod} ${exchange.requestURI.path}")
      when (exchange.requestURI.path) {
        "/api/v1/recordings" -> exchange.respond(200, envelope(buildJsonObject {
          put("recordings", JsonArray(listOf(mockRecording, recording)))
        }))
        "/api/v1/configs/v1" -> exchange.respond(200, config)
        "/api/v1/agent/conversations/conv_mock/messages" -> exchange.respond(200, envelope(buildJsonObject {
          put("messages", JsonArray(listOf(buildJsonObject {
            put("message_id", "msg_mock"); put("conversation_id", "conv_mock")
            put("role", "user"); put("content", "历史模拟问题"); put("run_id", "run_mock")
          })))
          put("next_cursor", JsonNull)
        }))
        "/api/v1/agent/runs/run_mock" -> exchange.respond(200, oldRun)
        else -> exchange.respond(404, error("run_not_found"))
      }
    }.use { server ->
      val store = MemoryStore(TraceAgentSnapshot(server.origin, "synthetic-viewer"))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val controller = TraceAgentController(scope, store)
        controller.refreshRecordings()
        withTimeout(5000) { controller.state.first { !it.busy && it.selectedRecordingId == "rec_1" } }
        controller.openConversation("conv_mock")
        withTimeout(5000) { controller.state.first { !it.busy && it.messages.isNotEmpty() } }
        controller.openRun("run_mock")
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "failed" } }
        assertEquals(oldRun, controller.state.value.run)
        assertNull(controller.state.value.error)
        assertTrue(requests.contains("GET /api/v1/agent/runs/run_mock"))

        controller.retry()
        withTimeout(5000) { controller.state.first { !it.busy && it.error != null } }
        assertTrue("History can be read, but its mock scope must not create another execution", requests.none { it.startsWith("POST ") })
        assertTrue("Existing mock history remains on the server", requests.none { it.startsWith("DELETE ") })
      } finally { scope.cancel() }
    }
  }

  @Test fun `active running mock history never polls over a new real recording plan`() = runBlocking {
    val mockReads = AtomicInteger()
    val query = traceQuery(TraceRecording("rec_mock_001", "cam_1", "v1", 120000L, "mock"), "E3")
    val mockPlan = buildJsonObject {
      put("query", query); put("query_text", "历史模拟问题")
      put("steps", JsonArray(listOf(JsonPrimitive("检索候选"))))
    }
    val originalBody = buildJsonObject { put("run_id", "run_mock"); put("input_message_id", "msg_mock"); put("plan", mockPlan) }
    val mockRun = run(originalBody, "conv_mock", "running")
    LocalApi { exchange ->
      when {
        exchange.requestURI.path == "/api/v1/recordings" -> exchange.respond(200, envelope(buildJsonObject {
          put("recordings", JsonArray(listOf(mockRecording, recording)))
        }))
        exchange.requestURI.path == "/api/v1/configs/v1" -> exchange.respond(200, config)
        exchange.requestURI.path == "/api/v1/agent/runs/run_mock" -> {
          mockReads.incrementAndGet(); exchange.respond(200, mockRun)
        }
        exchange.requestURI.path == "/api/v1/agent/conversations/conv_mock/messages" && exchange.requestMethod == "GET" ->
          exchange.respond(200, envelope(buildJsonObject { put("messages", JsonArray(emptyList())); put("next_cursor", JsonNull) }))
        exchange.requestURI.path == "/api/v1/agent/conversations" && exchange.requestMethod == "POST" -> {
          val body = exchange.jsonBody()
          exchange.respond(201, envelope(buildJsonObject { put("conversation", body) }))
        }
        exchange.requestURI.path == "/api/v1/agent/conversations/conv_mock/messages" && exchange.requestMethod == "POST" -> {
          val body = exchange.jsonBody()
          exchange.respond(201, envelope(buildJsonObject { put("message", buildJsonObject {
            body.forEach { (key, value) -> put(key, value) }
            put("conversation_id", "conv_mock"); put("role", "user"); put("run_id", JsonNull)
          }) }))
        }
        exchange.requestURI.path == "/api/v1/agent/plans" && exchange.requestMethod == "POST" -> {
          val body = exchange.jsonBody()
          exchange.respond(200, envelope(buildJsonObject {
            put("query", body.getValue("scope")); put("query_text", "新的真实录像计划")
            put("steps", JsonArray(listOf(JsonPrimitive("回读真实证据"))))
          }))
        }
        else -> exchange.respond(404, error("run_not_found"))
      }
    }.use { server ->
      val store = MemoryStore(TraceAgentSnapshot(server.origin, "synthetic-viewer"))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val controller = TraceAgentController(scope, store)
        controller.setActive(true)
        withTimeout(5000) { controller.state.first { !it.busy && it.selectedRecordingId == "rec_1" } }
        controller.openConversation("conv_mock")
        withTimeout(5000) { controller.state.first { !it.busy && it.conversationId == "conv_mock" } }
        controller.openRun("run_mock")
        withTimeout(5000) { controller.state.first { !it.busy && it.run?.string("run_id") == "run_mock" } }
        assertEquals(1, mockReads.get())

        controller.submit("查询真实录像")
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "plan_ready" } }
        val realPlan = controller.state.value.plan
        val realPending = store.value.pending
        assertEquals("rec_1", realPlan?.objectValue("query")?.string("recording_id"))
        assertNull(controller.state.value.run)
        // Wait beyond the production poll interval to catch an old history subscription.
        delay(2300)
        assertEquals("Read-only mock history must not keep an automatic poll alive", 1, mockReads.get())
        assertEquals("plan_ready", controller.state.value.phase)
        assertEquals(realPlan, controller.state.value.plan)
        assertEquals(realPending, store.value.pending)
        assertNull(controller.state.value.run)
        assertNull(controller.state.value.error)
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

  @Test fun `stop rereads server tool cancellation progress after a backward compatible stop response`() = runBlocking {
    val query = traceQuery(TraceRecording("rec_1", "cam_1", "v1", 120000L, "prerecorded"), "E3")
    val plan = buildJsonObject {
      put("query", query); put("query_text", "回读真实事件")
      put("steps", JsonArray(listOf(JsonPrimitive("读取事件证据"))))
    }
    val originalBody = buildJsonObject { put("run_id", "run_progress"); put("input_message_id", "msg_progress"); put("plan", plan) }
    fun progress(status: String) = JsonArray(listOf(buildJsonObject {
      put("call_id", "tool_0123456789abcdef0123456789abcdef"); put("tool", "get_event"); put("status", status)
      put("sequence", 1); put("started_at", "2026-10-17T00:00:00.100Z")
      put("finished_at", if (status == "running") JsonNull else JsonPrimitive("2026-10-17T00:00:02.300Z"))
    }))
    val runningProgress = progress("running")
    val cancelledProgress = progress("cancelled")
    val running = JsonObject(run(originalBody, "conv_progress") + ("progress" to runningProgress))
    // Older stop responses can omit telemetry. Only the following GET owns it.
    val stopped = JsonObject(run(originalBody, "conv_progress", "stopped") + ("finished_at" to JsonPrimitive("2026-10-17T00:00:02.300Z")))
    val stoppedWithProgress = JsonObject(stopped + ("progress" to cancelledProgress))
    val reads = AtomicInteger()
    val stops = AtomicInteger()
    val requests = CopyOnWriteArrayList<String>()
    lateinit var store: MemoryStore
    LocalApi { exchange ->
      assertEquals("Bearer synthetic-viewer", exchange.requestHeaders.getFirst("Authorization"))
      requests.add("${exchange.requestMethod} ${exchange.requestURI.path}")
      when (exchange.requestURI.path) {
        "/api/v1/recordings" -> exchange.respond(200, envelope(buildJsonObject { put("recordings", JsonArray(listOf(recording))) }))
        "/api/v1/configs/v1" -> exchange.respond(200, config)
        "/api/v1/agent/runs/run_progress" -> {
          assertEquals("GET", exchange.requestMethod)
          reads.incrementAndGet()
          if (stops.get() == 0) exchange.respond(200, running)
          else {
            assertEquals("The POST terminal result must be committed before telemetry is reread", "stopped", store.value.lastRun?.string("status"))
            assertNull("The client must not infer tool cancellation from the run status", store.value.lastRun?.get("progress"))
            exchange.respond(200, stoppedWithProgress)
          }
        }
        "/api/v1/agent/runs/run_progress/stop" -> {
          assertEquals("POST", exchange.requestMethod)
          assertEquals(buildJsonObject {}, exchange.jsonBody())
          stops.incrementAndGet()
          exchange.respond(200, stopped)
        }
        else -> exchange.respond(404, error("run_not_found"))
      }
    }.use { server ->
      val originalPending = TracePending("msg_progress", "回读真实事件", query, plan, "run_progress")
      store = MemoryStore(TraceAgentSnapshot(server.origin, "synthetic-viewer", "conv_progress", "rec_1", "E3", originalPending))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val controller = TraceAgentController(scope, store)
        controller.setActive(true)
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "running" } }
        assertEquals(runningProgress, controller.state.value.run?.get("progress"))
        controller.stop()
        withTimeout(5000) { controller.state.first { !it.busy && it.phase == "stopped" && it.run?.get("progress") == cancelledProgress } }
        assertEquals(stoppedWithProgress, controller.state.value.run)
        assertEquals(cancelledProgress, store.value.lastRun?.get("progress"))
        assertEquals("stopped", store.value.lastRun?.string("status"))
        assertEquals(originalPending, store.value.pending)
        assertEquals(2, reads.get())
        assertEquals(1, stops.get())
        assertEquals(listOf("POST /api/v1/agent/runs/run_progress/stop"), requests.filter { it.startsWith("POST ") })
        assertNull(controller.state.value.error)
      } finally { scope.cancel() }
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
