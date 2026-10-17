package com.anker.esiotkit.sample.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.anker.esiotkit.media.live_stream.LiveMediaPlayer

/**
 * @version 2024/8/30
 */
@Composable
expect fun LiveStreamPlayView(
  modifier: Modifier, liveMediaPlayer: LiveMediaPlayer,
  onViewCreated: () -> Unit
)