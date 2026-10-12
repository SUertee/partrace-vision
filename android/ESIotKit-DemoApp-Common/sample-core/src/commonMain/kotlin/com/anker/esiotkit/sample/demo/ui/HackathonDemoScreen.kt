package com.anker.esiotkit.sample.demo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.view.BaseScreen

/**
 * AI Demo 入口页（底部 Tab 内容）：承载独立功能入口。
 *
 * 注意：标题栏由宿主底部 Tab（[com.anker.esiotkit.sample.main.mega.MegaMainScreen]）统一渲染，
 * 因此这里显式传 [BaseScreen.BaseColumn] 的 titleBar = false，避免出现重复标题与多余的返回箭头。
 */
object HackathonDemoScreen : BaseScreen() {

  @Composable
  override fun Content() {
    val navigator = LocalNavigator.currentOrThrow
    EntryContent(
      onVoiceQuery = { navigator.push(VoiceQueryScreen) },
      onDailyReport = { navigator.push(DailyReportScreen) },
    )
  }

  @Composable
  fun EntryContent(
    onVoiceQuery: () -> Unit,
    onDailyReport: () -> Unit,
  ) {
    BaseColumn(titleBar = false, scrollState = rememberScrollState()) {
      Spacer(Modifier.height(8.dp))

      // ── 头部区块：主标题 + 副标题 ─────────────────────────────
      Text(
        text = "AI 安心分析",
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF1F1F1F),
      )
      Spacer(Modifier.height(6.dp))
      Text(
        text = "SDK 事件数据 × AI 智能分析 · 语音问答与自动日报",
        fontSize = 13.sp,
        color = Color(0xFF6B7280),
        lineHeight = 18.sp,
      )
      Spacer(Modifier.height(20.dp))

      // ── 功能入口卡片 ────────────────────────────────────────
      FeatureCard(
        title = "语音问答安心分析",
        desc = "语音或文字提问，AI 结合今日事件给出安心结论",
        icon = Icons.Filled.RecordVoiceOver,
        onClick = onVoiceQuery,
      )
      Spacer(Modifier.height(12.dp))
      FeatureCard(
        title = "自动日报分析",
        desc = "一键生成今日安全日报：确认/误报统计 + 时间线",
        icon = Icons.Filled.Summarize,
        onClick = onDailyReport,
      )
      Spacer(Modifier.height(20.dp))

      // ── 底部说明区块 ────────────────────────────────────────
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(Color(0xFFF5F7FA))
          .padding(14.dp)
      ) {
        Text(
          text = "完整链路需已登录账号 + 设备在线；分析会自动结合今日事件与本地素材。",
          fontSize = 12.sp,
          color = Color(0xFF8A9199),
          lineHeight = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          text = "控制台日志 TAG: ESIot/data · ESIot/http · ESIot/app",
          fontSize = 11.sp,
          color = Color(0xFFA0A6AD),
        )
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}

/**
 * 功能入口卡片：白色圆角卡片 + 浅色边框，左侧图标圆形容器，右侧 chevron 指示可进入。
 */
@Composable
private fun FeatureCard(
  title: String,
  desc: String,
  icon: ImageVector,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(16.dp)
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .clickable(onClick = onClick),
    shape = shape,
    color = Color.White,
    border = BorderStroke(1.dp, Color(0xFFE5EAF0)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(CircleShape)
          .background(Color(0xFFE8F1F7)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = Color(0xFF005D8E),
          modifier = Modifier.size(24.dp),
        )
      }
      Spacer(Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          fontSize = 17.sp,
          fontWeight = FontWeight.SemiBold,
          color = Color(0xFF1F1F1F),
        )
        Spacer(Modifier.height(4.dp))
        Text(
          text = desc,
          fontSize = 12.5.sp,
          color = Color(0xFF6B7280),
          lineHeight = 17.sp,
        )
      }
      Spacer(Modifier.width(8.dp))
      Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = Color(0xFFB0B8C1),
        modifier = Modifier.size(22.dp),
      )
    }
  }
}
