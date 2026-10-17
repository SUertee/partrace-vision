package com.anker.esiotkit.sample.main.station.ui.events

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import cafe.adriel.voyager.navigator.Navigator
import com.anker.esiotkit.sample.main.initializeSampleDependencies
import com.anker.esiotkit.sample.ui.composable.PlatformNavigatorContent
import com.anker.esiotkit.support.di.esKoin
import org.koin.compose.KoinContext

typealias StationBackendEventLauncher = (String, String, String?) -> Unit

/** Android supplies an independent host; other hosts retain the existing navigator. */
val LocalStationBackendEventLauncher = staticCompositionLocalOf<StationBackendEventLauncher?> { null }
internal val LocalStationBackendEventBack = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Uses the same authorized repository and UI without the SDK home navigation lifecycle. */
@Composable
fun StationBackendEventDetailHost(eventId: String, recordingId: String, evidenceId: String?, onBack: () -> Unit) {
  initializeSampleDependencies()
  val target = remember(eventId, recordingId, evidenceId) {
    StationEventDetailScreen.backend(eventId, recordingId, evidenceId)
  }
  KoinContext(esKoin) {
    MaterialTheme {
      Navigator(target) { navigator ->
        CompositionLocalProvider(LocalStationBackendEventBack provides {
          if (!navigator.pop()) onBack()
        }) {
          PlatformNavigatorContent(navigator)
        }
      }
    }
  }
}
