package com.anker.esiotkit.sample.demo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.sample.demo.ai.IAiAnalysisService
import com.anker.esiotkit.sample.demo.analyzer.SafetyAnalyzerImpl
import com.anker.esiotkit.sample.demo.audio.AudioPlayer
import com.anker.esiotkit.sample.demo.audio.AudioRecorder
import com.anker.esiotkit.sample.demo.audio.createAudioPlayer
import com.anker.esiotkit.sample.demo.audio.createAudioRecorder
import com.anker.esiotkit.sample.demo.progress.DemoProgress
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.di.esInject
import com.anker.esiotkit.support.log.appLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoiceQueryUiState(
  val query: String = "帮我看下今天上午家里有没有人出入",
  val recording: Boolean = false,
  val running: Boolean = false,
  val answer: String = "",
  val status: String = "",
  val logs: List<String> = emptyList(),
)

class VoiceQueryViewModel : ScreenModel {

  private val aiService: IAiAnalysisService by lazy { esInject() }
  private val analyzer: SafetyAnalyzerImpl by lazy { esInject() }
  private val recorder: AudioRecorder by lazy { createAudioRecorder() }
  private val player: AudioPlayer by lazy { createAudioPlayer() }

  private val _state = MutableStateFlow(VoiceQueryUiState())
  val state: StateFlow<VoiceQueryUiState> = _state.asStateFlow()

  private var lastTts: ByteArray? = null

  fun onQueryChange(text: String) = _state.update { it.copy(query = text) }

  fun toggleRecord() {
    if (_state.value.running) return
    val current = recorder.isRecording()
    appLogger.i("VoiceQueryViewModel/toggleRecord/start: recording=$current")
    if (current) {
      val wav = try {
        recorder.stop()
      } catch (e: Exception) {
        appLogger.e("VoiceQueryViewModel/toggleRecord/failure: reason=stop, error=${e.message ?: e::class.simpleName}")
        _state.update {
          it.copy(recording = false, logs = it.logs + "停止录音异常: ${e.message ?: e::class.simpleName}")
        }
        emitToast("停止录音失败，请重试")
        return
      }
      _state.update { it.copy(recording = false, logs = it.logs + "录音结束: ${wav?.size ?: 0} bytes") }
      if (wav == null || wav.isEmpty()) {
        _state.update { it.copy(recording = false, logs = it.logs + "录音为空（检查麦克风权限），请改用文本输入") }
        emitToast("录音为空，请检查麦克风权限或改用文本输入")
        return
      }
      appLogger.i("VoiceQueryViewModel/toggleRecord/success: wavBytes=${wav.size}")
      screenModelScope.launch {
        _state.update { it.copy(running = true, logs = it.logs + "ASR 识别中...") }
        val text = try {
          aiService.transcribeAudio(wav)
        } catch (e: Exception) {
          appLogger.e("VoiceQueryViewModel/toggleRecord/failure: reason=transcribe, error=${e.message ?: e::class.simpleName}")
          _state.update {
            it.copy(running = false, logs = it.logs + "ASR 识别失败: ${e.message ?: e::class.simpleName}")
          }
          emitToast("语音识别失败，请重试或改用文本输入")
          return@launch
        }
        if (text.isNullOrBlank()) {
          _state.update { it.copy(running = false, logs = it.logs + "语音识别暂不可用，请改用文本输入") }
          emitToast("语音识别不可用，请改用文本输入")
          return@launch
        }
        _state.update { it.copy(query = text, running = false, logs = it.logs + "ASR 结果: $text") }
        runAnalysis(text)
      }
    } else {
      val started = try {
        recorder.start()
        recorder.isRecording()
      } catch (e: Exception) {
        appLogger.e("VoiceQueryViewModel/toggleRecord/failure: reason=start, error=${e.message ?: e::class.simpleName}")
        _state.update {
          it.copy(
            recording = false,
            logs = it.logs + "录音启动失败: ${e.message ?: e::class.simpleName}（检查麦克风权限）",
          )
        }
        emitToast("录音启动失败，请检查麦克风权限或改用文本输入")
        return
      }
      if (started) {
        appLogger.i("VoiceQueryViewModel/toggleRecord/success: recordStarted=true")
        _state.update { it.copy(recording = true, logs = it.logs + "开始录音...") }
      } else {
        appLogger.e("VoiceQueryViewModel/toggleRecord/failure: reason=start-not-recording")
        _state.update {
          it.copy(recording = false, logs = it.logs + "录音启动失败（检查麦克风权限），请改用文本输入")
        }
        emitToast("录音启动失败，请检查麦克风权限或改用文本输入")
      }
    }
  }

  fun analyzeText() {
    if (_state.value.running) return
    val query = _state.value.query.trim()
    if (query.isEmpty()) {
      appLogger.w("VoiceQueryViewModel/analyzeText/fallback: reason=blank-query, useVoiceInput=true")
      emitToast("请输入问题，或先录音识别")
      return
    }
    screenModelScope.launch { runAnalysis(query) }
  }

  fun playAnswer() {
    val tts = lastTts
    if (tts == null) {
      appLogger.w("VoiceQueryViewModel/playAnswer/fallback: reason=no-tts")
      emitToast("暂无可播报的语音")
      return
    }
    try {
      player.play(tts)
      appLogger.i("VoiceQueryViewModel/playAnswer/success: bytes=${tts.size}")
    } catch (e: Exception) {
      appLogger.e("VoiceQueryViewModel/playAnswer/failure: error=${e.message ?: e::class.simpleName}")
      _state.update { it.copy(logs = it.logs + "重新播报失败: ${e.message ?: e::class.simpleName}") }
      emitToast("播报失败，请重试")
    }
  }

  private suspend fun runAnalysis(query: String) {
    appLogger.i("VoiceQueryViewModel/analyzeText/start: queryLength=${query.length}")
    _state.update { it.copy(running = true, answer = "", status = "", logs = it.logs + "开始分析: $query") }
    try {
      val result = analyzer.analyze(query)
      _state.update {
        it.copy(
          running = false,
          answer = result.answer,
          status = result.status.name,
          logs = it.logs + "分析完成: status=${result.status.name} elapsed=${result.totalElapsedMs}ms",
        )
      }
      appLogger.i("VoiceQueryViewModel/analyzeText/success: status=${result.status.name}, elapsedMs=${result.totalElapsedMs}")
      DemoProgress.start("tts", "语音播报", "语音合成 + 自动播放")
      val tts = try {
        aiService.synthesizeSpeech(result.answer)
      } catch (e: Exception) {
        appLogger.e("VoiceQueryViewModel/runAnalysis/failure: reason=synthesizeSpeech, error=${e.message ?: e::class.simpleName}")
        null
      }
      if (tts != null) {
        lastTts = tts
        _state.update { it.copy(logs = it.logs + "TTS 就绪（${tts.size} bytes），自动播报") }
        try {
          player.play(tts)
          DemoProgress.success("tts", "${tts.size} bytes · 已自动播放")
        } catch (e: Exception) {
          appLogger.e("VoiceQueryViewModel/runAnalysis/failure: reason=play, error=${e.message ?: e::class.simpleName}")
          _state.update { it.copy(logs = it.logs + "自动播报失败，可点“重新播报”重试") }
          DemoProgress.fail("tts", "播放失败 · 可手动重播")
        }
      } else {
        appLogger.e("VoiceQueryViewModel/runAnalysis/failure: reason=tts-unavailable")
        _state.update { it.copy(logs = it.logs + "TTS 失败，仅文本展示") }
        DemoProgress.fail("tts", "语音合成不可用 · 仅文本展示")
      }
    } catch (e: Exception) {
      appLogger.e("VoiceQueryViewModel/analyzeText/failure: error=${e.message ?: e::class.simpleName}")
      DemoProgress.failRunning("分析失败 · ${e.message ?: e::class.simpleName}")
      _state.update {
        it.copy(
          running = false,
          status = "FAILED",
          answer = "分析失败：${e.message ?: e::class.simpleName}",
          logs = it.logs + "分析失败: ${e.message ?: e::class.simpleName}",
        )
      }
      emitToast("分析失败，请重试（详见页面日志）")
    }
  }
}

object VoiceQueryScreen : BaseScreen() {

  @Composable
  override fun dynamicTitle(): String = "语音问答安心分析"

  @Composable
  override fun Content() {
    val viewModel = rememberScreenModel { VoiceQueryViewModel() }
    val state by viewModel.state.collectAsState()
    val progressSteps by DemoProgress.steps.collectAsState()
    var showTimeline by remember { mutableStateOf(false) }
    BaseColumn(scrollState = rememberScrollState()) {
      Spacer(Modifier.height(4.dp))

      // ── 输入卡片：问题输入 + 示例提示 ─────────────────────────
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, DemoCardBorder),
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("你的问题") },
            placeholder = { Text("例如：帮我看下今天下午家里有没有人出入") },
            shape = RoundedCornerShape(10.dp),
            minLines = 1,
            maxLines = 3,
          )
        }
      }
      Spacer(Modifier.height(10.dp))

      // ── 分析按钮 ─────────────────────────────────────────────
      ActionButton(
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.running,
        onClick = {
          if (state.query.isNotBlank()) {
            DemoProgress.reset()
            showTimeline = true
          }
          viewModel.analyzeText()
        },
      ) {
        Text(
          text = if (state.running) "分析中…" else "开始分析",
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
      if (progressSteps.isNotEmpty()) {
        TextButton(onClick = { showTimeline = true }, modifier = Modifier.fillMaxWidth()) {
          Text("查看执行过程", fontSize = 13.sp, color = DemoPrimary)
        }
      }

      // ── 结论卡片 ─────────────────────────────────────────────
      if (state.answer.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        VoiceResultCard(
          status = state.status,
          answer = state.answer,
          onReplay = { viewModel.playAnswer() },
        )
      }

      // ── 运行日志（默认折叠，保证一屏可容纳主内容） ─────────────
      Spacer(Modifier.height(12.dp))
      CollapsibleLogCard(logs = state.logs)
      Spacer(Modifier.height(24.dp))
    }
    DemoTimelineDialog(visible = showTimeline, onClose = { showTimeline = false })
  }
}

// ───────────────────────────── 设计令牌 ─────────────────────────────
private val DemoPrimary = Color(0xFF005D8E)
private val DemoTitle = Color(0xFF1F1F1F)
private val DemoSecondary = Color(0xFF6B7280)
private val DemoTertiary = Color(0xFF8A9199)
private val DemoCardBorder = Color(0xFFE5EAF0)
private val DemoLightSurface = Color(0xFFF5F7FA)

// ───────────────────────────── 组件 ─────────────────────────────

/** 主/警示操作按钮：统一圆角、高度与字重。 */
@Composable
private fun ActionButton(
  modifier: Modifier = Modifier,
  container: Color = DemoPrimary,
  contentColor: Color = Color.White,
  enabled: Boolean = true,
  onClick: () -> Unit,
  content: @Composable RowScope.() -> Unit,
) {
  Button(
    onClick = onClick,
    modifier = modifier.height(48.dp),
    enabled = enabled,
    shape = RoundedCornerShape(12.dp),
    colors = ButtonDefaults.buttonColors(
      containerColor = container,
      contentColor = contentColor,
    ),
    contentPadding = PaddingValues(horizontal = 16.dp),
  ) {
    content()
  }
}

/** 状态标签：SUCCESS 绿 / FAILED 红 / EMPTY 中性。保留 [status] 语义。 */
@Composable
private fun StatusChip(status: String) {
  val (bg, fg) = when (status.uppercase()) {
    "SUCCESS" -> Color(0xFFE7F6EC) to Color(0xFF1A7F37)
    "FAILED" -> Color(0xFFFDECEC) to Color(0xFFC0392B)
    else -> DemoLightSurface to DemoSecondary
  }
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(bg)
      .padding(horizontal = 8.dp, vertical = 3.dp),
  ) {
    Text(
      text = "[$status]",
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      color = fg,
    )
  }
}

/** 结论卡片：状态标签 + 可读结论正文 + 重新播报。 */
@Composable
private fun VoiceResultCard(status: String, answer: String, onReplay: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = Color.White,
    border = BorderStroke(1.dp, DemoCardBorder),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "结论",
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          color = DemoTitle,
        )
        Spacer(Modifier.width(8.dp))
        StatusChip(status)
      }
      Spacer(Modifier.height(8.dp))
      Text(
        text = answer,
        fontSize = 15.sp,
        color = DemoTitle,
        lineHeight = 22.sp,
      )
      Spacer(Modifier.height(12.dp))
      OutlinedButton(
        onClick = onReplay,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, DemoPrimary),
        contentPadding = PaddingValues(vertical = 10.dp),
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.VolumeUp,
          contentDescription = null,
          tint = DemoPrimary,
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
          text = "重新播报",
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium,
          color = DemoPrimary,
        )
      }
    }
  }
}

/**
 * 可折叠运行日志卡片：默认折叠，仅显示条数与最后一行；展开后显示 takeLast(30)。
 * 便于主内容在一屏内完整呈现。
 */
@Composable
private fun CollapsibleLogCard(logs: List<String>) {
  var expanded by remember { mutableStateOf(false) }
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = DemoLightSurface,
  ) {
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { expanded = !expanded }
          .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "运行日志",
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          color = DemoTitle,
        )
        if (logs.isNotEmpty()) {
          Spacer(Modifier.width(6.dp))
          Text(
            text = "${logs.size} 条",
            fontSize = 11.sp,
            color = DemoTertiary,
          )
        }
        Spacer(Modifier.weight(1f))
        Icon(
          imageVector = Icons.Filled.KeyboardArrowDown,
          contentDescription = if (expanded) "收起" else "展开",
          tint = DemoTertiary,
          modifier = Modifier
            .size(20.dp)
            .rotate(if (expanded) 180f else 0f),
        )
      }
      if (expanded) {
        Column(
          modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        ) {
          if (logs.isEmpty()) {
            Text(text = "暂无日志", fontSize = 11.sp, color = DemoTertiary)
          } else {
            logs.takeLast(30).forEach { line ->
              Text(
                text = line,
                fontSize = 11.sp,
                color = Color(0xFF4B5563),
                lineHeight = 16.sp,
              )
              Spacer(Modifier.height(3.dp))
            }
          }
        }
      } else if (logs.isNotEmpty()) {
        Text(
          text = logs.last(),
          fontSize = 11.sp,
          color = DemoTertiary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier
            .padding(horizontal = 14.dp)
            .padding(bottom = 12.dp),
        )
      }
    }
  }
}
