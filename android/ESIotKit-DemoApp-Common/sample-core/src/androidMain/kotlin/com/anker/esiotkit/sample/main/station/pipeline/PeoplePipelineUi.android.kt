package com.anker.esiotkit.sample.main.station.pipeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.anker.esiotkit.core.constants.isStation
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.media.event_stream.EventMediaPlayer
import com.anker.esiotkit.sample.view.EventStreamPlayView
import com.anker.esiotkit.sample.view.emitToast
import kotlinx.coroutines.launch

@Composable
actual fun PeoplePipelineHost(sdkAuthenticated: Boolean) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer); PeoplePipeline.setActive(false, false) }
  }
  LaunchedEffect(started, sdkAuthenticated) { PeoplePipeline.setActive(started, sdkAuthenticated) }
  // This subscriber is a concrete completion callback. The durable StateFlow also recovers after restart.
  LaunchedEffect(Unit) {
    PeoplePipeline.completions.collect { completed ->
      emitToast(if (completed.eventCount == 0 && completed.evidenceCount == 0) "录像已完成解析"
        else "录像解析完成：${completed.eventCount} 个区域事件，${completed.evidenceCount} 张证据。")
    }
  }
  val request by PeoplePipeline.playerRequest.collectAsState()
  request?.let { download ->
    key(download.taskId) {
      val player = remember { EventMediaPlayer(download.event, download.device).also { download.createdPlayer = it } }
      EventStreamPlayView(Modifier.size(1.dp), player, onVideoViewReady = {
        if (!download.ready.isCompleted) download.ready.complete(player)
      })
    }
  }
}

@Composable
internal actual fun PeoplePipelineSettingsContent(onBack: () -> Unit) {
  val state by PeoplePipeline.state.collectAsState()
  val devices by MegaDeviceManager.deviceList.collectAsState()
  val scope = rememberCoroutineScope()
  var baseUrl by remember { mutableStateOf("") }
  var token by remember { mutableStateOf("") }
  var configVersion by remember { mutableStateOf("") }
  var enabled by remember { mutableStateOf(false) }
  var loaded by remember { mutableStateOf(false) }
  var saving by remember { mutableStateOf(false) }
  var feedback by remember { mutableStateOf<String?>(null) }
  val mapping = remember { mutableStateMapOf<String, String>() }
  LaunchedEffect(Unit) {
    val settings = PeoplePipeline.settings()
    baseUrl = settings.baseUrl
    token = settings.bearerToken
    configVersion = settings.configVersion
    enabled = settings.enabled
    mapping.putAll(settings.cameraMapping)
    loaded = true
  }
  Column(Modifier.fillMaxSize().background(Color(0xFFF6F6F6)).systemBarsPadding()
    .imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = onBack) { Text("返回") }
      Text("人物录像自动解析", fontSize = 21.sp)
    }
    Text("仅在 App 前台且 SDK 已登录时运行。任务与已下载录像保存在应用私有目录，回到前台后恢复。当前不提供系统后台常驻。", fontSize = 14.sp)
    Text("原片先入库为待解析录像，随后交给 Vision；解析完成后，App 回读持久状态并发出完成提示。", fontSize = 13.sp, color = Color(0xFF666666))
    Text("首次启用记录时间，只处理启用后新发现的 HUMAN 人物录像，不补传全历史；FACE 单独出现不会触发上传。", fontSize = 13.sp, color = Color(0xFF666666))
    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("后端服务地址（不含 /api/v1）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = loaded && !saving)
    OutlinedTextField(token, { token = it }, label = { Text("独立的后端 Bearer Token") }, singleLine = true,
      visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = loaded && !saving)
    Text("凭据只保存到本机应用私有且不备份的目录。这里使用 ParTrace 后端凭据，不能填写摄像头 SDK Token、数据库密码或模型密钥。", fontSize = 12.sp, color = Color(0xFF666666))
    OutlinedTextField(configVersion, { configVersion = it }, label = { Text("后端 config_version") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = loaded && !saving)
    Text("SDK 摄像头 → 后端 camera_id", fontSize = 17.sp)
    val cameras = devices.filter { !it.deviceSn.isStation() }
    if (cameras.isEmpty()) Text("请先登录 SDK 并加载设备。已有映射仍会保留。", color = Color.Gray)
    val visibleSns = (cameras.map { it.deviceSn } + mapping.keys).distinct()
    visibleSns.forEach { sn ->
      val device = cameras.firstOrNull { it.deviceSn == sn }
      Text(device?.iotDevice?.deviceName?.ifBlank { sn } ?: "已保存摄像头", fontSize = 14.sp)
      OutlinedTextField(mapping[sn].orEmpty(), { mapping[sn] = it }, label = { Text("camera_id · $sn") },
        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = loaded && !saving)
      TextButton(onClick = {
        saving = true
        scope.launch {
          try { feedback = PeoplePipeline.importLatestPeople(sn) }
          catch (_: Exception) { feedback = "历史人物事件查询失败，请检查摄像头连接后重试。" }
          finally { saving = false }
        }
      }, enabled = loaded && !saving && state.configured && state.enabled) { Text("手动处理最近未导入的人物录像") }
    }
    Text("camera_id 必须已存在于所选后端配置并已授权；留空的设备不上传。HTTPS 可用，开发 HTTP 仅允许 10.0.2.2 / 本机回环。", fontSize = 12.sp, color = Color.Gray)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("启用自动下载、上传与状态回读", modifier = Modifier.weight(1f))
      Switch(enabled, onCheckedChange = { enabled = it }, enabled = loaded && !saving)
    }
    Button(onClick = {
      saving = true
      feedback = null
      scope.launch {
        try {
          feedback = PeoplePipeline.saveSettings(PeopleSettings(baseUrl.trim(), token.trim(), configVersion.trim(),
            mapping.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }, enabled))
            ?: "配置已保存。原片入库待解析与 Vision 解析完成会分别显示。"
        } catch (_: Exception) { feedback = "配置保存失败，请检查本地存储。" }
        finally { saving = false }
      }
    }, enabled = loaded && !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "保存中…" else "保存配置") }
    feedback?.let { Text(it, color = Color(0xFF356890)) }
    state.error?.let { Text(it, color = Color(0xFFB54B36)) }
    HorizontalDivider()
    Text("持久任务 · ${state.tasks.size} / 200", fontSize = 18.sp)
    Text("单路下载；下载与上传最多 5 次自动重试；单文件上限 64 MiB。原片入库后的状态回读会持续退避重试，最长间隔 2 分钟。服务端解析失败是终态，需要检查 Vision 服务。SDK 时间只作原始元数据，未声明 UTC 校准。", fontSize = 12.sp, color = Color.Gray)
    Text("手动入口仅用于最近 24 小时的真实历史录像验证，任务会标记“历史手动”。自动任务仍只处理启用后的新事件。", fontSize = 12.sp, color = Color.Gray)
    if (state.tasks.isEmpty()) Text("暂无新人物录像任务", color = Color.Gray)
    state.tasks.forEach { task ->
      Column(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${task.cameraId} · ${task.label}")
        Text(peopleStatusText(task.status), color = Color(0xFF356890))
        if (task.status == "completed") Text("${task.eventCount} 个事件 · ${task.evidenceCount} 张证据")
        task.error?.let { Text(it, color = Color(0xFFB54B36), fontSize = 12.sp) }
        Text("任务 ${task.jobId.take(12)}", color = Color.Gray, fontSize = 11.sp)
        if (task.retryable || task.redownloadable) TextButton(onClick = { PeoplePipeline.retry(task.jobId) }) {
          Text(if (task.redownloadable) "重新下载原片" else "重试")
        }
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}

private fun peopleStatusText(status: String): String = when (status) {
  "discovered" -> "已发现人物事件 · 等待完整录像"
  "waiting_clip" -> "等待摄像头完成录像 · 自动检查"
  "downloading" -> "正在下载完整录像"
  "ready" -> "录像已就绪 · 等待原片入库"
  "uploading" -> "正在上传原片"
  "retry_wait" -> "失败后等待重试"
  "queued" -> "原片已入库 · 待解析"
  "processing" -> "Vision 正在解析原片"
  "completed" -> "解析完成 · 事实已发布"
  "failed" -> "任务失败"
  "paused_config" -> "配置已变更 · 任务暂停"
  else -> "等待恢复"
}
