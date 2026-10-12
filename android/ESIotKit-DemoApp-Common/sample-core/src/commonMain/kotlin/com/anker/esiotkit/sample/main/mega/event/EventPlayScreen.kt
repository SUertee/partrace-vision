package com.anker.esiotkit.sample.main.mega.event

import kotlinx.serialization.Transient

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Slider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.media.event_stream.EventMediaPlayer
import com.anker.esiotkit.media.event_stream.EventPlayerState
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.sample.support.exitFullScreen
import com.anker.esiotkit.sample.support.fullScreen
import com.anker.esiotkit.sample.support.toggleScreenOrientation
import com.anker.esiotkit.sample.ui.composable.appendWhen
import com.anker.esiotkit.sample.ui.composable.state
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.EventStreamPlayView
import com.anker.esiotkit.sample.view.TitleBar
import com.anker.esiotkit.support.ktx.msToDurationStringWithoutHour
import com.anker.esiotkit.support.log.appLogger

/**
 * @version 2024/9/23
 */
class EventPlayScreen(
  @Transient private val megaEvent: MegaEvent = MegaEvent()
) : BaseScreen() {
  @Composable
  override fun dynamicTitle(): String = megaEvent.deviceName.ifEmpty { megaEvent.deviceSn }

  override fun onBackClick() {
    if (fullScreen.value) {
      exitFullScreen()
    } else {
      super.onBackClick()
    }
  }

  @Composable
  override fun Content() = BaseColumn(scrollState = rememberScrollState()) {
    val device = remember {
      MegaDeviceManager.getDeviceBy(megaEvent.deviceSn)
    }
    val navigator = LocalNavigator.currentOrThrow
    if (device == null) {
      navigator.pop()
      return@BaseColumn
    }
    val viewModel = rememberScreenModel { EventPlayViewModel(device, megaEvent) }
    val isFullScreen by fullScreen.collectAsState()
    // AndroidView.factory（设置 frameLayout）在 layout 阶段执行，晚于 DisposableEffect.onRemembered。
    // 直接在 DisposableEffect 中 play() 会因 frameLayout=null 导致 IjkVideoView NPE，故推迟一帧。
    LaunchedEffect(Unit) {
      withFrameNanos { }
      appLogger.i("EventPlayScreen/Content/startPlay: delayed by one frame, playState=${viewModel.playState}")
      try {
        viewModel.play()
      } catch (e: Exception) {
        appLogger.e("EventPlayScreen/Content/playError: ${e.message}")
      }
    }
    DisposableEffect(Unit) {
      onDispose {
        exitFullScreen()
        viewModel.stopAndRelease()
      }
    }
    Box(
      modifier = Modifier
        .appendWhen(!isFullScreen) { align(Alignment.CenterHorizontally) }
        .appendWhen(isFullScreen) { fillMaxSize().background(Color.Black) },
      contentAlignment = Alignment.Center
    ) {
      EventPlayer(
        player = viewModel.player,
        loading = remember(viewModel.playState) { viewModel.playState == EventPlayerState.Loading },
        aspectRatio = viewModel.aspectRatio
      )
      if (isFullScreen) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "退出全屏",
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(16.dp)
            .size(24.dp)
            .clickable { toggleScreenOrientation() },
          tint = Color.White
        )
      }
    }
    appLogger.i("EventPlayScreen/Content/playState:${viewModel.playState}")
    if (!isFullScreen) {
      EventPlayController(
        playState = viewModel.playState,
        totalTime = remember { megaEvent.endTimestamp - megaEvent.startTimestamp },
        progress = viewModel.playProgress,
        onClickPlay = {
          when (viewModel.playState) {
            EventPlayerState.Playing -> viewModel.pause()
            EventPlayerState.Paused -> viewModel.resume()
            EventPlayerState.Loading -> {}
            else -> viewModel.play()
          }
        },
        onClickFullScreen = {
          toggleScreenOrientation()
        },
        onSeek = viewModel::seek,
      )
      DownloadController(
        progress = viewModel.downloadProgress,
        isMuted = viewModel.isMuted,
        silentDownloading = viewModel.silentDownloading,
        onClickDownload = viewModel::downloadEvent,
        onClickSilentDownload = viewModel::downloadEventSilently,
        onClickMute = viewModel::toggleMute
      )
    }
    if (viewModel.downErrorInfo.isNotEmpty()) {
      Text(
        text = viewModel.downErrorInfo,
        fontSize = 14.sp,
        modifier = Modifier.padding(8.dp),
        color = Color.Red
      )
    }
  }

  @Composable
  fun EventPlayer(
    player: EventMediaPlayer,
    loading: Boolean,
    aspectRatio: Float = 16 / 9f
  ) {
    val isFullScreen by fullScreen.collectAsState()
    BoxWithConstraints(
      modifier = Modifier
        .appendWhen(!isFullScreen) { fillMaxWidth() }
        .appendWhen(isFullScreen) { fillMaxSize() }
        .background(color = Color.Black)
    ) {
      val isLargeScreen = maxWidth > 480.dp
      val videoMaxWidth = if (isFullScreen) {
        maxWidth
      } else if (isLargeScreen) {
        (maxWidth * 0.5f).coerceAtMost(500.dp)
      } else {
        maxWidth
      }

      Box(
        modifier = Modifier
          .widthIn(max = videoMaxWidth)
          .then(if (isLargeScreen && !isFullScreen) Modifier.heightIn(max = 700.dp) else Modifier)
          .aspectRatio(aspectRatio)
          .align(Alignment.Center)
      ) {
        EventStreamPlayView(modifier = Modifier.fillMaxSize(), player = player)
        if (loading) {
          CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center).size(48.dp),
          )
        }
        Icon(
          imageVector = Icons.Filled.Fullscreen,
          contentDescription = if (isFullScreen) "退出全屏" else "全屏",
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .clickable { toggleScreenOrientation() }
            .padding(12.dp)
            .size(24.dp),
          tint = Color.White
        )
      }
    }
  }

  @Composable
  fun EventPlayController(
    playState: EventPlayerState,
    totalTime: Long,
    progress: Float,
    onClickPlay: () -> Unit,
    onClickFullScreen: () -> Unit,
    onSeek: (Float) -> Unit,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(8.dp)
    ) {
      val playIcon = when (playState) {
        EventPlayerState.Playing -> Icons.Default.Pause
        EventPlayerState.Finished, EventPlayerState.Timeout -> Icons.Default.Replay
        else -> Icons.Default.PlayArrow
      }
      Icon(
        imageVector = playIcon, contentDescription = "",
        modifier = Modifier.size(48.dp).clickable {
          onClickPlay()
        },
      )
      SeekBar(progress, playState, onSeek)
      val totalTimeString = remember { totalTime.msToDurationStringWithoutHour() }
      val current = remember(progress) {
        (totalTime * progress).toLong().msToDurationStringWithoutHour()
      }
      Text(
        text = "$current / $totalTimeString",
        fontSize = 12.sp,
      )
    }
  }

  @Composable
  private fun RowScope.SeekBar(
    progress: Float,
    playState: EventPlayerState,
    onSeek: (Float) -> Unit
  ) {
    var seekTarget by state(0f)
    Slider(
      value = if (seekTarget == 0f) progress else seekTarget,
      valueRange = 0f..1f,
      onValueChange = {
        if (playState != EventPlayerState.Loading) {
          seekTarget = it
        }
      },
      onValueChangeFinished = {
        onSeek(seekTarget)
        seekTarget = 0f
      },
      modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
    )
  }

  @Composable
  fun DownloadController(
    progress: Float,
    isMuted: Boolean,
    silentDownloading: Boolean = false,
    onClickDownload: () -> Unit,
    onClickSilentDownload: () -> Unit = {},
    onClickMute: () -> Unit
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        TextButton(onClick = onClickDownload) {
          Text("Download")
        }
        TextButton(
          onClick = onClickSilentDownload,
          colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            contentColor = if (silentDownloading) Color.Red else MaterialTheme.colorScheme.primary
          )
        ) {
          Text(if (silentDownloading) "Stop Silent" else "Silent DL")
        }
        LinearProgressIndicator(
          modifier = Modifier.weight(1f),
          progress = { progress }
        )
        Icon(
          imageVector = if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
          contentDescription = if (isMuted) "取消静音" else "静音",
          modifier = Modifier.size(40.dp).clickable {
            onClickMute()
          },
        )
      }
    }
  }
}