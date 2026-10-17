package com.anker.esiotkit.sample.main

import com.anker.esiotkit.support.ioScope
import com.anker.esiotkit.support.job.CancellableJob
import com.anker.esiotkit.support.job.toCancellableJob
import com.anker.esiotkit.support.mainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * @version 2024/10/4
 */
sealed class SampleAppEvent {
  data object Idle : SampleAppEvent()
  data object Logout : SampleAppEvent()
  data class IntoDeviceSettings(val deviceSn: String) : SampleAppEvent()
  data class IntoPairing(val devicePn: String) : SampleAppEvent()
  data object IntoMainScreen : SampleAppEvent()
  data class ShowDeviceEvents(val deviceSn: String) : SampleAppEvent()
  data object ExitApp : SampleAppEvent()

  companion object {
    fun addListener(listener: (SampleAppEvent) -> Unit): CancellableJob {
      return mainScope.launch {
        sampleAppEvent
          .collect { listener(it) }
      }.toCancellableJob()
    }

    fun emit(event: SampleAppEvent) {
      _sampleAppEvent.trySend(event)
    }
  }
}

private val _sampleAppEvent: Channel<SampleAppEvent> = Channel(Channel.BUFFERED)

internal val sampleAppEvent =
  _sampleAppEvent.receiveAsFlow().shareIn(ioScope, started = SharingStarted.Eagerly, replay = 1)
