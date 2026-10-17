package com.anker.esiotkit.sample.main.station.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TraceAgentEntryTest {
  private class MemoryStore(
    @Volatile var value: TraceAgentSnapshot,
    private val readStarted: CountDownLatch? = null,
    private val releaseRead: CountDownLatch? = null,
    private val beforeWrite: (TraceAgentSnapshot) -> Unit = {}
  ) : TraceAgentStore {
    val writes = CopyOnWriteArrayList<TraceAgentSnapshot>()
    override fun read(): TraceAgentSnapshot {
      readStarted?.countDown()
      check(releaseRead?.await(5, TimeUnit.SECONDS) != false) { "Timed out releasing the local snapshot read" }
      return value
    }
    override fun write(value: TraceAgentSnapshot) {
      beforeWrite(value)
      this.value = value
      writes.add(value)
    }
  }

  private data class Request(val method: String, val path: String, val query: String?)

  private class LocalApi(handler: (HttpExchange) -> Unit) : AutoCloseable {
    val requests = CopyOnWriteArrayList<Request>()
    val authorizations = CopyOnWriteArrayList<String>()
    private val failures = CopyOnWriteArrayList<Throwable>()
    // A gated old response must not prevent a replacement activation from using the API.
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "trace-entry-api").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      this.executor = this@LocalApi.executor
      createContext("/") { exchange ->
        requests.add(Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.rawQuery))
        authorizations.add(exchange.requestHeaders.getFirst("Authorization").orEmpty())
        try { handler(exchange) }
        catch (_: IOException) { exchange.close() } // Cancelling the request can close its response socket.
        catch (failure: Throwable) {
          failures.add(failure)
          exchange.close()
        }
      }
      start()
    }
    val origin = "http://127.0.0.1:${server.address.port}"
    fun assertHealthy() {
      assertTrue("HTTP handler failures: ${failures.map { it.toString() }}", failures.isEmpty())
      assertTrue(authorizations.all { it == "Bearer synthetic-entry-viewer" })
    }
    override fun close() { server.stop(0); executor.shutdownNow() }
  }

  private val query = traceQuery(TraceRecording("rec_saved", "cam_saved", "v1", 120000L, "prerecorded"), "E3")
  private val plan = buildJsonObject {
    put("query", query); put("query_text", "上次确认的问题")
    put("steps", JsonArray(listOf(JsonPrimitive("读取已有录像"))))
  }
  private val pending = TracePending("msg_saved", "上次的问题", query, plan, "run_saved")

  private fun envelope(body: JsonObject) = buildJsonObject {
    put("api_version", "0.3-mvp-draft")
    body.forEach { (key, value) -> put(key, value) }
  }

  private fun HttpExchange.respond(body: JsonObject) {
    val bytes = body.toString().toByteArray(Charsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
  }

  private fun HttpExchange.respondMetadata(): Boolean = when (requestURI.path) {
    "/api/v1/recordings" -> {
      respond(envelope(buildJsonObject { put("recordings", JsonArray(listOf("rec_other", "rec_saved").map { id ->
        buildJsonObject {
          put("recording_id", id); put("camera_id", "cam_saved"); put("config_version", "v1")
          put("duration_ms", 120000L); put("source_mode", "prerecorded"); put("publication_status", "published")
        }
      })) }))
      true
    }
    "/api/v1/configs/v1" -> {
      respond(envelope(buildJsonObject { put("config", buildJsonObject {
        put("config_version", "v1"); put("cameras", JsonArray(listOf(buildJsonObject {
          put("camera_id", "cam_saved"); put("zones", JsonArray(listOf("E1", "E3").map { zone ->
            buildJsonObject { put("zone_id", zone) }
          }))
        })))
      }) }))
      true
    }
    else -> false
  }

  private fun savedRun(status: String) = envelope(buildJsonObject {
    put("run_id", "run_saved"); put("input_message_id", "msg_saved"); put("conversation_id", "conv_saved")
    put("status", status); put("plan", plan)
    put("summary", if (status == "completed") JsonPrimitive("之前的分析结果") else JsonNull)
    put("references", JsonArray(emptyList())); put("error", JsonNull)
    put("created_at", "2026-10-17T00:00:00Z")
    put("finished_at", if (status == "running") JsonNull else JsonPrimitive("2026-10-17T00:00:01Z"))
  })

  private fun messages(nextCursor: String? = null) = envelope(buildJsonObject {
    put("messages", JsonArray(listOf(buildJsonObject {
      put("message_id", "msg_saved"); put("conversation_id", "conv_saved"); put("role", "user")
      put("content", "上次的问题"); put("run_id", JsonNull)
    }, buildJsonObject {
      put("message_id", "msg_answer"); put("conversation_id", "conv_saved"); put("role", "assistant")
      put("content", "之前的分析结果"); put("run_id", "run_saved")
    })))
    put("next_cursor", nextCursor?.let(::JsonPrimitive) ?: JsonNull)
  })

  private fun conversations() = envelope(buildJsonObject {
    put("conversations", JsonArray(listOf(buildJsonObject { put("conversation_id", "conv_saved"); put("title", "上次的问题") })))
    put("next_cursor", "older_conversations")
  })

  private fun snapshot(origin: String, lastRun: JsonObject? = null, pending: TracePending? = null) =
    TraceAgentSnapshot(origin, "synthetic-entry-viewer", "conv_saved", "rec_saved", "E3", pending, lastRun)

  private suspend fun CountDownLatch.awaitSignal() = withContext(Dispatchers.IO) {
    assertTrue("Timed out awaiting a controlled test signal", await(5, TimeUnit.SECONDS))
  }

  private suspend fun withController(
    store: MemoryStore,
    test: suspend (TraceAgentController, CoroutineDispatcher) -> Unit
  ) {
    val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "trace-entry-controller").apply { isDaemon = true } }
      .asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    try { test(TraceAgentController(scope, store), dispatcher) }
    finally { scope.cancel(); dispatcher.close() }
  }

  private suspend fun TraceAgentController.awaitIdle(predicate: (TraceAgentState) -> Boolean = { true }) =
    withTimeout(5000) { state.first { !it.busy && predicate(it) } }

  private fun assertFresh(controller: TraceAgentController, store: MemoryStore, origin: String) {
    val state = controller.state.value
    assertTrue(state.configured)
    assertEquals(origin, state.baseUrl)
    assertEquals("rec_saved", state.selectedRecordingId)
    assertEquals("E3", state.selectedZoneId)
    assertEquals("idle", state.phase)
    assertNull(state.error)
    assertNull(state.conversationId)
    assertNull(state.plan)
    assertNull(state.run)
    assertTrue(state.messages.isEmpty())
    assertTrue(state.conversations.isEmpty())
    assertFalse(state.hasMoreMessages)
    assertFalse(state.hasMoreConversations)
    assertNull(state.evidence)
    assertNull(state.evidenceBytes)
    assertEquals(origin, store.value.baseUrl)
    assertEquals("synthetic-entry-viewer", store.value.bearerToken)
    assertEquals("rec_saved", store.value.recordingId)
    assertEquals("E3", store.value.zoneId)
    assertNull(store.value.conversationId)
    assertNull(store.value.pending)
    assertNull(store.value.lastRun)
  }

  @Test fun `fresh entry wins over concurrent initialization without reading completed history`() = runBlocking {
    val readStarted = CountDownLatch(1)
    val releaseRead = CountDownLatch(1)
    LocalApi { exchange ->
      if (!exchange.respondMetadata()) error("A fresh entry must not read the previous conversation or run")
    }.use { api ->
      val store = MemoryStore(snapshot(api.origin, savedRun("completed"), pending), readStarted, releaseRead)
      try {
        withController(store) { controller, dispatcher ->
          withContext(dispatcher) { controller.initialize() }
          readStarted.awaitSignal()
          withContext(dispatcher) {
            controller.requestNewConversationOnEntry()
            controller.setActive(true)
          }
          releaseRead.countDown()
          controller.awaitIdle { it.configured && it.selectedZoneId == "E3" }
          assertFresh(controller, store, api.origin)
          assertTrue(api.requests.none { it.path.startsWith("/api/v1/agent/") })
          assertTrue(api.requests.all { it.method == "GET" })
          api.assertHealthy()
        }
      } finally { releaseRead.countDown() }
    }
  }

  @Test fun `fresh entry cancels an old message read and ignores its late response`() = runBlocking {
    val messageStarted = CountDownLatch(1)
    val releaseMessage = CountDownLatch(1)
    val lateResponseFinished = CountDownLatch(1)
    LocalApi { exchange ->
      if (!exchange.respondMetadata()) {
        check(exchange.requestURI.path == "/api/v1/agent/conversations/conv_saved/messages")
        messageStarted.countDown()
        check(releaseMessage.await(5, TimeUnit.SECONDS))
        try { exchange.respond(messages("older_messages")) }
        finally { lateResponseFinished.countDown() }
      }
    }.use { api ->
      val store = MemoryStore(snapshot(api.origin))
      try {
        withController(store) { controller, dispatcher ->
          withContext(dispatcher) { controller.setActive(true) }
          messageStarted.awaitSignal()
          withContext(dispatcher) {
            controller.requestNewConversationOnEntry()
            controller.setActive(true)
          }
          controller.awaitIdle { it.conversationId == null }
          assertFresh(controller, store, api.origin)
          releaseMessage.countDown()
          lateResponseFinished.awaitSignal()
          // Let the HTTP callback, if any, reach the controller's serialized dispatcher.
          delay(100)
          withContext(dispatcher) { assertFresh(controller, store, api.origin) }
          assertEquals(1, api.requests.count { it.path.endsWith("/messages") })
          assertTrue(api.requests.all { it.method == "GET" })
          api.assertHealthy()
        }
      } finally { releaseMessage.countDown() }
    }
  }

  @Test fun `fresh entry waits for a deactivated noncancellable write before clearing durable history`() = runBlocking {
    val writeStarted = CountDownLatch(1)
    val releaseWrite = CountDownLatch(1)
    val writeAttempts = AtomicInteger()
    LocalApi { exchange ->
      if (!exchange.respondMetadata()) error("The cancelled activation must not continue into old history")
    }.use { api ->
      val store = MemoryStore(snapshot(api.origin), beforeWrite = { value ->
        if (writeAttempts.incrementAndGet() == 1) {
          // Metadata activation saves the selected scope inside NonCancellable.
          // Hold that old conversation write across deactivate and the new entry.
          check(value.conversationId == "conv_saved")
          writeStarted.countDown()
          check(releaseWrite.await(5, TimeUnit.SECONDS))
        }
      })
      try {
        withController(store) { controller, dispatcher ->
          withContext(dispatcher) { controller.setActive(true) }
          writeStarted.awaitSignal()
          withContext(dispatcher) {
            controller.setActive(false)
            controller.requestNewConversationOnEntry()
            controller.setActive(true)
          }
          // This dispatcher barrier lets the replacement activation reach its
          // suspension point while the previous write is still held by the latch.
          withContext(dispatcher) {
            assertTrue("The replacement activation must wait for the cancelled durable write", controller.state.value.busy)
            assertTrue(store.writes.isEmpty())
            assertEquals("conv_saved", store.value.conversationId)
          }
          releaseWrite.countDown()
          controller.awaitIdle { it.conversationId == null }
          assertFresh(controller, store, api.origin)
          assertEquals("The old write must finish before the fresh conversation is committed",
            listOf("conv_saved", null), store.writes.map { it.conversationId })
          assertEquals(2, writeAttempts.get())
          assertTrue(api.requests.none { it.path.startsWith("/api/v1/agent/") })
          assertTrue(api.requests.all { it.method == "GET" })
          api.assertHealthy()
        }
      } finally { releaseWrite.countDown() }
    }
  }

  @Test fun `fresh entry clears loaded history pagination and evidence but explicit history still opens`() = runBlocking {
    LocalApi { exchange ->
      if (!exchange.respondMetadata()) when (exchange.requestURI.path) {
        "/api/v1/agent/runs/run_saved" -> exchange.respond(savedRun("completed"))
        "/api/v1/agent/conversations/conv_saved/messages" -> exchange.respond(messages("older_messages"))
        "/api/v1/agent/conversations" -> exchange.respond(conversations())
        "/api/v1/evidence/evd_saved" -> exchange.respond(envelope(buildJsonObject {
          put("evidence", buildJsonObject { put("evidence_id", "evd_saved"); put("media_status", "missing") })
        }))
        else -> error("Unexpected API path ${exchange.requestURI.path}")
      }
    }.use { api ->
      val store = MemoryStore(snapshot(api.origin, savedRun("completed"), pending))
      withController(store) { controller, dispatcher ->
        withContext(dispatcher) { controller.setActive(true) }
        controller.awaitIdle { it.phase == "completed" && it.hasMoreMessages }
        withContext(dispatcher) { controller.loadHistory() }
        controller.awaitIdle { it.hasMoreConversations }
        withContext(dispatcher) { controller.openEvidence("evd_saved") }
        withTimeout(5000) { controller.state.first { it.evidence != null } }
        withContext(dispatcher) {
          controller.requestNewConversationOnEntry()
          controller.setActive(true)
        }
        controller.awaitIdle { it.conversationId == null }
        assertFresh(controller, store, api.origin)
        val beforePagination = api.requests.toList()
        withContext(dispatcher) { controller.loadMoreMessages() }
        controller.awaitIdle()
        withContext(dispatcher) { controller.loadMoreConversations() }
        controller.awaitIdle()
        assertEquals("A new conversation cannot reuse either old pagination cursor", beforePagination, api.requests.toList())

        withContext(dispatcher) { controller.loadHistory() }
        controller.awaitIdle { it.conversations.isNotEmpty() }
        withContext(dispatcher) { controller.openConversation("conv_saved") }
        controller.awaitIdle { it.messages.size == 2 }
        assertEquals("conv_saved", controller.state.value.conversationId)
        assertEquals("conv_saved", store.value.conversationId)
        assertEquals("之前的分析结果", controller.state.value.messages.last().string("content"))
        assertTrue(api.requests.all { it.method == "GET" })
        api.assertHealthy()
      }
    }
  }

  @Test fun `fresh entry preserves the stable ID of a running or uncertain real execution`() = runBlocking {
    // No cached result means its start may already have reached the server. It is not a disposable draft.
    for (cachedStatus in listOf("running", null)) {
      LocalApi { exchange ->
        if (!exchange.respondMetadata()) {
          check(exchange.requestURI.path == "/api/v1/agent/runs/run_saved")
          exchange.respond(savedRun("running"))
        }
      }.use { api ->
        val store = MemoryStore(snapshot(api.origin, cachedStatus?.let(::savedRun), pending))
        withController(store) { controller, dispatcher ->
          withContext(dispatcher) {
            controller.requestNewConversationOnEntry()
            controller.setActive(true)
          }
          controller.awaitIdle { it.phase == "running" }
          assertEquals("conv_saved", controller.state.value.conversationId)
          assertEquals("run_saved", controller.state.value.run?.string("run_id"))
          assertEquals(pending, store.value.pending)
          assertEquals("conv_saved", store.value.conversationId)
          assertEquals(plan, controller.state.value.plan)
          assertEquals("rec_saved", controller.state.value.selectedRecordingId)
          assertEquals("E3", controller.state.value.selectedZoneId)
          assertNull(controller.state.value.error)
          assertTrue("Entering AI must not stop or recreate an unfinished execution", api.requests.all { it.method == "GET" })
          assertTrue(api.requests.any { it.path == "/api/v1/agent/runs/run_saved" })
          api.assertHealthy()
        }
      }
    }
  }

  @Test fun `ordinary lifecycle activation restores the current conversation without creating a fresh one`() = runBlocking {
    val messageReads = AtomicInteger()
    LocalApi { exchange ->
      if (!exchange.respondMetadata()) {
        check(exchange.requestURI.path == "/api/v1/agent/conversations/conv_saved/messages")
        messageReads.incrementAndGet()
        exchange.respond(messages())
      }
    }.use { api ->
      val store = MemoryStore(snapshot(api.origin))
      withController(store) { controller, dispatcher ->
        withContext(dispatcher) { controller.setActive(true) }
        controller.awaitIdle { it.messages.size == 2 }
        withContext(dispatcher) { controller.setActive(false); controller.setActive(true) }
        controller.awaitIdle { it.messages.size == 2 }
        assertEquals(2, messageReads.get())
        assertEquals("conv_saved", controller.state.value.conversationId)
        assertEquals("conv_saved", store.value.conversationId)
        assertNull(controller.state.value.error)
        assertTrue(api.requests.all { it.method == "GET" })
        api.assertHealthy()
      }
    }
  }
}
