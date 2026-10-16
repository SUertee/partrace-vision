package com.anker.esiotkit.sample.main.station

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.station.records.StationAlarmMessage
import com.anker.esiotkit.sample.main.station.records.rememberStationNotificationsEnabled

@Composable
fun MessageCenterScreen(
  onBack: () -> Unit,
  onNotificationSettings: () -> Unit,
  onPeopleSettings: () -> Unit = {}
) {
  val notificationsEnabled = rememberStationNotificationsEnabled()
  var showSettings by rememberSaveable { mutableStateOf(false) }
  MessageCenterContent(onBack, onNotificationSettings, { showSettings = true },
    notificationsEnabled = notificationsEnabled)
  if (showSettings) AlertDialog(
    onDismissRequest = { showSettings = false },
    title = { Text("消息设置") },
    text = {
      Column {
        TextButton(onClick = { showSettings = false; onNotificationSettings() }) { Text("系统通知设置") }
        TextButton(onClick = { showSettings = false; onPeopleSettings() }) { Text("人物录像自动解析") }
      }
    },
    confirmButton = { TextButton(onClick = { showSettings = false }) { Text("关闭") } }
  )
}

/** Pure rendering entry for a real alarm source or explicitly labelled design previews. */
@Composable
fun MessageCenterContent(
  onBack: () -> Unit,
  onNotificationSettings: () -> Unit,
  onSettings: () -> Unit,
  notificationsEnabled: Boolean,
  messages: List<StationAlarmMessage> = emptyList(),
  onMessage: (StationAlarmMessage) -> Unit = {},
  showEmptyState: Boolean = true
) {
  Column(Modifier.fillMaxSize().background(StationPageBackground).verticalScroll(rememberScrollState())) {
    StationPageTopBar("消息中心", onBack, StationPageAction.Settings, onSettings)
    if (!notificationsEnabled) {
      Spacer(Modifier.height(24.dp))
      StationNotificationBanner(onNotificationSettings)
      Spacer(Modifier.height(19.dp))
    } else Spacer(Modifier.height(16.dp))
    Text("报警消息", Modifier.padding(start = 32.dp), color = Color.Black, fontSize = 20.sp,
      lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    if (messages.isEmpty() && showEmptyState) {
      Spacer(Modifier.height(if (notificationsEnabled) 235.dp else 138.dp))
      MessageEmptyState(Modifier.align(Alignment.CenterHorizontally))
    } else {
      messages.groupBy { it.monthLabel }.forEach { (month, monthMessages) ->
        Spacer(Modifier.height(22.dp))
        Text(month, Modifier.padding(horizontal = 31.dp), color = Color(0xFF888888), fontSize = 15.sp,
          lineHeight = 21.sp)
        Spacer(Modifier.height(21.dp))
        monthMessages.forEach { message ->
          AlarmMessageCard(message, { onMessage(message) })
          Spacer(Modifier.height(12.dp))
        }
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
private fun AlarmMessageCard(message: StationAlarmMessage, onClick: () -> Unit) {
  Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth()
    .clip(RoundedCornerShape(16.dp)).background(Color.White).clickable(onClick = onClick)
    .padding(start = 11.dp, end = 8.dp, top = 13.dp, bottom = 13.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(60.dp).background(Color(0xFF7B83F6), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
      StationPageIcon(StationPageGlyph.Picture, null, Modifier.size(38.dp), Color.White)
    }
    Spacer(Modifier.width(16.dp))
    Box(Modifier.weight(1f)) {
      Column(Modifier.fillMaxWidth()) {
        Text(message.text, color = Color.Black, fontSize = 18.sp, lineHeight = 24.sp)
        Text(message.dateLabel, color = Color(0xFF888888), fontSize = 15.sp, lineHeight = 21.sp)
      }
      StationPageIcon(StationPageGlyph.Chevron, "查看报警消息", Modifier.size(22.dp).align(Alignment.BottomEnd))
    }
  }
}

@Composable
private fun MessageEmptyState(modifier: Modifier = Modifier) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Canvas(Modifier.size(165.dp).semantics { contentDescription = "暂无信息" }) {
      val grey = Color(0xFFBBBBBB)
      val p = Path().apply {
        moveTo(size.width * .5f, size.height * .045f)
        cubicTo(size.width * .249f, size.height * .045f, size.width * .045f, size.height * .249f, size.width * .045f, size.height * .5f)
        cubicTo(size.width * .045f, size.height * .751f, size.width * .249f, size.height * .955f, size.width * .5f, size.height * .955f)
        lineTo(size.width * .955f, size.height * .955f); lineTo(size.width * .955f, size.height * .5f)
        cubicTo(size.width * .955f, size.height * .249f, size.width * .751f, size.height * .045f, size.width * .5f, size.height * .045f)
        close()
      }
      drawPath(p, grey, style = Stroke(width = 15.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
      drawCircle(grey, radius = 10.dp.toPx(), center = Offset(size.width * .335f, size.height * .445f))
      drawCircle(grey, radius = 10.dp.toPx(), center = Offset(size.width * .665f, size.height * .445f))
      val strokeWidth = 5.dp.toPx()
      drawLine(grey, Offset(size.width * .462f, size.height * .45f), Offset(size.width * .538f, size.height * .528f), strokeWidth)
      drawLine(grey, Offset(size.width * .538f, size.height * .45f), Offset(size.width * .462f, size.height * .528f), strokeWidth)
    }
    Spacer(Modifier.height(17.dp))
    Text("暂 无 信 息", color = Color(0xFFBBBBBB), fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 2.sp,
      fontWeight = FontWeight.Bold)
  }
}
