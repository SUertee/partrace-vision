package com.anker.esiotkit.sample.main.station.pipeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class PeopleTaskView(
  val jobId: String,
  val cameraId: String,
  val label: String,
  val status: String,
  val error: String?,
  val eventCount: Int,
  val evidenceCount: Int,
  val retryable: Boolean,
  val redownloadable: Boolean = false
)

data class PeoplePipelineState(
  val configured: Boolean = false,
  val enabled: Boolean = false,
  val foreground: Boolean = false,
  val error: String? = null,
  val tasks: List<PeopleTaskView> = emptyList()
)

data class PeoplePipelineCompletion(val jobId: String, val recordingId: String, val eventCount: Int, val evidenceCount: Int)

expect object PeoplePipeline {
  val state: StateFlow<PeoplePipelineState>
  val completions: SharedFlow<PeoplePipelineCompletion>
  fun retry(jobId: String)
}

@Composable
expect fun PeoplePipelineHost(sdkAuthenticated: Boolean)

@Composable
internal expect fun PeoplePipelineSettingsContent(onBack: () -> Unit)

object PeoplePipelineSettingsScreen : Screen {
  @Composable
  override fun Content() {
    val navigator = LocalNavigator.currentOrThrow
    PeoplePipelineSettingsContent(onBack = { navigator.pop() })
  }
}

@Composable
fun PeoplePipelineCard(onConfigure: () -> Unit, modifier: Modifier = Modifier) {
  val state by PeoplePipeline.state.collectAsState()
  val completed = state.tasks.count { it.status == "completed" }
  val pending = state.tasks.count { it.status != "completed" && it.status != "failed" }
  val failed = state.tasks.count { it.status == "failed" }
  Column(modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(11.dp))
    .clickable(onClick = onConfigure).padding(16.dp)) {
    Text("人物录像自动解析", color = Color(0xFF333333), fontSize = 17.sp)
    Text(
      when {
        !state.configured -> "尚未配置服务 · 点击设置"
        !state.enabled -> "已暂停 · 点击查看任务"
        !state.foreground -> "等待 App 前台与 SDK 登录"
        else -> "待处理 $pending · 已完成 $completed · 失败 $failed"
      }, color = Color(0xFF666666), fontSize = 13.sp
    )
    state.error?.let { Text(it, color = Color(0xFFB54B36), fontSize = 12.sp) }
    state.tasks.firstOrNull { it.status == "completed" }?.let {
      Text(if (it.eventCount == 0 && it.evidenceCount == 0) "最近一段录像已解析完成"
        else "最近完成：${it.eventCount} 个区域事件 · ${it.evidenceCount} 张证据",
        color = Color(0xFF438A68), fontSize = 12.sp)
    }
  }
}
