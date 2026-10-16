package com.anker.esiotkit.sample.main.mega.live_video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DetailInk = Color(0xFF333333)
private val DetailBlue = Color(0xFF638FF4)
private val DetailBackground = Color(0xFFF6F6F6)

/** Presentation only. The caller supplies the original SDK player and actions. */
@Composable
internal fun DeviceLiveDetailLayout(
  name: String,
  state: MegaLiveStreamUiState,
  fullScreen: Boolean,
  onBack: () -> Unit,
  onSettings: () -> Unit,
  onMore: () -> Unit,
  onPTZ: () -> Unit,
  onTalk: () -> Unit,
  onRecord: () -> Unit,
  onCapture: () -> Unit,
  onFullScreen: () -> Unit,
  onEvents: () -> Unit,
  onPlayback: () -> Unit,
  onRecordings: () -> Unit,
  onDownloads: () -> Unit,
  player: @Composable () -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
  val portraitPlayerMaxHeight = (maxHeight * 0.48f).coerceAtMost(360.dp)
  Column(Modifier.fillMaxSize().background(if (fullScreen) Color.Black else DetailBackground)) {
    if (!fullScreen) {
      Row(Modifier.fillMaxWidth().background(Color.White).statusBarsPadding().height(72.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = DetailInk) }
        Text(name, Modifier.weight(1f).padding(start = 8.dp), color = DetailInk, fontSize = 19.sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onMore) { Icon(Icons.Default.MoreHoriz, "更多设备控制", tint = DetailInk, modifier = Modifier.size(27.dp)) }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设备设置", tint = DetailInk, modifier = Modifier.size(25.dp)) }
      }
    }
    // This slot stays at the same composition location during fullscreen changes.
    Box(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.weight(1f) else Modifier.heightIn(max = portraitPlayerMaxHeight)), contentAlignment = Alignment.Center) {
      player()
    }
    if (!fullScreen) {
      Row(Modifier.fillMaxWidth().height(74.dp).background(Color.White).padding(horizontal = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        DetailQuickAction(Icons.Default.ControlCamera, "云台控制", state.supportsPTZControl && state.isPlaying, onClick = onPTZ)
        DetailQuickAction(Icons.Default.Mic, if (state.talkStatus.running) "结束对讲" else "对讲",
          state.supportsTalk && (state.isPlaying || state.talkStatus.running), state.talkStatus, onTalk)
        DetailQuickAction(Icons.Default.Videocam, if (state.recordStatus.running) "停止录像" else "录像",
          state.supportsRecord && (state.isPlaying || state.recordStatus.running), state.recordStatus, onRecord)
        DetailQuickAction(Icons.Default.PhotoCamera, "截图", state.supportsScreenshot && state.isPlaying, onClick = onCapture)
        DetailQuickAction(Icons.Default.Fullscreen, "全屏", true, onClick = onFullScreen)
      }
      Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        .navigationBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(17.dp))
          .background(Brush.horizontalGradient(listOf(Color(0xFFF1FCFA), Color.White, Color(0xFFF5F1FF), Color(0xFFF0FCFF))))
          .clickable(onClick = onMore).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.AutoAwesome, null, tint = DetailBlue, modifier = Modifier.size(24.dp))
          Spacer(Modifier.width(12.dp))
          Text("更多设备控制", Modifier.weight(1f), color = DetailBlue, fontSize = 16.sp)
          Icon(Icons.Default.ChevronRight, null, tint = DetailBlue)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
          .clickable(onClick = onEvents).padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.AutoMirrored.Filled.Message, null, tint = Color(0xFFF4A000), modifier = Modifier.size(25.dp))
          Text("Events", Modifier.weight(1f).padding(start = 12.dp), color = DetailInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
          Text("More", color = Color(0xFF999999), fontSize = 16.sp)
          Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFBBBBBB))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailRecordingCard("Playback", Icons.Default.VideoLibrary, Color(0xFF8184F5), onPlayback)
            DetailRecordingCard("All\nRecordings", Icons.Default.SdStorage, Color(0xFF65B8EE), onRecordings)
          }
          Column(Modifier.weight(1f).heightIn(min = 164.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFE0EEFF), Color.White, Color.White)))
            .padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text("录像管理", color = DetailInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("查看录像下载\n和任务进度", color = Color(0xFF999999), fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDownloads, shape = CircleShape,
              colors = ButtonDefaults.buttonColors(containerColor = DetailBlue), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
              Text("下载队列", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
        Spacer(Modifier.height(8.dp))
      }
    }
  }
  }
}

@Composable
private fun DetailQuickAction(icon: ImageVector, label: String, enabled: Boolean,
  status: MegaLiveFunctionStatus = MegaLiveFunctionStatus.Idle, onClick: () -> Unit) {
  IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
    if (status.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    else Icon(icon, label, modifier = Modifier.size(28.dp), tint = when {
      !enabled -> Color(0xFFCACACA)
      status == MegaLiveFunctionStatus.Error -> Color(0xFFD84949)
      status.running -> DetailBlue
      else -> Color(0xFF555555)
    })
  }
}

@Composable
private fun DetailRecordingCard(title: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().heightIn(min = 78.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
    .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = color, modifier = Modifier.size(23.dp))
    Text(title, Modifier.weight(1f).padding(start = 12.dp), color = DetailInk, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFBBBBBB), modifier = Modifier.size(19.dp))
  }
}
