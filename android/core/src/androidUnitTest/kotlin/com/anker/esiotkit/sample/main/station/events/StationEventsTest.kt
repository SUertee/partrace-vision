package com.anker.esiotkit.sample.main.station.events

import com.anker.esiotkit.core.entities.event.DetectionType
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.domain.model.MegaPicture
import com.anker.esiotkit.event.model.EventCondition
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.*
import org.junit.Test

class StationEventsTest {
  private val date = LocalDate(2026, 10, 17)
  private val zone = TimeZone.of("Asia/Shanghai")
  private val query = StationEventQuery(date, zone)
  private val cameras = listOf(StationEventCamera("a", "station", "门口"), StationEventCamera("b", "station", "货架"))
  private val start = stationEventDayRange(date, zone).startMillis

  @Test fun `local day boundaries follow timezone and daylight saving rather than fixed 24 hours`() {
    assertEquals(24 * 60 * 60 * 1000L, stationEventDayRange(date, zone).let { it.endExclusiveMillis - it.startMillis })
    val newYork = TimeZone.of("America/New_York")
    val spring = stationEventDayRange(LocalDate(2026, 3, 8), newYork)
    val autumn = stationEventDayRange(LocalDate(2026, 11, 1), newYork)
    assertEquals(23 * 60 * 60 * 1000L, spring.endExclusiveMillis - spring.startMillis)
    assertEquals(25 * 60 * 60 * 1000L, autumn.endExclusiveMillis - autumn.startMillis)
  }

  @Test fun `scope excludes other account devices and adjacent days while retaining local midnight`() {
    val end = stationEventDayRange(date, zone).endExclusiveMillis
    val events = listOf(event("previous", start - 1), event("midnight", start), event("end", end - 1),
      event("next", end), event("foreign", start + 100, "foreign"), event("", start + 100))
    val result = projectStationEvents(events, query, cameras)
    assertEquals(listOf("end", "midnight"), result.events.map { it.id })
    assertEquals("门口", result.events.last().deviceName)
    assertEquals(6, result.returnedCount)
  }

  @Test fun `device and detection filters are enforced locally even when SDK returns mixed results`() {
    val scoped = query.copy(deviceSn = "b", type = StationEventType.Human)
    val result = projectStationEvents(listOf(
      event("a-human", start, "a", DetectionType.HUMAN.value),
      event("b-motion", start, "b", DetectionType.MOTION.value),
      event("b-face", start, "b", DetectionType.FACE.value),
      event("b-human", start + 1, "b", DetectionType.HUMAN.value)
    ), scoped, cameras)
    assertEquals(listOf("b-human", "b-face"), result.events.map { it.id })
  }

  @Test fun `same identifiers deduplicate with latest complete metadata and sort newest first`() {
    val original = event("same", start + 100).copy(endTimestamp = start + 101)
    val updated = original.copy(endTimestamp = start + 1100)
    val result = projectStationEvents(listOf(original, event("old", start), updated, event("new", start + 200)), query, cameras)
    assertEquals(listOf("new", "same", "old"), result.events.map { it.id })
    assertEquals(1000L, result.events[1].durationMillis)
    assertEquals(updated, result.events[1].event)
  }

  @Test fun `identical native IDs on different cameras remain distinct playback targets`() {
    val result = projectStationEvents(listOf(event("native_same", start, "a"), event("native_same", start, "b")), query, cameras)
    assertEquals(2, result.events.size)
    assertEquals(setOf("a", "b"), result.events.map { it.deviceSn }.toSet())
    assertEquals(2, result.events.map { it.key }.toSet().size)
  }

  @Test fun `all doorbell variants faces and crying are retained and unknown detections are visible`() {
    listOf(DetectionType.RING, DetectionType.ANSWERED_RING, DetectionType.MISSED_RING).forEach { type ->
      val result = projectStationEvents(listOf(event("ring", start, detection = type.value)), query.copy(type = StationEventType.Doorbell), cameras)
      assertEquals(listOf("门铃"), result.events.single().typeLabels)
    }
    listOf(DetectionType.SOUND, DetectionType.CRY).forEach { type ->
      assertEquals(listOf("声音"), projectStationEvents(listOf(event("sound", start, detection = type.value)),
        query.copy(type = StationEventType.Sound), cameras).events.single().typeLabels)
    }
    val unknown = event("unknown", start, detection = 999).copy(pictures = listOf(MegaPicture(detectionType = DetectionType.HUMAN.value)))
    assertEquals(setOf("人物", "其他检测"), projectStationEvents(listOf(unknown), query, cameras).events.single().typeLabels.toSet())
    assertEquals(listOf("其他检测"), projectStationEvents(listOf(event("missing", start, detection = null)), query, cameras).events.single().typeLabels)
  }

  @Test fun `limit warning uses SDK count before deduplication and defensive filtering`() {
    val full = projectStationEvents(List(StationEventQueryLimit) { event("same", start, detection = DetectionType.MOTION.value) },
      query.copy(type = StationEventType.Human), cameras)
    assertTrue(full.limitReached)
    assertTrue(full.events.isEmpty())
    assertEquals(200, full.returnedCount)
    assertFalse(projectStationEvents(List(199) { event("same", start) }, query, cameras).limitReached)
  }

  @Test fun `query requests only allowed parent and child scope with inclusive end and 200 limit`() = runBlocking {
    val requester = FakeRequester(listOf(event("one", start, "b")))
    val result = loadStationEvents(query.copy(deviceSn = "b", type = StationEventType.Human), cameras, requester)
    val condition = requester.condition!!
    assertEquals(listOf("station"), condition.parentSnList)
    assertEquals(listOf("b"), condition.deviceSnList)
    assertEquals(start, condition.startTime)
    assertEquals(stationEventDayRange(date, zone).endExclusiveMillis - 1, condition.endTime)
    assertEquals(200, condition.count)
    assertEquals(setOf(DetectionType.HUMAN.value, DetectionType.FACE.value), condition.detectionList.toSet())
    assertEquals("one", result.events.single().id)
  }

  @Test fun `no cameras skips SDK and a removed selected camera is an error rather than zero events`() = runBlocking {
    val requester = FakeRequester(emptyList())
    assertTrue(loadStationEvents(query, emptyList(), requester).events.isEmpty())
    assertNull(requester.condition)
    try {
      loadStationEvents(query.copy(deviceSn = "removed"), cameras, requester)
      fail("Removed camera must not return a success")
    } catch (failure: StationEventsException) { assertEquals(StationEventFailure.UnavailableCamera, failure.reason) }
    assertNull(requester.condition)
  }

  @Test fun `SDK failures preserve diagnostic code without exposing provider message or returning empty success`() = runBlocking {
    val requester = StationEventRequester { _, _, failure ->
      failure(401, "private SDK diagnostic must not be displayed")
      StationEventCancellation { }
    }
    try {
      loadStationEvents(query, cameras, requester)
      fail("SDK failure must propagate")
    } catch (failure: StationEventsException) {
      assertEquals(StationEventFailure.Sdk, failure.reason)
      assertEquals(401, failure.sdkCode)
      assertFalse(failure.message.orEmpty().contains("private"))
    }
  }

  @Test fun `timeout cancels SDK handle and delayed success cannot turn it into a zero event success`() = runBlocking {
    val requester = FakeRequester()
    try {
      loadStationEvents(query, cameras, requester, timeoutMillis = 30)
      fail("Timeout must propagate")
    } catch (failure: StationEventsException) { assertEquals(StationEventFailure.TimedOut, failure.reason) }
    assertEquals(1, requester.cancelCount)
    requester.success!!(listOf(event("late", start)))
    requester.failure!!(500, "late")
    assertEquals(1, requester.cancelCount)
  }

  @Test fun `scope cancellation releases handle propagates cancellation and ignores late callbacks`() = runBlocking {
    val requester = FakeRequester()
    var delivered = false
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      loadStationEvents(query, cameras, requester)
      delivered = true
    }
    job.cancelAndJoin()
    requester.success!!(listOf(event("late", start)))
    requester.failure!!(500, "late")
    assertEquals(1, requester.cancelCount)
    assertTrue(job.isCancelled)
    assertFalse(delivered)
  }

  @Test fun `cancellation during request registration still cancels the returned handle`() = runBlocking {
    lateinit var target: Job
    var cancelled = 0
    var delivered = false
    val requester = StationEventRequester { _, _, _ ->
      target.cancel()
      StationEventCancellation { cancelled++ }
    }
    target = launch(start = CoroutineStart.LAZY) {
      loadStationEvents(query, cameras, requester)
      delivered = true
    }
    target.start()
    target.join()
    assertEquals(1, cancelled)
    assertTrue(target.isCancelled)
    assertFalse(delivered)
  }

  @Test fun `a throwing SDK cancel handle cannot escape lifecycle cancellation or deliver a late result`() = runBlocking {
    var success: ((List<MegaEvent>) -> Unit)? = null
    var failure: ((Int, String) -> Unit)? = null
    var cancelCount = 0
    var delivered = false
    val requester = StationEventRequester { _, onSuccess, onFailure ->
      success = onSuccess
      failure = onFailure
      StationEventCancellation {
        cancelCount++
        throw IllegalStateException("SDK cancel hook failed")
      }
    }
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      loadStationEvents(query, cameras, requester)
      delivered = true
    }
    // Without the cleanup guard, cancelAndJoin would fail the enclosing scope with
    // CompletionHandlerException instead of allowing a normal screen transition.
    job.cancelAndJoin()
    success!!(listOf(event("late", start)))
    failure!!(500, "late")
    assertEquals(1, cancelCount)
    assertTrue(job.isCancelled)
    assertFalse(delivered)
  }

  @Test fun `repeated success and failure callbacks keep the first terminal result`() = runBlocking {
    val requester = StationEventRequester { _, success, failure ->
      success(listOf(event("first", start)))
      success(listOf(event("second", start)))
      failure(500, "late")
      StationEventCancellation { }
    }
    assertEquals(listOf("first"), loadStationEvents(query, cameras, requester).events.map { it.id })
  }

  @Test fun `a broken mutable SDK snapshot reports invalid data instead of pretending there were no events`() = runBlocking {
    val broken = object : AbstractList<MegaEvent>() {
      override val size = 1
      override fun get(index: Int): MegaEvent = throw ConcurrentModificationException()
    }
    try {
      loadStationEvents(query, cameras, FakeRequester(broken))
      fail("An unreadable SDK list must be an error")
    } catch (failure: StationEventsException) { assertEquals(StationEventFailure.InvalidResult, failure.reason) }
  }

  private fun event(id: String, timestamp: Long, deviceSn: String = "a", detection: Int? = DetectionType.HUMAN.value) =
    MegaEvent(megaEventId = id, parentSn = "station", deviceSn = deviceSn, deviceName = "SDK name",
      startTimestamp = timestamp, endTimestamp = timestamp + 2000, videoPath = "sdk-event.mp4",
      thumbnail = MegaPicture(detectionType = detection))

  private class FakeRequester(private val immediateEvents: List<MegaEvent>? = null) : StationEventRequester {
    var condition: EventCondition? = null
    var success: ((List<MegaEvent>) -> Unit)? = null
    var failure: ((Int, String) -> Unit)? = null
    var cancelCount = 0
    override fun request(condition: EventCondition, onSuccess: (List<MegaEvent>) -> Unit, onFailure: (Int, String) -> Unit): StationEventCancellation {
      this.condition = condition
      success = onSuccess
      failure = onFailure
      immediateEvents?.let(onSuccess)
      return StationEventCancellation { cancelCount++ }
    }
  }
}
