package com.anker.esiotkit.sample.main.station.pipeline

import com.anker.esiotkit.core.entities.event.DetectionType
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.Serializable

internal const val MaxPeopleVideoBytes = 64L * 1024 * 1024
internal const val MaxPeopleTasks = 200
internal const val MaxPeopleAttempts = 5

@Serializable
internal data class PeopleSettings(
  val baseUrl: String = "",
  val bearerToken: String = "",
  val configVersion: String = "",
  val cameraMapping: Map<String, String> = emptyMap(),
  val enabled: Boolean = false,
  val enabledAtMs: Long = 0,
  val baselineReady: Boolean = false,
  val lastScanMs: Long = 0
) {
  val configured get() = baseUrl.isNotBlank() && bearerToken.isNotBlank() && configVersion.isNotBlank() && cameraMapping.isNotEmpty()
  fun targetKey() = peopleDigest("$baseUrl\u001f$configVersion\u001f${cameraMapping.toSortedMap()}")
  fun targetKey(sn: String) = peopleDigest("$baseUrl\u001f$configVersion\u001f$sn\u001f${cameraMapping[sn].orEmpty()}")
}

@Serializable
internal data class PeopleTask(
  val jobId: String,
  val sdkEventId: String,
  val deviceSn: String,
  val cameraId: String,
  val configVersion: String,
  val targetKey: String,
  val eventJson: String,
  val sdkTimestampMs: Long? = null,
  val createdAtMs: Long,
  val status: String = "discovered",
  val localPath: String? = null,
  val durationMs: Long = 0,
  val attempts: Int = 0,
  val nextAttemptMs: Long = 0,
  val error: String? = null,
  val retryable: Boolean = true,
  val remoteAccepted: Boolean = false,
  val eventCount: Int = 0,
  val evidenceCount: Int = 0,
  val completionDelivered: Boolean = false,
  val origin: String = "automatic"
)

@Serializable
internal data class PeopleSnapshot(val settings: PeopleSettings = PeopleSettings(), val tasks: List<PeopleTask> = emptyList(), val seen: List<String> = emptyList())

internal fun peopleDigest(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun peopleJobId(deviceSn: String, eventId: String): String = peopleDigest("$deviceSn\u001f$eventId")

internal fun normalizePeopleBaseUrl(input: String): String? {
  return runCatching {
  val uri = URI(input.trim())
  val host = uri.host?.lowercase() ?: return null
  val localHosts = setOf("10.0.2.2", "127.0.0.1", "localhost", "[::1]", "::1")
  if (uri.scheme != "https" && !(uri.scheme == "http" && host in localHosts)) return null
  if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
  if (uri.rawPath !in listOf("", "/") || uri.port !in -1..65535 || uri.port == 0) return null
  "${uri.scheme}://${uri.rawAuthority}".trimEnd('/')
  }.getOrNull()
}

internal fun peopleRetryDelay(attempt: Int): Long = listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L)[(attempt - 1).coerceIn(0, 4)]

/** The SDK query and both automatic/manual eligibility use the same face-or-human scope. */
internal val PeopleDetectionTypes = listOf(DetectionType.FACE.value, DetectionType.HUMAN.value)

internal fun isSdkPersonDetection(detectionValues: List<Int?>): Boolean =
  detectionValues.any { it != null && it in PeopleDetectionTypes }

internal fun canDiscoverPeopleEvent(startMs: Long, enabledAtMs: Long, detectionValues: List<Int?>): Boolean =
  startMs >= enabledAtMs && isSdkPersonDetection(detectionValues)

internal const val RejectedPeopleSourceMessage = "服务端拒绝原片或清单，可能是录像损坏或配置不匹配。"

internal fun canRedownloadPeopleTask(task: PeopleTask): Boolean = task.status == "failed" && !task.remoteAccepted &&
  task.error in setOf(RejectedPeopleSourceMessage, "服务端拒绝录像或配置，请核对 camera_id 与 config_version。")

/** An explicit user retry may replace bytes only while the server has never accepted the source. */
internal fun retryPeopleTask(task: PeopleTask): PeopleTask = when {
  canRedownloadPeopleTask(task) -> task.copy(status = "discovered", localPath = null, durationMs = 0,
    attempts = 0, nextAttemptMs = 0, error = null, retryable = true)
  !task.retryable -> task
  else -> task.copy(status = if (task.remoteAccepted) "queued" else if (task.localPath != null) "ready" else "discovered",
    attempts = 0, nextAttemptMs = 0, error = null)
}

internal fun shouldReadPeopleRemoteStatus(task: PeopleTask): Boolean = task.remoteAccepted &&
  (task.status in setOf("queued", "processing") || task.status == "failed" && task.retryable)

/** Losing a status response cannot turn an accepted server job into a Vision failure. */
internal fun deferPeopleTaskAfterFailure(task: PeopleTask, message: String, nowMs: Long): PeopleTask {
  if (task.status == "completed" || task.status == "failed" && !task.retryable) return task
  if (task.remoteAccepted) {
    if (!shouldReadPeopleRemoteStatus(task)) return task
    val attempt = task.attempts.coerceIn(0, MaxPeopleAttempts - 1) + 1
    return task.copy(status = if (task.status == "processing") "processing" else "queued",
      attempts = attempt, nextAttemptMs = nowMs + peopleRetryDelay(attempt), error = message, retryable = true)
  }
  val attempt = task.attempts + 1
  return task.copy(attempts = attempt, error = message, retryable = true,
    status = if (attempt >= MaxPeopleAttempts) "failed" else "retry_wait",
    nextAttemptMs = nowMs + peopleRetryDelay(attempt))
}

internal fun recoverPeopleTask(task: PeopleTask): PeopleTask = when {
  task.remoteAccepted && task.status == "failed" && task.retryable -> task.copy(status = "queued", attempts = 0, nextAttemptMs = 0, error = null)
  task.status == "downloading" -> task.copy(status = if (task.localPath != null) "ready" else "discovered")
  task.status == "uploading" -> task.copy(status = "ready")
  else -> task
}
