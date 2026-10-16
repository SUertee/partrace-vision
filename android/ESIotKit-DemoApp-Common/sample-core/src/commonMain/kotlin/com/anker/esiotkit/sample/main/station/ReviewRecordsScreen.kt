package com.anker.esiotkit.sample.main.station

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.station.records.*

@Composable
fun ReviewRecordsScreen(onBack: () -> Unit, onNotificationSettings: () -> Unit,
  onConfigureService: () -> Unit = {}) {
  val source = rememberStationReviewSource()
  val notificationsEnabled = rememberStationNotificationsEnabled()
  var result by remember { mutableStateOf<StationReviewResult?>(null) }
  var refresh by remember { mutableStateOf(0) }
  var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
  var detail by remember { mutableStateOf<StationReviewDetailResult?>(null) }
  var detailRefresh by remember { mutableStateOf(0) }
  LaunchedEffect(source, refresh) { result = null; result = source.load() }
  LaunchedEffect(source, selectedId, detailRefresh) {
    detail = null
    selectedId?.let { detail = source.detail(it) }
  }
  ReviewRecordsContent(onBack, onNotificationSettings, notificationsEnabled, result,
    onRetry = { refresh++ }, onConfigureService = onConfigureService, onReview = { selectedId = it.id })
  if (selectedId != null) AlertDialog(
    onDismissRequest = { selectedId = null }, title = { Text("审核详情") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        when (val value = detail) {
          null -> { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.height(12.dp)); Text("正在读取审核记录…") }
          is StationReviewDetailResult.Failed -> {
            Text(value.message)
            TextButton(onClick = { detailRefresh++ }) { Text("重试") }
          }
          is StationReviewDetailResult.Loaded -> {
            val record = value.detail.review
            Text(record.eventId, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            Text("时间 ${record.createdAtLabel}")
            Text("核查结论：${record.decisionLabel}")
            Text("核查人：${record.reviewerId}")
            if (record.recordingId.isNotBlank()) Text("录像：${record.recordingId}")
            if (record.zoneId.isNotBlank()) Text("区域：${record.zoneId}")
            record.note?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(12.dp)); Text(it) }
            Spacer(Modifier.height(12.dp))
            Text("记录编号：${record.id}", color = Color.Gray, fontSize = 12.sp)
            if (value.detail.evidenceIds.isNotEmpty()) Text("关联证据：${value.detail.evidenceIds.joinToString("、")}", fontSize = 12.sp)
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = { selectedId = null }) { Text("关闭") } }
  )
}

/** The screenshot examples are only passed from previews; production receives server records. */
@Composable
fun ReviewRecordsContent(
  onBack: () -> Unit,
  onNotificationSettings: () -> Unit,
  notificationsEnabled: Boolean,
  result: StationReviewResult?,
  onRetry: () -> Unit = {},
  onConfigureService: () -> Unit = {},
  onReview: (StationReviewRecord) -> Unit = {}
) {
  var searchVisible by rememberSaveable { mutableStateOf(false) }
  var search by rememberSaveable { mutableStateOf("") }
  Column(Modifier.fillMaxSize().background(StationPageBackground).verticalScroll(rememberScrollState())) {
    StationPageTopBar("审核记录", onBack, StationPageAction.Search, { searchVisible = !searchVisible })
    if (!notificationsEnabled) {
      Spacer(Modifier.height(24.dp))
      StationNotificationBanner(onNotificationSettings)
      Spacer(Modifier.height(19.dp))
    } else Spacer(Modifier.height(16.dp))
    Text("全部", Modifier.padding(start = 32.dp), color = Color.Black, fontSize = 20.sp,
      lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    if (searchVisible) {
      OutlinedTextField(search, { search = it }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        singleLine = true, placeholder = { Text("搜索事件编号、备注或时间") }, shape = RoundedCornerShape(16.dp),
        trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("清除") } })
      Spacer(Modifier.height(12.dp))
    }
    when (result) {
      null -> ReviewStatus("正在读取审核记录…", loading = true)
      StationReviewResult.NotConfigured -> ReviewStatus("尚未配置后端服务", "连接服务后可读取已保存的审核记录。", "服务设置", onConfigureService)
      is StationReviewResult.Failed -> ReviewStatus("暂时无法读取审核记录", result.message, "重试", onRetry)
      is StationReviewResult.Loaded -> {
        val query = search.trim()
        val reviews = result.reviews.filter { query.isEmpty() || listOf(it.eventId, it.id, it.note.orEmpty(), it.createdAtLabel)
          .any { value -> value.contains(query, ignoreCase = true) } }
        if (reviews.isEmpty()) ReviewStatus(if (query.isEmpty()) "暂无审核记录" else "没有找到匹配的审核记录",
          if (query.isEmpty()) "人工核查保存后，记录会显示在这里。" else "试试其他事件编号、备注或时间。")
        reviews.forEach { record ->
          ReviewRecordCard(record, { onReview(record) })
          Spacer(Modifier.height(12.dp))
        }
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
private fun ReviewRecordCard(record: StationReviewRecord, onClick: () -> Unit) {
  Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth()
    .clip(RoundedCornerShape(16.dp)).background(Color.White).clickable(onClick = onClick)
    .padding(start = 20.dp, end = 20.dp, top = 15.dp, bottom = 20.dp)) {
    Text(record.eventId, color = Color.Black, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
    Text("时间 ${record.createdAtLabel}", color = Color.Black, fontSize = 18.sp, lineHeight = 26.sp)
    Spacer(Modifier.height(13.dp))
    Text("已保存", modifier = Modifier.background(Color(0xFFD8DAFF), RoundedCornerShape(15.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
      color = Color(0xFF7B83F6), fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun ReviewStatus(title: String, description: String? = null, action: String? = null,
  onAction: () -> Unit = {}, loading: Boolean = false) {
  Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()
    .background(Color.White, RoundedCornerShape(16.dp)).padding(20.dp)) {
    if (loading) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.height(12.dp)) }
    Text(title, fontSize = 16.sp, color = StationPageInk, fontWeight = FontWeight.Medium)
    description?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Color.Gray, fontSize = 14.sp) }
    action?.let { TextButton(onClick = onAction) { Text(it) } }
  }
}
