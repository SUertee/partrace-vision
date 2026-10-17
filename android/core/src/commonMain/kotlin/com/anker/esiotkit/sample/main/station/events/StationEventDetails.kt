package com.anker.esiotkit.sample.main.station.events

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

sealed interface StationEventDetailTarget {
  data class BackendEvent(val eventId: String, val expectedRecordingId: String, val focusedEvidenceId: String? = null) : StationEventDetailTarget
  data class SdkDetection(val deviceSn: String, val sdkEventId: String) : StationEventDetailTarget
}

/** An opaque read session. Credentials and SDK objects never enter shared UI state. */
data class StationEventDetailSession(val origin: String, val id: String)

data class StationDetailRecording(
  val id: String, val cameraId: String, val configVersion: String, val sourceMode: String,
  val durationMs: Long, val publicationStatus: String, val mediaStatus: String, val startedAt: String?
)

data class StationDetailEvent(
  val id: String, val recordingId: String, val zoneId: String, val type: String,
  val observationStatus: String, val detectionSource: String, val startOffsetMs: Long,
  val endOffsetMs: Long, val summary: String?, val personId: String?, val evidenceIds: List<String>
)

data class StationDetailEvidence(val id: String, val eventId: String, val offsetMs: Long, val mediaStatus: String)

data class StationDetailJob(val id: String, val status: String, val sdkEventId: String, val eventCount: Int?, val evidenceCount: Int?, val deviceSn: String? = null, val sdkTimestampMs: Long? = null)

data class StationEventDetails(
  val recording: StationDetailRecording,
  val selectedEvent: StationDetailEvent?,
  val timeline: List<StationDetailEvent>,
  val evidence: List<StationDetailEvidence>,
  val focusedEvidenceId: String?,
  val job: StationDetailJob?,
  val session: StationEventDetailSession,
  val limitReached: Boolean = false
)

enum class StationEventDetailAvailability { NotConfigured, DifferentTarget, NotImported, Queued, Processing, AnalysisFailed, NotPublished, NoConfiguredZones }

sealed interface StationEventDetailsResult {
  data class Loaded(val detail: StationEventDetails) : StationEventDetailsResult
  data class Unavailable(val kind: StationEventDetailAvailability, val message: String, val job: StationDetailJob? = null) : StationEventDetailsResult
  data class Failed(val message: String) : StationEventDetailsResult
}

sealed interface StationEventImageResult {
  data class Loaded(val bytes: ByteArray) : StationEventImageResult
  data class Failed(val message: String) : StationEventImageResult
}

/** Ephemeral platform media handle; no URL, path or credentials enter navigation. */
interface StationRecordingPlayback {
  fun release()
}

sealed interface StationEventRecordingResult {
  data class Loaded(val playback: StationRecordingPlayback) : StationEventRecordingResult
  data class Failed(val message: String) : StationEventRecordingResult
}

interface StationEventDetailSource {
  suspend fun load(target: StationEventDetailTarget): StationEventDetailsResult
  suspend fun evidence(session: StationEventDetailSession, evidenceId: String): StationEventImageResult
  suspend fun recording(session: StationEventDetailSession, recordingId: String): StationEventRecordingResult =
    StationEventRecordingResult.Failed("当前平台暂不支持原片回看。")
}

@Composable
expect fun rememberStationEventDetailSource(): StationEventDetailSource

@Composable
expect fun StationRecordingPlayer(playback: StationRecordingPlayback, startOffsetMs: Long,
  modifier: Modifier = Modifier, onFailure: (String) -> Unit)
