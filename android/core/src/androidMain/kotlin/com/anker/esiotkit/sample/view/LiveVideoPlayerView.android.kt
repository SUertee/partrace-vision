package com.anker.esiotkit.sample.view

import android.util.Log
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.anker.esiotkit.media.live_stream.LiveMediaPlayer

/**
 * @version 2024/8/30
 */
@Composable
actual fun LiveStreamPlayView(
  modifier: Modifier, liveMediaPlayer: LiveMediaPlayer,
  onViewCreated: () -> Unit
) {
  AndroidView(
    factory = { context ->
//      P2PLiveStreamPlayer(context).apply {
//        liveMediaPlayer.initVideoView(this)
//      }
      FrameLayout(context).apply {
        liveMediaPlayer.initVideoView(this)
        Log.i("ESIot/data", "LiveVideoPlayerView LiveDebug: [FACTORY] onViewCreated callback invoked")
        onViewCreated()
      }
    }, modifier = modifier
  )
}