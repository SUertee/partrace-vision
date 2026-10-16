package com.anker.esiotkit.sample.main.station

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.station.records.*
import androidx.compose.ui.tooling.preview.Preview

/** Only previews reserve a neutral system area; production uses real WindowInsets. */
@Composable
internal fun StationDesignPreviewFrame(
  topColor: Color = Color(0xFFF6F6F6),
  bottomColor: Color = Color(0xFFF6F6F6),
  contentHandlesSystemBars: Boolean = false,
  content: @Composable () -> Unit
) {
  val padding = if (contentHandlesSystemBars && WindowInsets.systemBars.getTop(LocalDensity.current) > 0) 0.dp else 24.dp
  MaterialTheme {
    Box(Modifier.fillMaxSize().background(bottomColor)) {
      Box(Modifier.fillMaxWidth().height(24.dp).background(topColor))
      Box(Modifier.fillMaxSize().padding(vertical = padding)) { content() }
    }
  }
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun StationHomeDesignPreview() {
  StationDesignPreviewFrame(Color(0xFF9CCFF8), Color(0xFFFAFAFA), contentHandlesSystemBars = true) {
    StationApp(
      deviceState = StationDeviceState(devices = listOf(
        StationDeviceCard("design_camera_1", "深圳", true, false),
        StationDeviceCard("design_camera_2", "深圳", true, false),
      )),
      onRefreshDevices = {}, onOpenDevice = {}, onDeviceSettings = {},
      devicePreview = { _, modifier -> Box(modifier.background(Color(0xFF333333))) },
      onSdkTools = {}, onNotificationSettings = {}, nowMillis = { 0L },
    )
  }
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun MessageEmptyDesignPreview() {
  StationDesignPreviewFrame { MessageCenterContent({}, {}, {}, notificationsEnabled = false) }
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun MessageAlarmDesignPreview() {
  StationDesignPreviewFrame {
  MessageCenterContent({}, {}, {}, notificationsEnabled = true, messages = listOf(
    StationAlarmMessage("design-example", "10月", "10.17", "14:32:58–14:33:10 被货架遮挡，前后片段连续性待人工核查")
  ))
  }
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun MessageBlankDesignPreview() {
  StationDesignPreviewFrame { MessageCenterContent({}, {}, {}, notificationsEnabled = false, showEmptyState = false) }
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun ReviewRecordsDesignPreview() {
  StationDesignPreviewFrame {
  ReviewRecordsContent({}, {}, false, StationReviewResult.Loaded(listOf(
    StationReviewRecord("design-example", "E3-01.02", "2020-10-17 22:14", "待确认")
  )))
  }
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun TraceAssistantIdlePreview() {
  StationDesignPreviewFrame(Color(0xFFEDFCFE), Color(0xFFEDFCFE)) {
    TraceAssistantLayout("", {}, true, true, {}, {}, {}, {}, {}, {}, {}, {}) {}
  }
}

@Preview(widthDp = 412, heightDp = 906)
@Composable
private fun DeviceLiveDetailDesignPreview() {
  StationDesignPreviewFrame(Color.White, Color(0xFFF6F6F6), contentHandlesSystemBars = true) {
    com.anker.esiotkit.sample.main.mega.live_video.DeviceLiveDetailLayout(
      name = "基地外宣传栏",
      state = com.anker.esiotkit.sample.main.mega.live_video.MegaLiveStreamUiState(),
      fullScreen = false,
      onBack = {}, onSettings = {}, onMore = {}, onPTZ = {}, onTalk = {}, onRecord = {},
      onCapture = {}, onFullScreen = {}, onEvents = {}, onPlayback = {}, onRecordings = {}, onDownloads = {},
      player = {
        androidx.compose.foundation.layout.Column(
          Modifier.fillMaxWidth().height(232.dp).background(Color(0xFF474747)),
          horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
          verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
          androidx.compose.material3.Text("Offline", color = Color.White, fontSize = 17.sp)
          androidx.compose.foundation.layout.Spacer(Modifier.height(5.dp))
          androidx.compose.material3.Text("09-17 17:19:39", color = Color.White, fontSize = 17.sp)
        }
      },
    )
  }
}
