package com.anker.esiotkit.sample.main.station.records

import androidx.compose.runtime.Composable

/** Alarm messages are supplied by an alarm service. Pipeline jobs are not alarm messages. */
data class StationAlarmMessage(val id: String, val monthLabel: String, val dateLabel: String, val text: String)

data class StationReviewRecord(
  val id: String,
  val eventId: String,
  val createdAtLabel: String,
  val decisionLabel: String,
  val note: String? = null,
  val reviewerId: String = "",
  val recordingId: String = "",
  val zoneId: String = ""
)

data class StationReviewDetail(val review: StationReviewRecord, val evidenceIds: List<String> = emptyList())

sealed interface StationReviewResult {
  data class Loaded(val reviews: List<StationReviewRecord>) : StationReviewResult
  data object NotConfigured : StationReviewResult
  data class Failed(val message: String) : StationReviewResult
}

sealed interface StationReviewDetailResult {
  data class Loaded(val detail: StationReviewDetail) : StationReviewDetailResult
  data class Failed(val message: String) : StationReviewDetailResult
}

interface StationReviewSource {
  suspend fun load(): StationReviewResult
  suspend fun detail(id: String): StationReviewDetailResult
}

@Composable
expect fun rememberStationReviewSource(): StationReviewSource

/** Rechecked when the host resumes, including after returning from system notification settings. */
@Composable
expect fun rememberStationNotificationsEnabled(): Boolean
