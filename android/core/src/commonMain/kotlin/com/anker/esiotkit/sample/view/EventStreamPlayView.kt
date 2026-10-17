package com.anker.esiotkit.sample.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.anker.esiotkit.media.event_stream.EventMediaPlayer

@Composable
expect fun EventStreamPlayView(modifier: Modifier = Modifier, player: EventMediaPlayer, onVideoViewReady: () -> Unit = {})