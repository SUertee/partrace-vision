package com.anker.esiotkit.sample.main.station.ui.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.station.ui.components.StationIcon
import com.anker.esiotkit.sample.main.station.ui.components.StationSymbol

private val ConversationInk = Color(0xFF39434F)
private val ConversationMuted = Color(0xFF7E8793)
private val ConversationLavender = Color(0xFFF1EFFF)
private val ConversationPurple = Color(0xFF8276CA)

/** These are reported states, not stages inferred from elapsed time or a plan. */
internal enum class TraceToolProgressStatus { Pending, Running, Completed, Failed, Unavailable, Cancelled }

internal data class TraceToolProgressItem(
  val id: String,
  val title: String,
  val status: TraceToolProgressStatus,
  val detail: String? = null,
)

@Composable
internal fun TraceUserBubble(text: String, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.End) {
    Text(text, color = Color(0xFFFDFEFF), fontSize = 16.sp, lineHeight = 25.sp,
      modifier = Modifier.widthIn(max = 320.dp)
        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 5.dp))
        .background(TraceAssistantBlue).padding(horizontal = 17.dp, vertical = 13.dp))
  }
}

@Composable
internal fun TraceAssistantText(text: String, modifier: Modifier = Modifier) {
  Text(text, modifier = modifier.fillMaxWidth(), color = ConversationInk, fontSize = 16.sp, lineHeight = 27.sp)
}

/** Expansion and every step state are owned by the host; this component never creates activity. */
@Composable
internal fun TraceToolProgress(
  items: List<TraceToolProgressItem>,
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  title: String = "核查进度",
) {
  Column(modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
      .background(ConversationLavender)
      .clickable(role = Role.Button, onClickLabel = if (expanded) "收起核查进度" else "展开核查进度") {
        onExpandedChange(!expanded)
      }.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(title, color = ConversationPurple, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.weight(1f))
      StationIcon(StationSymbol.Chevron, modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
        color = ConversationPurple)
    }
    if (expanded) {
      Spacer(Modifier.height(15.dp))
      if (items.isEmpty()) Text("暂无进度记录", color = ConversationMuted, fontSize = 13.sp,
        modifier = Modifier.padding(start = 13.dp, bottom = 8.dp))
      items.forEachIndexed { index, item ->
        TraceProgressStep(item, index == items.lastIndex)
      }
    }
  }
}

@Composable
private fun TraceProgressStep(item: TraceToolProgressItem, last: Boolean) {
  val (label, color) = when (item.status) {
    TraceToolProgressStatus.Pending -> "待执行" to ConversationMuted
    TraceToolProgressStatus.Running -> "进行中" to TraceAssistantBlue
    TraceToolProgressStatus.Completed -> "已完成" to ConversationInk
    TraceToolProgressStatus.Failed -> "未完成" to Color(0xFFB65242)
    TraceToolProgressStatus.Unavailable -> "不可用" to Color(0xFF9A762F)
    TraceToolProgressStatus.Cancelled -> "已取消" to ConversationMuted
  }
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    Canvas(Modifier.width(24.dp).fillMaxHeight()) {
      val center = Offset(size.width / 2f, 10.dp.toPx())
      if (!last) drawLine(Color(0xFFDDE0E7), center, Offset(center.x, size.height), 1.dp.toPx())
      drawCircle(Color(0xFFFCFCFE), 5.dp.toPx(), center)
      if (item.status == TraceToolProgressStatus.Completed) {
        drawCircle(TraceAssistantBlue, 4.dp.toPx(), center)
      } else {
        drawCircle(color, 4.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
        if (item.status == TraceToolProgressStatus.Running) drawCircle(color, 1.5.dp.toPx(), center)
      }
    }
    Column(Modifier.weight(1f).padding(start = 4.dp, bottom = if (last) 3.dp else 18.dp)) {
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.title, color = ConversationInk, fontSize = 14.sp, lineHeight = 21.sp,
          modifier = Modifier.weight(1f))
        Text(label, color = color, fontSize = 11.sp, lineHeight = 21.sp)
      }
      item.detail?.takeIf { it.isNotBlank() }?.let {
        Text(it, color = ConversationMuted, fontSize = 12.sp, lineHeight = 19.sp,
          modifier = Modifier.padding(top = 3.dp))
      }
    }
  }
}

@Composable
internal fun TraceRelatedClip(
  title: String,
  subtitle: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(15.dp))
    .background(ConversationLavender).alpha(if (enabled) 1f else 0.55f)
    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    .padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, color = ConversationInk, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
      if (subtitle.isNotBlank()) Text(subtitle, color = ConversationMuted, fontSize = 12.sp, lineHeight = 19.sp)
    }
    Spacer(Modifier.width(14.dp))
    Canvas(Modifier.size(18.dp).semantics { contentDescription = "打开相关片段" }) {
      drawLine(ConversationPurple, Offset(size.width * 0.24f, size.height * 0.12f),
        Offset(size.width * 0.67f, size.height * 0.5f), 1.8.dp.toPx(), StrokeCap.Round)
      drawLine(ConversationPurple, Offset(size.width * 0.67f, size.height * 0.5f),
        Offset(size.width * 0.24f, size.height * 0.88f), 1.8.dp.toPx(), StrokeCap.Round)
    }
  }
}
