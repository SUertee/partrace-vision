package com.anker.esiotkit.sample.main.station.ui.events

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.station.ui.components.StationIcon
import com.anker.esiotkit.sample.main.station.ui.components.StationSymbol

internal data class StationEventDetailDevice(val id: String, val label: String)

/** Labels and identifiers only. Playback and evidence authorization stay with the host. */
internal data class StationEventDetailItem(
  val id: String,
  val timeLabel: String,
  val title: String,
  val detail: String = "",
  val mediaLabel: String? = null,
  val deviceId: String? = null,
)

internal data class StationEventDetailDisplayState(
  val devices: List<StationEventDetailDevice> = emptyList(),
  val selectedDeviceId: String? = null,
  // The host supplies the items for the selected device; the layout does not merge cameras.
  val items: List<StationEventDetailItem> = emptyList(),
  val selectedItemId: String? = null,
  val title: String = "事件过程",
  val subtitle: String? = null,
  val coverageNotice: String? = null,
  val loading: Boolean = false,
  val errorMessage: String? = null,
  val emptyMessage: String = "当前设备暂无可查看的事件片段。",
)

private val DetailBlue = Color(0xFF4AA8FF)
private val DetailInk = Color(0xFF39434F)
private val DetailMuted = Color(0xFF84909D)
private val DetailBackground = Color(0xFFF6F8FB)
private val DetailSurface = Color(0xFFFDFEFF)

/** Pure layout: the media slot must show the real selected item, or its actual unavailable state. */
@Composable
internal fun StationEventDetailLayout(
  state: StationEventDetailDisplayState,
  onBack: () -> Unit,
  onSelectDevice: (String) -> Unit,
  onSelectItem: (String) -> Unit,
  media: @Composable (StationEventDetailItem, Modifier) -> Unit,
  onRetry: (() -> Unit)? = null,
  onDownload: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val selected = state.items.firstOrNull { it.id == state.selectedItemId }
    ?: state.items.firstOrNull().takeIf { state.selectedItemId == null }
  Column(modifier.fillMaxSize().background(DetailBackground).navigationBarsPadding()) {
    EventDetailHeader(state.title, onBack, onDownload)
    LazyColumn(Modifier.weight(1f).fillMaxWidth(),
      contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)) {
      state.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle -> item("subtitle") {
        Text(subtitle, color = DetailMuted, fontSize = 13.sp, lineHeight = 20.sp)
      } }
      state.coverageNotice?.takeIf { it.isNotBlank() }?.let { notice -> item("coverage") {
        Text(notice, color = Color(0xFF8B681D), fontSize = 13.sp, lineHeight = 21.sp,
          modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFF3CC)).padding(horizontal = 14.dp, vertical = 12.dp))
      } }
      if (state.devices.isNotEmpty()) item("devices") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          state.devices.forEach { device ->
            val active = device.id == state.selectedDeviceId
            Text(device.label, color = if (active) DetailBlue else DetailMuted,
              fontSize = 15.sp, lineHeight = 22.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
              modifier = Modifier.clip(RoundedCornerShape(12.dp))
                .background(if (active) Color(0xFFE7F3FF) else DetailSurface)
                .selectable(selected = active, role = Role.Tab, onClick = { onSelectDevice(device.id) })
                .heightIn(min = 44.dp).padding(horizontal = 17.dp, vertical = 11.dp))
          }
        }
      }
      when {
        state.items.isEmpty() && state.loading -> item("loading") {
          EventDetailNotice("正在读取事件过程", "设备片段就绪后会显示在这里。", loading = true)
        }
        state.items.isEmpty() && state.errorMessage != null -> item("error") {
          EventDetailNotice("事件过程加载失败", state.errorMessage, onRetry = onRetry)
        }
        state.items.isEmpty() -> item("empty") {
          EventDetailNotice("暂无事件片段", state.emptyMessage, onRetry = onRetry)
        }
        else -> {
          if (state.loading) item("refreshing") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              CircularProgressIndicator(Modifier.size(16.dp), color = DetailBlue, strokeWidth = 2.dp)
              Text("正在更新事件过程…", color = DetailMuted, fontSize = 12.sp)
            }
          }
          state.errorMessage?.let { message -> item("refresh-error") {
            EventDetailNotice("事件更新失败", message, onRetry = onRetry)
          } }
          item("media") {
            if (selected == null) EventDetailNotice("所选片段已不可用", "请重新选择下方片段，或刷新事件。", onRetry = onRetry)
            else EventDetailMedia(selected, state.items, onSelectItem, media)
          }
          item("timeline-title") {
            Text("事件时间线", color = DetailInk, fontSize = 17.sp, lineHeight = 26.sp,
              fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
          }
          items(state.items, key = { "event:${it.id}" }) { item ->
            EventDetailTimelineItem(item, selected?.id == item.id, item.id == state.items.last().id) {
              onSelectItem(item.id)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun EventDetailHeader(title: String, onBack: () -> Unit, onDownload: (() -> Unit)?) {
  Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) {
      StationIcon(StationSymbol.Back, modifier = Modifier.size(23.dp), color = DetailInk)
    }
    Text(title, color = DetailInk, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
      modifier = Modifier.weight(1f).padding(start = 5.dp).semantics { heading() })
    if (onDownload != null) IconButton(onClick = onDownload,
      modifier = Modifier.semantics { contentDescription = "下载当前片段" }) {
      Canvas(Modifier.size(24.dp)) {
        val stroke = 1.8.dp.toPx()
        drawLine(DetailBlue, Offset(size.width * 0.5f, size.height * 0.12f), Offset(size.width * 0.5f, size.height * 0.65f), stroke, StrokeCap.Round)
        drawLine(DetailBlue, Offset(size.width * 0.3f, size.height * 0.45f), Offset(size.width * 0.5f, size.height * 0.65f), stroke, StrokeCap.Round)
        drawLine(DetailBlue, Offset(size.width * 0.5f, size.height * 0.65f), Offset(size.width * 0.7f, size.height * 0.45f), stroke, StrokeCap.Round)
        drawLine(DetailBlue, Offset(size.width * 0.2f, size.height * 0.76f), Offset(size.width * 0.2f, size.height * 0.88f), stroke, StrokeCap.Round)
        drawLine(DetailBlue, Offset(size.width * 0.2f, size.height * 0.88f), Offset(size.width * 0.8f, size.height * 0.88f), stroke, StrokeCap.Round)
        drawLine(DetailBlue, Offset(size.width * 0.8f, size.height * 0.88f), Offset(size.width * 0.8f, size.height * 0.76f), stroke, StrokeCap.Round)
      }
    }
  }
}

@Composable
private fun EventDetailMedia(
  selected: StationEventDetailItem,
  items: List<StationEventDetailItem>,
  onSelectItem: (String) -> Unit,
  media: @Composable (StationEventDetailItem, Modifier) -> Unit,
) {
  val index = items.indexOfFirst { it.id == selected.id }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp))
      .background(Color(0xFFE8EDF3)), contentAlignment = Alignment.Center) {
      media(selected, Modifier.fillMaxSize())
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(selected.mediaLabel.orEmpty(), color = DetailMuted, fontSize = 12.sp, lineHeight = 19.sp,
        modifier = Modifier.weight(1f))
      IconButton(onClick = { onSelectItem(items[index - 1].id) }, enabled = index > 0,
        modifier = Modifier.size(44.dp).semantics { contentDescription = "上一个片段" }) {
        StationIcon(StationSymbol.Back, modifier = Modifier.size(14.dp), color = if (index > 0) DetailBlue else Color(0xFFC4CDD6))
      }
      Text("${index + 1} / ${items.size}", color = DetailMuted, fontSize = 12.sp)
      IconButton(onClick = { onSelectItem(items[index + 1].id) }, enabled = index < items.lastIndex,
        modifier = Modifier.size(44.dp).semantics { contentDescription = "下一个片段" }) {
        StationIcon(StationSymbol.Back, modifier = Modifier.size(14.dp).rotate(180f),
          color = if (index < items.lastIndex) DetailBlue else Color(0xFFC4CDD6))
      }
    }
  }
}

@Composable
private fun EventDetailTimelineItem(item: StationEventDetailItem, selected: Boolean, last: Boolean, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(12.dp))
    .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    .padding(horizontal = 10.dp, vertical = 8.dp)) {
    Column(Modifier.width(21.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
      Spacer(Modifier.height(5.dp))
      Box(Modifier.size(if (selected) 11.dp else 8.dp).background(if (selected) DetailBlue else Color(0xFFC8D0DA), CircleShape))
      if (!last) Box(Modifier.padding(top = 5.dp).width(1.dp).weight(1f).background(Color(0xFFDDE3EB)))
    }
    Column(Modifier.weight(1f).heightIn(min = 52.dp).padding(start = 10.dp, bottom = 7.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(item.timeLabel, color = if (selected) DetailBlue else DetailMuted, fontSize = 13.sp, lineHeight = 19.sp)
      Text(item.title, color = if (selected) DetailBlue else DetailInk, fontSize = 15.sp, lineHeight = 23.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
      if (item.detail.isNotBlank()) Text(item.detail, color = DetailMuted, fontSize = 13.sp, lineHeight = 21.sp)
    }
  }
}

@Composable
private fun EventDetailNotice(title: String, message: String, loading: Boolean = false, onRetry: (() -> Unit)? = null) {
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DetailSurface).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)) {
    if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = DetailBlue, strokeWidth = 2.dp)
    Text(title, color = DetailInk, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    Text(message, color = DetailMuted, fontSize = 13.sp, lineHeight = 21.sp)
    if (onRetry != null && !loading) TextButton(onClick = onRetry) { Text("重新加载", color = DetailBlue) }
  }
}
