package com.anker.esiotkit.sample.main.station.agent

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TraceAgentContractTest {
  private val recording = TraceRecording("rec_1", "camera_1", "config_1", 9_000_000_000L, "prerecorded")
  private val query = traceQuery(recording, "E3")

  @Test fun `scope uses backend identities and Long milliseconds and excludes envelope from plan`() {
    assertEquals(9_000_000_000L, query.getValue("end_offset_ms").jsonPrimitive.long)
    assertEquals("camera_1", query.string("camera_id"))
    val response = buildJsonObject {
      put("api_version", "0.3-mvp-draft"); put("query", query)
      put("query_text", "移动的包裹"); put("steps", JsonArray(listOf(JsonPrimitive("核对关键帧"))))
    }
    val plan = tracePlan(response, query)
    assertEquals(setOf("query", "query_text", "steps"), plan.keys)
    val changedScope = buildJsonObject { query.forEach { (key, value) -> put(key, value) }; put("zone_id", "OTHER") }
    assertThrows(TraceApiFailure::class.java) { tracePlan(response, changedScope) }
  }

  @Test fun `stable IDs and confirmed payload survive serialization and changed retry input`() {
    val question = beginTraceQuestion(TraceAgentSnapshot(), "  有哪些候选？  ", query)
    val restored = traceJson.decodeFromString<TraceAgentSnapshot>(traceJson.encodeToString(question))
    assertEquals(question, restored)
    assertEquals("有哪些候选？", restored.pending?.question)
    val confirmed = confirmTracePlan(restored, "查找移动", listOf(" 检索 ", "回读证据"))
    val allocated = allocateTraceRun(confirmed)
    assertEquals(allocated, allocateTraceRun(allocated))
    assertEquals(allocated, confirmTracePlan(allocated, "不同的计划", listOf("不同步骤")))
    val newExecution = allocateTraceRun(allocated, newExecution = true)
    assertNotEquals(allocated.pending?.runId, newExecution.pending?.runId)
    assertEquals(allocated.pending?.messageId, newExecution.pending?.messageId)
    assertEquals(allocated.pending?.plan, newExecution.pending?.plan)
    assertThrows(TraceApiFailure::class.java) { confirmTracePlan(restored, " ", listOf("检索")) }
    assertThrows(TraceApiFailure::class.java) { confirmTracePlan(restored, "检索", listOf(" ")) }
  }

  @Test fun `run parser rejects wrong owner scope and run identity and accepts older server without cards`() {
    val plan = confirmTracePlan(beginTraceQuestion(TraceAgentSnapshot(), "检索", query), "检索", listOf("核对")).pending!!.plan!!
    val run = buildJsonObject {
      put("run_id", "run_1"); put("conversation_id", "conv_1"); put("status", "running")
      put("input_message_id", "msg_1")
      put("plan", plan); put("summary", JsonNull); put("references", JsonArray(emptyList()))
    }
    assertEquals(run, validateTraceRun(run, "run_1", "conv_1"))
    assertThrows(TraceApiFailure::class.java) { validateTraceRun(run, "run_2", "conv_1") }
    assertThrows(TraceApiFailure::class.java) { validateTraceRun(run, "run_1", "conv_2") }
    val wrongCards = buildJsonObject {
      run.forEach { (key, value) -> put(key, value) }
      put("cards", buildJsonObject { put("run_id", "run_1"); put("query", buildJsonObject { put("recording_id", "outside") }) })
    }
    assertThrows(TraceApiFailure::class.java) { validateTraceRun(wrongCards, "run_1", "conv_1") }
  }

  @Test fun `credential URL policy excludes foreign HTTP userinfo API prefixes and query tokens`() {
    assertEquals("https://api.example.invalid", normalizeTraceBaseUrl(" https://api.example.invalid/ "))
    assertEquals("http://10.0.2.2:8000", normalizeTraceBaseUrl("http://10.0.2.2:8000"))
    listOf("http://192.168.0.2:8000", "https://user:secret@example.invalid", "https://example.invalid/api/v1",
      "https://example.invalid?token=secret", "https://example.invalid#part", "file:///tmp/api", "https://example.invalid:0")
      .forEach { assertNull(it, normalizeTraceBaseUrl(it)) }
  }

  @Test fun `optional actual tool progress accepts legacy runs but rejects invented or repeated calls`() {
    val plan = confirmTracePlan(beginTraceQuestion(TraceAgentSnapshot(), "检索", query), "检索", listOf("核对")).pending!!.plan!!
    val run = buildJsonObject {
      put("run_id", "run_1"); put("conversation_id", "conv_1"); put("status", "running")
      put("input_message_id", "msg_1"); put("plan", plan); put("summary", JsonNull); put("references", JsonArray(emptyList()))
    }
    assertEquals(run, validateTraceRun(run, "run_1"))
    val call = buildJsonObject {
      put("call_id", "tool_0123456789abcdef0123456789abcdef"); put("tool", "search_events"); put("status", "completed")
      put("sequence", 1); put("started_at", "2026-10-17T00:00:00.000Z"); put("finished_at", "2026-10-17T00:00:00.200Z")
    }
    val valid = JsonObject(run + ("progress" to JsonArray(listOf(call))))
    assertEquals(valid, validateTraceRun(valid, "run_1"))
    listOf(
      JsonArray(listOf(call, call)),
      JsonArray(listOf(JsonObject(call + ("tool" to JsonPrimitive("arbitrary_tool"))))),
      JsonArray(listOf(JsonObject(call + ("status" to JsonPrimitive("thought_completed"))))),
      JsonArray(listOf(JsonObject(call + ("finished_at" to JsonNull)))),
      JsonArray(List(65) { call }),
    ).forEach { invalid ->
      assertThrows(TraceApiFailure::class.java) { validateTraceRun(JsonObject(run + ("progress" to invalid)), "run_1") }
    }
  }
}
