package com.anker.esiotkit.sample.main.station

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val TraceAssistantBlue = Color(0xFF4AA8FF)

/** Pure presentation shell; previews never initialize the SDK or the Agent. */
@Composable
internal fun TraceAssistantLayout(
  question: String,
  onQuestionChange: (String) -> Unit,
  inputEnabled: Boolean,
  showWelcome: Boolean,
  onBack: () -> Unit,
  onHistory: () -> Unit,
  onSubmit: () -> Unit,
  onDevice: () -> Unit,
  onTime: () -> Unit,
  onEvent: () -> Unit,
  onZone: () -> Unit,
  onPrompt: (String) -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    Modifier.fillMaxSize().background(
      Brush.verticalGradient(
        0f to Color(0xFFEDFCFE),
        0.29f to Color.White,
        0.73f to Color.White,
        1f to Color(0xFFEDFCFE),
      ),
    ).imePadding(),
  ) {
    TraceAssistantHeader(onBack, onHistory)
    Column(
      Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
    ) {
      if (showWelcome) {
        Spacer(Modifier.height(33.dp))
        Column(Modifier.padding(horizontal = 29.dp)) {
          Text("Hi 驿站追迹", color = TraceAssistantBlue, fontSize = 28.sp,
            lineHeight = 38.sp, fontWeight = FontWeight.Bold)
          Spacer(Modifier.height(14.dp))
          Text("我可以帮你调设备找录像", color = Color(0xFF858585), fontSize = 16.sp, lineHeight = 24.sp)
        }
        Spacer(Modifier.height(42.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          listOf(
            "十点有人来过驿站吗？",
            "我想看一下谁今天取走了这个快递",
            "怎么切换摄像头？",
          ).forEach { prompt -> TracePromptChip(prompt) { onPrompt(prompt) } }
        }
      }
      Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
      Spacer(Modifier.height(14.dp))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TracePromptChip("@设备", onDevice)
        TracePromptChip("时间", onTime)
        TracePromptChip("事件", onEvent)
        TracePromptChip("区域/货架", onZone)
      }
      Spacer(Modifier.height(18.dp))
      TraceQuestionComposer(question, onQuestionChange, inputEnabled, onSubmit)
      Spacer(Modifier.height(29.dp))
      Text("上述内容由AI生成，仅供参考", color = TraceAssistantBlue,
        fontSize = 12.sp, lineHeight = 18.sp,
        modifier = Modifier.align(Alignment.CenterHorizontally))
      Spacer(Modifier.height(7.dp))
    }
  }
}

@Composable
internal fun TraceAssistantHeader(onBack: () -> Unit, onHistory: () -> Unit) {
  Box(Modifier.fillMaxWidth().height(108.dp)) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 22.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) {
        Canvas(Modifier.size(23.dp)) {
          val path = Path().apply {
            moveTo(size.width * 0.66f, size.height * 0.16f)
            lineTo(size.width * 0.29f, size.height * 0.5f)
            lineTo(size.width * 0.66f, size.height * 0.84f)
          }
          drawPath(path, Color(0xFF383838), style = Stroke(width = 2.dp.toPx()))
        }
      }
      IconButton(onClick = onHistory, modifier = Modifier.semantics { contentDescription = "会话历史与设置" }) {
        Canvas(Modifier.size(25.dp)) {
          val ink = Color(0xFF383838)
          val stroke = 2.dp.toPx()
          drawArc(ink, 205f, 311f, false,
            topLeft = Offset(size.width * 0.08f, size.height * 0.08f), size = size * 0.84f,
            style = Stroke(stroke, cap = StrokeCap.Round))
          val arrow = Path().apply {
            moveTo(size.width * 0.09f, size.height * 0.14f)
            lineTo(size.width * 0.09f, size.height * 0.32f)
            lineTo(size.width * 0.28f, size.height * 0.32f)
          }
          drawPath(arrow, ink, style = Stroke(stroke, cap = StrokeCap.Round))
          drawLine(ink, Offset(size.width * 0.5f, size.height * 0.25f),
            Offset(size.width * 0.5f, size.height * 0.52f), stroke, StrokeCap.Round)
          drawLine(ink, Offset(size.width * 0.5f, size.height * 0.52f),
            Offset(size.width * 0.67f, size.height * 0.67f), stroke, StrokeCap.Round)
        }
      }
    }
    Text("P.T.Vision", color = Color.Black, fontSize = 23.sp,
      lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp,
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 33.dp))
  }
}

@Composable
internal fun TracePromptChip(text: String, onClick: () -> Unit) {
  Box(
    Modifier.heightIn(min = 37.dp).border(1.dp, Color(0xFFD9D9D9), CircleShape)
      .clip(CircleShape).background(Color.White).clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 6.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, color = Color(0xFF5A5A5A), fontSize = 16.sp, lineHeight = 23.sp)
  }
}

@Composable
private fun TraceQuestionComposer(
  question: String, onQuestionChange: (String) -> Unit, enabled: Boolean, onSubmit: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 66.dp)
      .border(3.dp, Brush.horizontalGradient(listOf(Color(0xFF7D7DFF), TraceAssistantBlue, Color(0xFFA7E4CF))), CircleShape)
      .padding(3.dp).background(Color.White, CircleShape).padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicTextField(
      value = question,
      onValueChange = onQuestionChange,
      enabled = enabled,
      maxLines = 3,
      textStyle = TextStyle(color = Color(0xFF505050), fontSize = 19.sp, lineHeight = 27.sp),
      cursorBrush = Brush.verticalGradient(listOf(TraceAssistantBlue, TraceAssistantBlue)),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
      keyboardActions = KeyboardActions(onSend = { onSubmit() }),
      modifier = Modifier.weight(1f).padding(vertical = 8.dp)
        .semantics { contentDescription = "输入追溯问题" },
      decorationBox = { field ->
        Box {
          if (question.isEmpty()) Text("设备离线了怎么办？", color = Color(0xFF858585), fontSize = 19.sp, lineHeight = 27.sp)
          field()
        }
      },
    )
    Spacer(Modifier.width(8.dp))
    IconButton(onClick = onSubmit, enabled = enabled && question.isNotBlank(),
      modifier = Modifier.size(52.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape)
        .semantics { contentDescription = "提交问题" }) { TraceComposerIcon(send = true) }
  }
}

@Composable
internal fun TraceComposerIcon(send: Boolean) {
  Canvas(Modifier.size(30.dp)) {
    val blue = TraceAssistantBlue
    if (send) {
      val arrow = Path().apply {
        moveTo(size.width * 0.76f, size.height * 0.16f)
        lineTo(size.width * 0.76f, size.height * 0.6f)
        lineTo(size.width * 0.23f, size.height * 0.6f)
        moveTo(size.width * 0.38f, size.height * 0.36f)
        lineTo(size.width * 0.23f, size.height * 0.6f)
        lineTo(size.width * 0.38f, size.height * 0.82f)
      }
      drawPath(arrow, blue, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    } else {
      listOf(0.18f, 0.34f, 0.5f, 0.66f, 0.82f).forEachIndexed { index, x ->
        val halfHeight = listOf(0.08f, 0.24f, 0.37f, 0.24f, 0.08f)[index]
        drawLine(blue, Offset(size.width * x, size.height * (0.5f - halfHeight)),
          Offset(size.width * x, size.height * (0.5f + halfHeight)), 1.8.dp.toPx(), StrokeCap.Round)
      }
    }
  }
}
