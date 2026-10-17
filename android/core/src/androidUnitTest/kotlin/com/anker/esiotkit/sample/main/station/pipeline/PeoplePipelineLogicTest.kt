package com.anker.esiotkit.sample.main.station.pipeline

import com.anker.esiotkit.core.entities.event.DetectionType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeoplePipelineLogicTest {
  @Test
  fun `the same camera event keeps one stable identifier across restarts`() {
    val original = peopleJobId("camera-a", "event-42")
    assertEquals(original, peopleJobId("camera-a", "event-42"))
    assertTrue(original.matches(Regex("[0-9a-f]{64}")))
    assertNotEquals(original, peopleJobId("camera-b", "event-42"))
    assertNotEquals(peopleJobId("ab", "c"), peopleJobId("a", "bc"))
  }

  @Test
  fun `FACE and HUMAN each qualify alone or together as person recordings`() {
    assertEquals(1, DetectionType.FACE.value)
    assertEquals(2, DetectionType.HUMAN.value)
    assertEquals(listOf(1, 2), PeopleDetectionTypes)
    assertTrue(isSdkPersonDetection(listOf(1)))
    assertTrue(isSdkPersonDetection(listOf(2)))
    assertTrue(isSdkPersonDetection(listOf(1, 2)))
    assertTrue(isSdkPersonDetection(listOf(0, null, 1, 4)))
    assertTrue(isSdkPersonDetection(listOf(0, 2, null)))
  }

  @Test
  fun `motion pets vehicles and other non-person detections remain excluded`() {
    DetectionType.entries.filter { it != DetectionType.FACE && it != DetectionType.HUMAN }.forEach {
      assertFalse(isSdkPersonDetection(listOf(it.value)), it.name)
    }
    assertFalse(isSdkPersonDetection(listOf(0, null, 4, 999)))
    assertFalse(isSdkPersonDetection(listOf(null)))
    assertFalse(isSdkPersonDetection(emptyList()))
  }

  @Test
  fun `enabling excludes historical events but retains an event from the first polling interval`() {
    for (detection in listOf(1, 2)) {
      assertFalse(canDiscoverPeopleEvent(999, 1000, listOf(detection)))
      assertTrue(canDiscoverPeopleEvent(1000, 1000, listOf(detection)))
      assertTrue(canDiscoverPeopleEvent(1001, 1000, listOf(detection)))
    }
    assertFalse(canDiscoverPeopleEvent(1001, 1000, listOf(0)))
  }

  @Test
  fun `service URLs permit TLS and emulator loopback without accepting remote cleartext or embedded credentials`() {
    assertEquals("https://backend.example", normalizePeopleBaseUrl(" https://backend.example/ "))
    assertEquals("http://10.0.2.2:8000", normalizePeopleBaseUrl("http://10.0.2.2:8000"))
    assertEquals("http://127.0.0.1:8000", normalizePeopleBaseUrl("http://127.0.0.1:8000/"))
    listOf("http://backend.example", "http://10.0.2.2.example", "https://name:secret@backend.example",
      "https://backend.example/api/v1", "https://backend.example?token=value", "file:///tmp/server").forEach {
      assertNull(normalizePeopleBaseUrl(it), it)
    }
  }

  @Test
  fun `durable snapshot retains the artifact and idempotency metadata after process death`() {
    val task = task().copy(status = "uploading", localPath = "/private/task.mp4", durationMs = 4321)
    val serialized = Json.encodeToString(PeopleSnapshot(tasks = listOf(task)))
    val restored = Json.decodeFromString<PeopleSnapshot>(serialized).tasks.single()
    assertEquals(task.jobId, restored.jobId)
    assertEquals("/private/task.mp4", recoverPeopleTask(restored).localPath)
    assertEquals(4321L, restored.durationMs)
    assertEquals("ready", recoverPeopleTask(restored).status)
    assertEquals("discovered", recoverPeopleTask(task().copy(status = "downloading")).status)
  }

  @Test
  fun `retry after uncertain upload preserves job and bytes while terminal Vision failure stays final`() {
    val uncertainUpload = task().copy(status = "failed", localPath = "/private/task.mp4", durationMs = 4321, attempts = 5)
    val retried = retryPeopleTask(uncertainUpload)
    assertEquals("ready", retried.status)
    assertEquals(uncertainUpload.jobId, retried.jobId)
    assertEquals(uncertainUpload.localPath, retried.localPath)
    assertEquals(0, retried.attempts)
    val remoteFailure = task().copy(status = "failed", remoteAccepted = true, retryable = false)
    assertEquals(remoteFailure, retryPeopleTask(remoteFailure))
    val lostStatus = uncertainUpload.copy(remoteAccepted = true)
    assertEquals("queued", retryPeopleTask(lostStatus).status)
  }

  @Test
  fun `adding another camera or rotating token does not redirect existing tasks`() {
    val original = PeopleSettings("https://backend.example", "test-only", "config-v1", mapOf("a" to "entrance"))
    val added = original.copy(bearerToken = "test-rotated", cameraMapping = mapOf("a" to "entrance", "b" to "shelves"))
    assertEquals(original.targetKey("a"), added.targetKey("a"))
    assertNotEquals(original.targetKey("a"), original.copy(baseUrl = "https://other.example").targetKey("a"))
    assertNotEquals(original.targetKey("a"), original.copy(cameraMapping = mapOf("a" to "other")).targetKey("a"))
  }

  @Test
  fun `network retry delay is bounded and never forms a tight loop`() {
    assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L), (1..5).map(::peopleRetryDelay))
    assertEquals(120_000L, peopleRetryDelay(100))
  }

  @Test
  fun `accepted jobs survive a long status outage and keep their last known server state`() {
    for (serverStatus in listOf("queued", "processing")) {
      var pending = task().copy(status = serverStatus, remoteAccepted = true, retryable = false,
        localPath = "/private/accepted.mp4", durationMs = 4321)
      repeat(20) {
        pending = deferPeopleTaskAfterFailure(pending, "status unavailable", 10_000)
        assertEquals(serverStatus, pending.status)
        assertTrue(shouldReadPeopleRemoteStatus(pending))
        assertTrue(pending.nextAttemptMs in 15_000L..130_000L)
      }
      assertEquals(5, pending.attempts)
      assertEquals(130_000L, pending.nextAttemptMs)
      assertEquals(peopleJobId("camera-a", "event-42"), pending.jobId)
      assertEquals("/private/accepted.mp4", pending.localPath)
      assertEquals(4321L, pending.durationMs)
      assertFalse(pending.completionDelivered)
    }
  }

  @Test
  fun `restart resumes legacy accepted network failures without reviving a terminal Vision failure`() {
    val lostStatus = task().copy(status = "failed", remoteAccepted = true, retryable = true, attempts = 5,
      nextAttemptMs = 500_000, error = "status unavailable", localPath = "/private/accepted.mp4", durationMs = 4321)
    val restored = Json.decodeFromString<PeopleSnapshot>(Json.encodeToString(PeopleSnapshot(tasks = listOf(lostStatus))))
    val recovered = recoverPeopleTask(restored.tasks.single())
    assertEquals("queued", recovered.status)
    assertTrue(recovered.remoteAccepted)
    assertTrue(shouldReadPeopleRemoteStatus(recovered))
    assertEquals(0, recovered.attempts)
    assertEquals(0L, recovered.nextAttemptMs)
    assertNull(recovered.error)
    assertEquals(lostStatus.jobId, recovered.jobId)
    assertEquals(lostStatus.localPath, recovered.localPath)
    assertFalse(recovered.completionDelivered)

    val visionFailed = lostStatus.copy(retryable = false)
    assertEquals(visionFailed, recoverPeopleTask(visionFailed))
    assertEquals(visionFailed, deferPeopleTaskAfterFailure(visionFailed, "status unavailable", 10_000))
    assertFalse(shouldReadPeopleRemoteStatus(visionFailed))
    val completed = lostStatus.copy(status = "completed", retryable = false, completionDelivered = true)
    assertEquals(completed, recoverPeopleTask(completed))
    assertEquals(completed, deferPeopleTaskAfterFailure(completed, "status unavailable", 10_000))
    assertFalse(shouldReadPeopleRemoteStatus(completed))
  }

  @Test
  fun `unaccepted preparation or upload still stops at the existing five attempt limit`() {
    val fourth = deferPeopleTaskAfterFailure(task().copy(attempts = 3), "upload unavailable", 10_000)
    assertEquals("retry_wait", fourth.status)
    val fifth = deferPeopleTaskAfterFailure(fourth, "upload unavailable", 10_000)
    assertEquals("failed", fifth.status)
    assertEquals(5, fifth.attempts)
    assertFalse(fifth.remoteAccepted)
    assertFalse(shouldReadPeopleRemoteStatus(fifth))
    assertEquals(fifth, recoverPeopleTask(fifth))
  }

  @Test
  fun `explicit rejected source redownload retains identity and never replaces accepted or conflicting bytes`() {
    val rejected = task().copy(status = "failed", retryable = false, localPath = "/private/rejected.mp4",
      durationMs = 29199, error = RejectedPeopleSourceMessage, origin = "automatic")
    assertTrue(canRedownloadPeopleTask(rejected))
    val retried = retryPeopleTask(rejected)
    assertEquals("discovered", retried.status)
    assertEquals(rejected.jobId, retried.jobId)
    assertEquals(rejected.sdkEventId, retried.sdkEventId)
    assertEquals(rejected.eventJson, retried.eventJson)
    assertEquals(rejected.targetKey, retried.targetKey)
    assertEquals(rejected.origin, retried.origin)
    assertNull(retried.localPath)
    assertEquals(0L, retried.durationMs)
    assertFalse(retried.remoteAccepted)
    assertFalse(retried.completionDelivered)
    assertTrue(retried.retryable)
    for (protected in listOf(rejected.copy(remoteAccepted = true),
      rejected.copy(status = "completed", completionDelivered = true),
      rejected.copy(error = "任务标识与已提交内容冲突。"))) {
      assertFalse(canRedownloadPeopleTask(protected))
      assertEquals(protected, retryPeopleTask(protected))
    }
  }

  private fun task() = PeopleTask(jobId = peopleJobId("camera-a", "event-42"), sdkEventId = "event-42",
    deviceSn = "camera-a", cameraId = "entrance", configVersion = "config-v1", targetKey = "target",
    eventJson = "{}", createdAtMs = 1000)
}
