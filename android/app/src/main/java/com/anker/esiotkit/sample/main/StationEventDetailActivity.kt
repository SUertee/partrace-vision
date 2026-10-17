package com.anker.esiotkit.sample.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.anker.esiotkit.sample.main.station.ui.events.StationBackendEventDetailHost

/** Private app navigation: identifiers only; the repository rechecks authorization on every load. */
class StationEventDetailActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val eventId = intent.getStringExtra(EventId)
    val recordingId = intent.getStringExtra(RecordingId)
    val evidenceId = intent.getStringExtra(EvidenceId)
    if (eventId == null || recordingId == null || !Id.matches(eventId) || !Id.matches(recordingId) ||
      (evidenceId != null && !Id.matches(evidenceId))) {
      finish()
      return
    }
    enableEdgeToEdge()
    WindowCompat.getInsetsController(window, window.decorView).apply {
      isAppearanceLightStatusBars = true
      isAppearanceLightNavigationBars = true
    }
    setContent {
      StationBackendEventDetailHost(eventId, recordingId, evidenceId, onBack = ::finish)
    }
  }

  companion object {
    private const val EventId = "event_id"
    private const val RecordingId = "recording_id"
    private const val EvidenceId = "evidence_id"
    private val Id = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}")

    fun intent(context: Context, eventId: String, recordingId: String, evidenceId: String?): Intent =
      Intent(context, StationEventDetailActivity::class.java)
        .putExtra(EventId, eventId).putExtra(RecordingId, recordingId).putExtra(EvidenceId, evidenceId)
  }
}
