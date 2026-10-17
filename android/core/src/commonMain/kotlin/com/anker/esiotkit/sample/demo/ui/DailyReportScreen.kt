package com.anker.esiotkit.sample.demo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.sample.demo.analyzer.DailyReportAnalyzerImpl
import com.anker.esiotkit.sample.demo.progress.DemoProgress
import com.anker.esiotkit.support.di.esInject
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DailyReportUiState(
  val running: Boolean = false,
  val report: String = "",
  val status: String = "",
  val summaryLine: String = "",
  val keyEvents: List<String> = emptyList(),
  val logs: List<String> = emptyList(),
)

class DailyReportViewModel : ScreenModel {

  private val analyzer: DailyReportAnalyzerImpl by lazy { esInject() }

  private val _state = MutableStateFlow(DailyReportUiState())
  val state: StateFlow<DailyReportUiState> = _state.asStateFlow()

  fun generate() {
    if (_state.value.running) return
    screenModelScope.launch {
      val startMs = currentTimeMillis()
      appLogger.i("DailyReportViewModel/generate/start")
      _state.update { it.copy(running = true, report = "", logs = it.logs + "开始生成日报...") }
      try {
        val result = analyzer.generate()
        val elapsed = currentTimeMillis() - startMs
        appLogger.i("DailyReportViewModel/generate/success: status=${result.status.name} elapsedMs=$elapsed")
        _state.update {
          it.copy(
            running = false,
            report = result.dailyReport,
            status = result.status.name,
            summaryLine = "确认 ${result.confirmedCount} 条 / 疑似误报 ${result.falseAlarmCount} 条；时间分布：${result.timeAnalysis}",
            keyEvents = result.keyEvents,
            logs = it.logs + "日报完成: status=${result.status.name}",
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val elapsed = currentTimeMillis() - startMs
        val reason = e.message ?: e::class.simpleName ?: "未知错误"
        appLogger.e("DailyReportViewModel/generate/failure: error=$reason elapsedMs=$elapsed")
        DemoProgress.failRunning("日报生成失败 · $reason")
        _state.update {
          it.copy(
            running = false,
            status = "FAILED",
            report = "生成失败：$reason",
            logs = it.logs + "日报失败: error=$reason",
          )
        }
        emitToast("日报生成失败，请重试（详见页面日志）")
      }
    }
  }
}

object DailyReportScreen : BaseScreen() {

  @Composable
  override fun dynamicTitle(): String = "自动日报分析"

  @Composable
  override fun Content() {
    val viewModel = rememberScreenModel { DailyReportViewModel() }
    val state by viewModel.state.collectAsState()
    val progressSteps by DemoProgress.steps.collectAsState()
    var showTimeline by remember { mutableStateOf(false) }
    BaseColumn(scrollState = rememberScrollState()) {
      Spacer(Modifier.height(4.dp))

      // ── 生成按钮 ─────────────────────────────────────────────
      ActionButton(
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.running,
        onClick = {
          DemoProgress.reset()
          showTimeline = true
          viewModel.generate()
        },
      ) {
        Text(
          text = if (state.running) "生成中…" else "生成今日安全日报",
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
      if (progressSteps.isNotEmpty()) {
        TextButton(onClick = { showTimeline = true }, modifier = Modifier.fillMaxWidth()) {
          Text("查看执行过程", fontSize = 13.sp, color = DemoPrimary)
        }
      }

      // ── 空态提示卡片 ─────────────────────────────────────────
      if (state.report.isBlank() && !state.running) {
        Spacer(Modifier.height(14.dp))
        ReportEmptyCard()
      }

      // ── 结果区：统计小卡 + 日报正文 + 关键事件 ────────────────
      if (state.report.isNotBlank()) {
        val stats = remember(state.summaryLine) { parseSummary(state.summaryLine) }
        Spacer(Modifier.height(12.dp))
        StatsRow(stats)
        Spacer(Modifier.height(10.dp))
        ReportCard(status = state.status, report = state.report)
        if (state.keyEvents.isNotEmpty()) {
          Spacer(Modifier.height(10.dp))
          KeyEventsCard(events = state.keyEvents)
        }
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

// ───────────────────────────── 统计解析 ─────────────────────────────

private data class ReportStats(
  val confirmed: String,
  val falseAlarm: String,
  val timeDist: String,
)

/**
 * 从 summaryLine（形如 "确认 0 条 / 疑似误报 1 条；时间分布：15时×1"）解析统计值。
 * 解析失败时回退为 "—"，不改变 ViewModel 或 summaryLine 本身。
 */
private fun parseSummary(summary: String): ReportStats {
  if (summary.isBlank()) return ReportStats("—", "—", "—")
  val confirmed = Regex("确认\\s*(\\d+)").find(summary)?.groupValues?.getOrNull(1) ?: "—"
  val falseAlarm = Regex("疑似误报\\s*(\\d+)").find(summary)?.groupValues?.getOrNull(1) ?: "—"
  val timeDist = summary.substringAfter("时间分布：", "")
    .ifBlank { summary.substringAfter("时间分布:", "") }
    .trim()
    .ifBlank { "—" }
  return ReportStats(confirmed, falseAlarm, timeDist)
}

// ───────────────────────────── 组件 ─────────────────────────────

/** 主操作按钮：统一圆角、高度与字重。 */
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

/** 空态卡片：尚未生成日报时的友好引导。 */
@Composable
private fun ReportEmptyCard() {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = DemoLightSurface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 22.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        modifier = Modifier
          .size(48.dp)
          .clip(CircleShape)
          .background(Color(0xFFE8F1F7)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Filled.Summarize,
          contentDescription = null,
          tint = DemoPrimary,
          modifier = Modifier.size(24.dp),
        )
      }
      Spacer(Modifier.height(10.dp))
      Text(
        text = "还没有生成日报",
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = DemoTitle,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = "点击上方按钮，一键生成今日安全日报",
        fontSize = 12.5.sp,
        color = DemoSecondary,
      )
    }
  }
}

/** 三宫格统计：确认条数 / 疑似误报 / 时间分布。 */
@Composable
private fun StatsRow(stats: ReportStats) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatCard(label = "确认条数", value = stats.confirmed, modifier = Modifier.weight(1f))
    StatCard(label = "疑似误报", value = stats.falseAlarm, modifier = Modifier.weight(1f))
    StatCard(
      label = "时间分布",
      value = stats.timeDist,
      modifier = Modifier.weight(1f),
      valueSize = 12.sp,
      maxLines = 2,
    )
  }
}

@Composable
private fun StatCard(
  label: String,
  value: String,
  modifier: Modifier,
  valueSize: TextUnit = 18.sp,
  maxLines: Int = 1,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    color = DemoLightSurface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = value,
        fontSize = valueSize,
        fontWeight = FontWeight.Bold,
        color = DemoPrimary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(3.dp))
      Text(
        text = label,
        fontSize = 11.sp,
        color = DemoSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** 日报正文卡片：状态标签 + 可读正文。 */
@Composable
private fun ReportCard(status: String, report: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = Color.White,
    border = BorderStroke(1.dp, DemoCardBorder),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "日报",
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          color = DemoTitle,
        )
        Spacer(Modifier.width(8.dp))
        StatusChip(status)
      }
      Spacer(Modifier.height(8.dp))
      Text(
        text = report,
        fontSize = 14.sp,
        color = DemoTitle,
        lineHeight = 21.sp,
      )
    }
  }
}

/** 关键事件卡片：逐条列出，便于快速扫读。 */
@Composable
private fun KeyEventsCard(events: List<String>) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = DemoLightSurface,
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(
        text = "关键事件（${events.size}）",
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = DemoTitle,
      )
      Spacer(Modifier.height(6.dp))
      events.forEach { event ->
        Text(
          text = "- $event",
          fontSize = 12.sp,
          color = Color(0xFF4B5563),
          lineHeight = 18.sp,
        )
        Spacer(Modifier.height(4.dp))
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
