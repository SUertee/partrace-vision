package com.anker.esiotkit.sample.main.station.events

import com.anker.esiotkit.core.constants.isStation
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.core.entities.event.DetectionType
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.event.model.EventCondition
import com.anker.esiotkit.manager.MegaEventManager
import com.anker.esiotkit.network.constants.NetConstant
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val StationEventQueryLimit = 200
private const val StationEventQueryTimeoutMillis = 30_000L

/** SDK detections describe what the camera detected, not reviewed business actions. */
enum class StationEventType(val label: String, internal val detectionTypes: Set<Int>) {
  All("全部类型", emptySet()),
  Human("人物", setOf(DetectionType.HUMAN.value, DetectionType.FACE.value)),
  Motion("移动", setOf(DetectionType.MOTION.value)),
  Pet("宠物", setOf(DetectionType.PET.value)),
  Vehicle("车辆", setOf(DetectionType.CAR.value)),
  Package("包裹", setOf(DetectionType.PACKAGE_PUT.value, DetectionType.PACKAGE_TAKE.value)),
  Doorbell("门铃", setOf(DetectionType.RING.value, DetectionType.ANSWERED_RING.value, DetectionType.MISSED_RING.value)),
  Sound("声音", setOf(DetectionType.SOUND.value, DetectionType.CRY.value))
}

data class StationEventCamera(val sn: String, val parentSn: String, val name: String)

data class StationEventQuery(
  val date: LocalDate,
  val zone: TimeZone,
  val deviceSn: String? = null,
  val type: StationEventType = StationEventType.All
)

data class StationEventDayRange(val startMillis: Long, val endExclusiveMillis: Long)

/** Playback uses this in-memory SDK event; it must not be saved in navigation/UI state. */
data class StationEventCard(
  val id: String,
  val deviceSn: String,
  val deviceName: String,
  val startMillis: Long,
  val durationMillis: Long,
  val typeLabels: List<String>,
  val event: MegaEvent
) {
  val key: String get() = "${deviceSn.length}:$deviceSn$id"
}

/** SDK success can contain cached or partial device results; it is never a coverage claim. */
data class StationEventResult(
  val events: List<StationEventCard>,
  val limitReached: Boolean,
  val returnedCount: Int
)

enum class StationEventFailure { Sdk, TimedOut, UnavailableCamera, InvalidResult }

class StationEventsException(
  val reason: StationEventFailure,
  val sdkCode: Int? = null
) : Exception(when (reason) {
  StationEventFailure.Sdk -> "摄像头事件加载失败，请重试。" + (sdkCode?.let { "（错误码 $it）" } ?: "")
  StationEventFailure.TimedOut -> "摄像头事件加载超时，请检查网络后重试。"
  StationEventFailure.UnavailableCamera -> "所选摄像头已不可用，请重新选择。"
  StationEventFailure.InvalidResult -> "摄像头事件数据读取失败，请重试。"
})

/** Read only the current account's SDK device snapshot. This does not start SDK requests. */
fun currentStationEventCameras(): List<StationEventCamera> = MegaDeviceManager.deviceList.value
  .filter { it.category == NetConstant.CATEGORY_SECURITY && !it.deviceSn.isStation() }
  .map { device -> StationEventCamera(device.deviceSn, device.parentSn.ifBlank { device.deviceSn },
    device.iotDevice.deviceName.trim().ifEmpty { device.deviceSn }) }
  .distinctBy { it.sn }

fun stationEventDayRange(date: LocalDate, zone: TimeZone): StationEventDayRange = StationEventDayRange(
  date.atStartOfDayIn(zone).toEpochMilliseconds(),
  date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toEpochMilliseconds()
)

/** One refresh request, bounded to 200 returned SDK events. No unverified cursor paging. */
suspend fun loadStationEvents(query: StationEventQuery, cameras: List<StationEventCamera>): StationEventResult =
  loadStationEvents(query, cameras, SdkStationEventRequester)

internal fun interface StationEventCancellation { fun cancel() }

/** Kept injectable so callback races, cancellation and SDK failures can be tested offline. */
internal fun interface StationEventRequester {
  fun request(
    condition: EventCondition,
    onSuccess: (List<MegaEvent>) -> Unit,
    onFailure: (Int, String) -> Unit
  ): StationEventCancellation
}

private object SdkStationEventRequester : StationEventRequester {
  override fun request(
    condition: EventCondition,
    onSuccess: (List<MegaEvent>) -> Unit,
    onFailure: (Int, String) -> Unit
  ): StationEventCancellation {
    // refresh=true requests SDK refresh then returns its merged local/cloud snapshot.
    // The SDK may still succeed with cached/partial results; do not describe it as all-fresh.
    val request = MegaEventManager.query(condition, refresh = true, lastEventTime = 0,
      onSuccess = onSuccess, onFailure = onFailure)
    return StationEventCancellation { request.cancel() }
  }
}

internal suspend fun loadStationEvents(
  query: StationEventQuery,
  cameras: List<StationEventCamera>,
  requester: StationEventRequester,
  timeoutMillis: Long = StationEventQueryTimeoutMillis
): StationEventResult {
  currentCoroutineContext().ensureActive()
  val allowedCameras = cameras.filter { it.sn.isNotBlank() }.distinctBy { it.sn }
  val selectedCameras = if (query.deviceSn == null) allowedCameras else
    allowedCameras.filter { it.sn == query.deviceSn }.also {
      if (it.isEmpty()) throw StationEventsException(StationEventFailure.UnavailableCamera)
    }
  if (selectedCameras.isEmpty()) return StationEventResult(emptyList(), false, 0)
  val range = stationEventDayRange(query.date, query.zone)
  val condition = EventCondition(
    _parentSnList = selectedCameras.map { it.parentSn.ifBlank { it.sn } }.distinct(),
    _startTime = range.startMillis,
    // The SDK uses an inclusive end date; don't request the next local day at midnight.
    _endTime = range.endExclusiveMillis - 1
  ).setDeviceSnList(selectedCameras.map { it.sn })
    .setDetectionTypeList(query.type.detectionTypes.toList())
    .setCount(StationEventQueryLimit)
  val events = try {
    withTimeoutOrNull(timeoutMillis) { awaitStationEventSnapshot(condition, requester) }
      ?: throw StationEventsException(StationEventFailure.TimedOut)
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failure: StationEventsException) {
    throw failure
  } catch (_: Exception) {
    throw StationEventsException(StationEventFailure.Sdk)
  }
  currentCoroutineContext().ensureActive()
  val result = try { projectStationEvents(events, query, selectedCameras) }
  catch (cancelled: CancellationException) { throw cancelled }
  catch (_: Exception) { throw StationEventsException(StationEventFailure.InvalidResult) }
  currentCoroutineContext().ensureActive()
  return result
}

private suspend fun awaitStationEventSnapshot(
  condition: EventCondition,
  requester: StationEventRequester
): List<MegaEvent> = suspendCancellableCoroutine { continuation ->
  val finished = atomic(false)
  val request = try {
    requester.request(condition, onSuccess = { events ->
      if (finished.compareAndSet(false, true) && continuation.isActive) {
        try {
          // Never retain the SDK's concurrently mutable list in Compose state.
          continuation.resume(events.toList())
        } catch (_: Exception) {
          if (continuation.isActive) continuation.resumeWithException(
            StationEventsException(StationEventFailure.InvalidResult))
        }
      }
    }, onFailure = { code, _ ->
      if (finished.compareAndSet(false, true) && continuation.isActive) {
        continuation.resumeWithException(StationEventsException(StationEventFailure.Sdk, code))
      }
    })
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    if (finished.compareAndSet(false, true) && continuation.isActive) {
      continuation.resumeWithException(StationEventsException(StationEventFailure.Sdk))
    }
    null
  }
  // Register after the handle is available: cancellation during request() then immediately
  // invokes this handler too, so an early timeout cannot leak the newly returned SDK job.
  continuation.invokeOnCancellation {
    finished.value = true
    // Cleanup must not throw into the cancelling UI/lifecycle coroutine.
    runCatching { request?.cancel() }
  }
}

internal fun projectStationEvents(
  events: List<MegaEvent>,
  query: StationEventQuery,
  cameras: List<StationEventCamera>
): StationEventResult {
  val range = stationEventDayRange(query.date, query.zone)
  val bySn = cameras.associateBy { it.sn }
  val matching = events.asSequence()
    .filter { it.megaEventId.isNotBlank() && it.deviceSn in bySn }
    .filter { query.deviceSn == null || it.deviceSn == query.deviceSn }
    .filter { it.startTimestamp >= range.startMillis && it.startTimestamp < range.endExclusiveMillis }
    .filter { query.type == StationEventType.All || stationDetectionTypes(it).any(query.type.detectionTypes::contains) }
    .sortedWith(compareByDescending<MegaEvent> { it.startTimestamp }.thenByDescending { it.endTimestamp }.thenBy { it.megaEventId }.thenBy { it.deviceSn })
    .distinctBy { it.deviceSn to it.megaEventId }
    .take(StationEventQueryLimit)
    .map { event -> StationEventCard(
      id = event.megaEventId,
      deviceSn = event.deviceSn,
      deviceName = bySn.getValue(event.deviceSn).name.ifBlank { event.deviceName.ifBlank { event.deviceSn } },
      startMillis = event.startTimestamp,
      durationMillis = (event.endTimestamp - event.startTimestamp).coerceAtLeast(0),
      typeLabels = stationEventLabels(event),
      event = event
    ) }.toList()
  // Use the SDK count before local defensive filters or deduplication; a filtered short
  // list does not establish that the original query was below its server-side limit.
  return StationEventResult(matching, events.size >= StationEventQueryLimit, events.size)
}

private fun stationDetectionTypes(event: MegaEvent): List<Int> =
  (event.pictures.mapNotNull { it.detectionType } + listOfNotNull(event.thumbnail.detectionType)).distinct()

private fun stationEventLabels(event: MegaEvent): List<String> {
  val detections = stationDetectionTypes(event)
  if (detections.isEmpty()) return listOf("其他检测")
  return detections.map { detection -> StationEventType.entries
    .firstOrNull { detection in it.detectionTypes }?.label ?: "其他检测" }.distinct()
}
