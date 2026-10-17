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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.station.events.StationEventCamera
import com.anker.esiotkit.sample.main.station.events.StationEventType
import com.anker.esiotkit.sample.main.station.ui.events.StationEventDisplayItem
import com.anker.esiotkit.sample.main.station.ui.events.StationEventsDisplayState
import com.anker.esiotkit.sample.main.station.ui.events.StationEventsLayout
import com.anker.esiotkit.sample.main.station.ui.assistant.TraceAssistantLayout
import com.anker.esiotkit.sample.main.station.ui.assistant.TraceAssistantText
import com.anker.esiotkit.sample.main.station.ui.assistant.TraceRelatedClip
import com.anker.esiotkit.sample.main.station.ui.assistant.TraceToolProgress
import com.anker.esiotkit.sample.main.station.ui.assistant.TraceToolProgressItem
import com.anker.esiotkit.sample.main.station.ui.assistant.TraceToolProgressStatus
import com.anker.esiotkit.sample.main.station.ui.assistant.TraceUserBubble
import com.anker.esiotkit.sample.main.station.ui.events.StationEventDetailDevice
import com.anker.esiotkit.sample.main.station.ui.events.StationEventDetailDisplayState
import com.anker.esiotkit.sample.main.station.ui.events.StationEventDetailItem
import com.anker.esiotkit.sample.main.station.ui.events.StationEventDetailLayout
import com.anker.esiotkit.sample.main.station.ui.screens.MessageCenterContent
import com.anker.esiotkit.sample.main.station.ui.screens.ProfileContent
import com.anker.esiotkit.sample.main.station.ui.screens.ReviewRecordsContent
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
private fun StationProfileDesignPreview() {
  StationDesignPreviewFrame(Color(0xFF9CCFF8), Color(0xFFFAFAFA)) {
    Box(Modifier.fillMaxSize().background(Color(0xFFFAFAFA))) {
      Box(Modifier.fillMaxWidth().height(236.dp).background(Brush.verticalGradient(
        0f to Color(0xFF9CCFF8), 0.42f to Color(0xFF79BDF7), 1f to Color(0xFFFAFAFA))))
      ProfileContent(StationReviewResult.Loaded(listOf(
        StationReviewRecord("design-example", "E3-01.02", "2020-10-17 22:14", "待确认")
      )))
    }
  }
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun TraceAssistantIdlePreview() {
  StationDesignPreviewFrame(Color(0xFFEDFCFE), Color(0xFFEDFCFE)) {
    TraceAssistantLayout("", {}, true, true, {}, {}, {}, {}, {}, {}, {}, {}) {}
  }
}

/** Simulated presentation data only: this preview never calls TraceAgent or the camera SDK. */
@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun TraceAssistantConversationPreview() {
  var expanded by remember { mutableStateOf(true) }
  StationDesignPreviewFrame(Color(0xFFEDFCFE), Color(0xFFEDFCFE)) {
    TraceAssistantLayout(
      question = "", onQuestionChange = {}, inputEnabled = true, showWelcome = false,
      onBack = {}, onHistory = {}, onSubmit = {}, onDevice = {}, onTime = {}, onEvent = {},
      onZone = {}, onPrompt = {},
    ) {
      Text("设计预览·模拟数据", color = Color(0xFF8C692F), fontSize = 12.sp)
      TraceUserBubble("帮我核查这段录像里 E3 区域发生了什么？")
      TraceToolProgress(
        items = listOf(
          TraceToolProgressItem("design-search", "检索范围内的候选事件", TraceToolProgressStatus.Completed),
          TraceToolProgressItem("design-events", "回读两条事件元数据", TraceToolProgressStatus.Completed),
          TraceToolProgressItem("design-evidence", "核查事件关键帧", TraceToolProgressStatus.Unavailable,
            "模拟缺口：关联图片缺失"),
        ),
        expanded = expanded, onExpandedChange = { expanded = it },
      )
      TraceAssistantText("同一摄像头的 E3 区域有两条候选事件。关键帧缺失，当前只能依据事件元数据，无法确认具体动作或连续过程。")
      TraceRelatedClip("片段 1 · 进入区域候选", "入口摄像头 · 0:12—0:20", onClick = {})
      TraceRelatedClip("片段 2 · 区域停留候选", "入口摄像头 · 0:21—0:39", onClick = {})
    }
  }
}

/** One-camera event process; missing media is shown explicitly instead of a fake player. */
@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun StationEventProcessPreview() {
  var selected by remember { mutableStateOf("design_event_1") }
  StationDesignPreviewFrame(Color(0xFFF6F8FB), Color(0xFFF6F8FB)) {
    StationEventDetailLayout(
      state = StationEventDetailDisplayState(
        devices = listOf(StationEventDetailDevice("design_camera_1", "入口摄像头")),
        selectedDeviceId = "design_camera_1",
        items = listOf(
          StationEventDetailItem("design_event_1", "0:12—0:20", "进入 E3 区域候选",
            "候选事件，待人工核查", "关键帧缺失 · 仅事件元数据", "design_camera_1"),
          StationEventDetailItem("design_event_2", "0:21—0:39", "E3 区域停留候选",
            "候选事件，不能据此认定拿取行为", "关键帧缺失 · 仅事件元数据", "design_camera_1"),
        ),
        selectedItemId = selected,
        subtitle = "设计预览·模拟数据 · 同一摄像头录像相对时间",
        coverageNotice = "两条事件均为候选，关联关键帧缺失；不能据此确认具体动作或连续过程。",
      ),
      onBack = {}, onSelectDevice = {}, onSelectItem = { selected = it },
      media = { _, modifier ->
        Box(modifier.background(Color(0xFFE3E8EF)), contentAlignment = androidx.compose.ui.Alignment.Center) {
          Text("设计预览，无真实媒体", color = Color(0xFF7B8795), fontSize = 14.sp)
        }
      },
    )
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

/** These records exist only in Compose previews and never initialize the SDK. */
@Composable
private fun StationEventsDesignPreviewContent(state: StationEventsDisplayState) {
  StationDesignPreviewFrame(Color(0xFF9CCFF8), Color(0xFFFAFAFA)) {
    Box(Modifier.fillMaxSize().background(Color(0xFFFAFAFA))) {
      Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(
        0f to Color(0xFF9CCFF8), .42f to Color(0xFF79BDF7), 1f to Color(0xFFFAFAFA))))
      StationEventsLayout(
        dateLabel = "2026年10月17日",
        cameras = listOf(
          StationEventCamera("design_camera", "design_station", "入口摄像头"),
          StationEventCamera("design_camera_2", "design_station", "货架摄像头")
        ),
        selectedCameraSn = null,
        selectedType = StationEventType.All,
        state = state,
        onSelectDate = {}, onSelectCamera = {}, onSelectType = {}, onRefresh = {}, onLogin = {},
        onTab = {}, onEvent = {},
        thumbnail = { _, modifier ->
          Box(modifier.background(Color(0xFFDDE3E9)), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.Text("预览缩略图", color = Color(0xFF7D8792), fontSize = 11.sp)
          }
        },
        previewLabel = "设计预览·模拟事件"
      )
    }
  }
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun StationEventsListDesignPreview() {
  StationEventsDesignPreviewContent(StationEventsDisplayState(events = listOf(
    StationEventDisplayItem("design_event_1", "入口摄像头", "14:32:58", "人物", "00:24", true),
    StationEventDisplayItem("design_event_2", "货架摄像头", "14:20:16", "移动", "00:18", true),
    StationEventDisplayItem("design_event_3", "入口摄像头", "13:48:05", "包裹", "00:30", false)
  )))
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun StationEventsEmptyDesignPreview() {
  StationEventsDesignPreviewContent(StationEventsDisplayState())
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun StationEventsFailureDesignPreview() {
  StationEventsDesignPreviewContent(StationEventsDisplayState(errorMessage = "摄像头事件读取失败，请重试。"))
}

@Preview(widthDp = 412, heightDp = 917)
@Composable
private fun StationEventsLoadingDesignPreview() {
  StationEventsDesignPreviewContent(StationEventsDisplayState(loading = true))
}
