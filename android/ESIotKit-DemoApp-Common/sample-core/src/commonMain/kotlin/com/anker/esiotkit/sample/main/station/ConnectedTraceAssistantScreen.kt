package com.anker.esiotkit.sample.main.station

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anker.esiotkit.sample.main.station.agent.TraceAgent
import com.anker.esiotkit.sample.main.station.agent.TraceEvidenceImage
import kotlinx.serialization.json.*

private val AssistantBlue = TraceAssistantBlue
private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.objects(key: String) = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
private fun JsonObject.objectValue(key: String) = this[key] as? JsonObject
private fun offsetLabel(value: Long): String = "${value / 60_000}:${(value / 1000 % 60).toString().padStart(2, '0')}"
private fun JsonObject.offset(key: String): String = offsetLabel((this[key] as? JsonPrimitive)?.longOrNull ?: 0)
private fun sourceLabel(source: String) = when (source) {
  "sdk" -> "SDK 原片"
  "mock" -> "模拟数据"
  "manual" -> "人工标注"
  "prerecorded" -> "真实预录"
  else -> source
}

@Composable
fun TraceAssistantScreen(onBack: () -> Unit, openServiceSettings: Boolean = false,
  onServiceSettingsOpened: () -> Unit = {}) {
  val state by TraceAgent.state.collectAsState()
  var question by rememberSaveable { mutableStateOf("") }
  var showSettings by remember { mutableStateOf(false) }
  var showRecordings by remember { mutableStateOf(false) }
  var showHistory by remember { mutableStateOf(false) }
  var showTime by remember { mutableStateOf(false) }
  var showEvents by remember { mutableStateOf(false) }
  var showZones by remember { mutableStateOf(false) }
  val focus = LocalFocusManager.current
  LaunchedEffect(Unit) { TraceAgent.initialize() }
  LaunchedEffect(openServiceSettings) {
    if (openServiceSettings) { showSettings = true; onServiceSettingsOpened() }
  }
  val selected = state.recordings.firstOrNull { it.id == state.selectedRecordingId }
  val running = state.run?.string("status") == "running"
  val inputEnabled = !state.busy && !running
  val hasActivity = state.messages.isNotEmpty() || state.run != null || state.plan != null || state.phase == "planning"
  fun chooseRecording() {
    if (state.configured) showRecordings = true else showSettings = true
  }
  fun chooseZone() {
    when {
      !state.configured -> showSettings = true
      selected == null -> showRecordings = true
      else -> showZones = true
    }
  }
  fun submit() {
    if (question.isBlank() || !inputEnabled) return
    when {
      !state.configured -> showSettings = true
      selected == null -> showRecordings = true
      state.selectedZoneId == null -> showZones = true
      else -> {
        TraceAgent.submit(question.trim())
        question = ""
        focus.clearFocus()
      }
    }
  }

  TraceAssistantLayout(
    question = question,
    onQuestionChange = { question = it },
    inputEnabled = inputEnabled,
    showWelcome = !hasActivity,
    onBack = onBack,
    onHistory = { showHistory = true; if (state.configured) TraceAgent.loadHistory() },
    onSubmit = ::submit,
    onDevice = { if (inputEnabled) chooseRecording() },
    onTime = { showTime = true },
    onEvent = { showEvents = true },
    onZone = { if (inputEnabled) chooseZone() },
    onPrompt = { if (inputEnabled) question = it },
  ) {
      state.messages.forEach { message ->
        key(message.string("message_id")) {
          AssistantSurface {
            Text(if (message.string("role") == "user") "我的问题" else "AI 答复", color = AssistantBlue, fontSize = 12.sp)
            Text(message.string("content"), fontSize = 15.sp, color = Color(0xFF444444))
            if (message.string("run_id").isNotEmpty()) TextButton(
              onClick = { TraceAgent.openRun(message.string("run_id")) }, enabled = !state.busy && !running
            ) { Text("查看本次分析与引用") }
          }
        }
      }
      if (state.hasMoreMessages) TextButton(onClick = TraceAgent::loadMoreMessages, enabled = !state.busy) { Text("加载更多消息") }
      if (state.phase == "plan_ready") state.plan?.let { plan ->
        var queryText by remember(plan) { mutableStateOf(plan.string("query_text")) }
        var stepText by remember(plan) { mutableStateOf((plan["steps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString("\n").orEmpty()) }
        val confirmedSteps = stepText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        AssistantSurface {
          Text("确认分析计划", fontWeight = FontWeight.Bold, color = Color(0xFF333333))
          OutlinedTextField(value = queryText, onValueChange = { queryText = it }, label = { Text("检索问题（可修改）") },
            modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !state.busy)
          plan.objectValue("query")?.let { query ->
            Text("${query.string("camera_id")} / ${query.string("zone_id")} · ${query.offset("start_offset_ms")}—${query.offset("end_offset_ms")}",
              fontSize = 12.sp, color = Color.Gray)
          }
          OutlinedTextField(value = stepText, onValueChange = { stepText = it }, label = { Text("分析步骤（每行一步）") },
            modifier = Modifier.fillMaxWidth(), maxLines = 6, enabled = !state.busy)
          Button(onClick = { TraceAgent.confirmPlan(queryText.trim(), confirmedSteps) },
            enabled = !state.busy && queryText.isNotBlank() && queryText.length <= 2000 && confirmedSteps.isNotEmpty()) { Text("确认并开始分析") }
        }
      }
      state.run?.let { run ->
        AssistantSurface {
          val status = run.string("status")
          Text(when (status) { "running" -> "正在分析"; "completed" -> "分析完成"; "stopped" -> "已停止"; "failed" -> "分析失败"; else -> "分析状态" },
            color = AssistantBlue, fontWeight = FontWeight.Bold)
          run.objectValue("plan")?.objectValue("query")?.let { query ->
            Text("${query.string("recording_id")} · ${query.string("zone_id")} · ${query.offset("start_offset_ms")}—${query.offset("end_offset_ms")}",
              fontSize = 11.sp, color = Color.Gray)
          }
          if (status == "running") {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("完成后会显示结果；离开页面后可回来继续查看。", color = Color.Gray, fontSize = 12.sp)
            TextButton(onClick = TraceAgent::stop, enabled = !state.busy) { Text("停止分析") }
          }
          if (status == "failed") run.objectValue("error")?.let { error ->
            Text(error.string("message"), color = Color(0xFFB54B36), fontSize = 14.sp)
            Text(error.string("code"), color = Color.Gray, fontSize = 11.sp)
          }
          if (status == "stopped") Text("本次运行已停止，未保存 AI 答复。", color = Color.Gray, fontSize = 13.sp)
          val cards = run.objectValue("cards")?.objects("cards").orEmpty()
          if (cards.isEmpty() && run.string("summary").isNotEmpty()) Text(run.string("summary"))
          cards.forEach { card -> AgentResultCard(card) }
          if (cards.isEmpty() && status == "completed") run.objects("references").forEach { EvidenceButton(it) }
        }
      }
      if (state.busy && hasActivity) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(if (state.phase == "planning") "正在生成计划…" else "正在连接服务…", fontSize = 13.sp, color = Color.Gray)
      }
      state.error?.let { error ->
        AssistantSurface {
          Text(error, color = Color(0xFFB54B36), fontSize = 13.sp)
          TextButton(onClick = TraceAgent::retry, enabled = !state.busy) { Text("重试") }
        }
      }
  }

  if (showSettings) AgentSettingsDialog(state.baseUrl, onDismiss = { showSettings = false }) { url, bearer ->
    TraceAgent.configure(url, bearer); showSettings = false
  }
  if (showRecordings) AlertDialog(onDismissRequest = { showRecordings = false }, title = { Text("选择设备录像") },
    text = {
      Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("选择已发布的录像作为本次查询范围。选择后点击发送即可生成分析计划。", fontSize = 13.sp, color = Color.Gray)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.recordings.isEmpty() && !state.busy) Text("暂无已发布录像。人物录像解析完成后，点击刷新。", fontSize = 14.sp)
        state.recordings.forEach { recording ->
          TextButton(enabled = inputEnabled, onClick = { TraceAgent.selectRecording(recording.id); showRecordings = false }) {
            Column(Modifier.fillMaxWidth()) {
              Text("${recording.cameraId} · ${offsetLabel(recording.durationMs)} · ${sourceLabel(recording.sourceMode)}")
              Text(recording.id, fontSize = 11.sp, color = Color.Gray)
              if (recording.id == state.selectedRecordingId) Text("当前已选", color = AssistantBlue, fontSize = 12.sp)
            }
          }
        }
        state.error?.let { Text(it, color = Color(0xFFB54B36), fontSize = 12.sp) }
      }
    }, confirmButton = { TextButton(onClick = { showRecordings = false }) { Text("完成") } },
    dismissButton = { TextButton(onClick = TraceAgent::refreshRecordings, enabled = inputEnabled) { Text("刷新") } })
  if (showTime) AlertDialog(onDismissRequest = { showTime = false }, title = { Text("查询时间") }, text = {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(selected?.let { "当前查询整段录像：0:00—${offsetLabel(it.durationMs)}" } ?: "请先选择设备录像。")
      Text("当前支持在所选整段录像内分析。录像的真实时钟时间尚未校准时，请使用片段内的相对时间描述问题。", fontSize = 13.sp, color = Color.Gray)
    }
  }, confirmButton = { TextButton(onClick = { showTime = false }) { Text("知道了") } },
    dismissButton = { TextButton(onClick = { showTime = false; chooseRecording() }, enabled = inputEnabled) { Text("选择录像") } })
  if (showZones) AlertDialog(onDismissRequest = { showZones = false }, title = { Text("选择区域/货架") }, text = {
    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (selected != null) Text("设备 ${selected.cameraId} 的已配置区域", fontSize = 13.sp, color = Color.Gray)
      if (state.zones.isEmpty()) Text("当前录像没有可查询区域，请刷新录像或核对区域配置。", fontSize = 14.sp)
      state.zones.forEach { zone ->
        TextButton(enabled = inputEnabled, onClick = { TraceAgent.selectZone(zone); showZones = false }) {
          Text(if (zone == state.selectedZoneId) "$zone · 已选" else zone)
        }
      }
      state.error?.let { Text(it, color = Color(0xFFB54B36), fontSize = 12.sp) }
    }
  }, confirmButton = { TextButton(onClick = { showZones = false }) { Text("关闭") } })
  if (showEvents) AlertDialog(onDismissRequest = { showEvents = false }, title = { Text("你想了解什么事件？") }, text = {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf("这段录像里有哪些人物活动？", "这个区域有哪些可核查的事件？", "能给我看看相关关键帧吗？").forEach { prompt ->
        TextButton(enabled = inputEnabled, onClick = { question = prompt; showEvents = false }) { Text(prompt) }
      }
    }
  }, confirmButton = { TextButton(onClick = { showEvents = false }) { Text("关闭") } })
  if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("会话历史") },
    text = {
      Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          TextButton(enabled = inputEnabled, onClick = {
            TraceAgent.newConversation(); question = ""; showHistory = false
          }) { Text("新建会话") }
          TextButton(enabled = !state.busy && (!running || state.error != null), onClick = {
            showHistory = false; showSettings = true
          }) { Text(if (state.configured) "服务设置" else "连接服务") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.conversations.isEmpty() && !state.busy) Text("暂无会话记录", color = Color.Gray, fontSize = 14.sp)
        state.conversations.forEach { conversation ->
          TextButton(enabled = inputEnabled, onClick = {
            TraceAgent.openConversation(conversation.string("conversation_id")); showHistory = false
          }) {
            Column(Modifier.fillMaxWidth()) {
              Text(conversation.string("created_at"), fontSize = 13.sp)
              Text(conversation.string("conversation_id"), fontSize = 10.sp, color = Color.Gray)
            }
          }
        }
        if (state.hasMoreConversations) TextButton(onClick = TraceAgent::loadMoreConversations, enabled = !state.busy) { Text("加载更多会话") }
        state.error?.let {
          Text(it, color = Color(0xFFB54B36), fontSize = 12.sp)
          TextButton(onClick = TraceAgent::retry, enabled = !state.busy) { Text("重试") }
        }
      }
    }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } })
  state.evidence?.let { frame ->
    AlertDialog(onDismissRequest = TraceAgent::closeEvidence, title = { Text("关键帧证据") },
      text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(frame.string("evidence_id"), fontSize = 12.sp)
          Text("事件 ${frame.string("event_id")} · ${frame.offset("offset_ms")}", fontSize = 12.sp, color = Color.Gray)
          state.evidenceBytes?.let { TraceEvidenceImage(it, Modifier.fillMaxWidth().heightIn(max = 300.dp)) }
          if (state.evidenceBytes == null) Text(if (state.busy) "正在读取当前证据…" else "证据图片暂不可用，请核对错误信息。", color = Color.Gray)
          state.error?.let { Text(it, color = Color(0xFFB54B36), fontSize = 12.sp) }
        }
      }, confirmButton = { TextButton(onClick = TraceAgent::closeEvidence) { Text("关闭") } })
  }
}

@Composable
private fun AssistantSurface(content: @Composable ColumnScope.() -> Unit) {
  Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(15.dp))
    .border(1.dp, Color(0xFFE8F0F3), RoundedCornerShape(15.dp)).padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun AgentResultCard(card: JsonObject) {
  Column(Modifier.fillMaxWidth().background(Color(0xFFF5FAFC), RoundedCornerShape(10.dp)).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(7.dp)) {
    Text(card.string("title"), fontWeight = FontWeight.Medium, color = Color(0xFF444444))
    when (card.string("kind")) {
      "run_summary" -> { Text(card.string("summary"), fontSize = 14.sp); card.objects("references").forEach { EvidenceButton(it) } }
      "event_evidence" -> {
        Text("${card.string("event_type")} · ${card.offset("start_offset_ms")}—${card.offset("end_offset_ms")}", fontSize = 13.sp)
        Text("${card.string("observation_status")} · ${card.string("detection_source")}", color = Color.Gray, fontSize = 11.sp)
        card.objects("evidence").forEach { EvidenceButton(it) }
      }
      "coverage_gap" -> {
        Text(when (card.string("gap")) {
          "unindexed" -> "部分事件尚未建立语义索引"
          "no_matches" -> "未找到匹配候选"
          "media_missing" -> "部分关键帧媒体缺失"
          else -> "检索存在缺口"
        }, fontSize = 14.sp)
        Text(card.string("detail"), fontSize = 12.sp, color = Color.Gray)
      }
      "run_status" -> Text(card.string("detail").ifBlank { card.string("status") }, fontSize = 14.sp)
      else -> Text("此卡片类型暂不支持，请查看分析文字。", fontSize = 13.sp, color = Color.Gray)
    }
  }
}

@Composable
private fun EvidenceButton(reference: JsonObject) {
  val id = reference.string("evidence_id")
  if (id.isNotEmpty()) TextButton(onClick = { TraceAgent.openEvidence(id) }) {
    Text("查看关键帧 ${reference.offset("offset_ms")} · $id", fontSize = 12.sp)
  }
}

@Composable
private fun AgentSettingsDialog(initialUrl: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
  var url by remember { mutableStateOf(initialUrl) }
  var token by remember { mutableStateOf("") }
  AlertDialog(onDismissRequest = onDismiss, title = { Text("后端服务") }, text = {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("使用后端 API 地址和独立 Bearer。开发模拟器地址：http://10.0.2.2:8000", fontSize = 12.sp, color = Color.Gray)
      OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("服务地址（不含 /api/v1）") }, singleLine = true)
      OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("后端 Bearer（留空保留现有凭据）") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation())
    }
  }, confirmButton = { TextButton(onClick = { onSave(url.trim(), token.trim()) }) { Text("保存并连接") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
