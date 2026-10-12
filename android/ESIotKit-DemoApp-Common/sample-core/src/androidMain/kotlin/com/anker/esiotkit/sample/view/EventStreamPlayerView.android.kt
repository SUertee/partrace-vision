package com.anker.esiotkit.sample.view

import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.anker.esiotkit.media.event_stream.EventMediaPlayer

/**
 * @version 2024/9/23
 */
@Composable
actual fun EventStreamPlayView(modifier: Modifier, player: EventMediaPlayer, onVideoViewReady: () -> Unit) {
  AndroidView(
    factory = { context ->
      FrameLayout(context).apply {
        player.initVideoView(this)
        onVideoViewReady()
      }
    }, modifier = modifier
  )
}