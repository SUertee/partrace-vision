package com.anker.esiotkit.sample.ui.component

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WiFi 信号详情页使用的大图标（含信号值文本）
 */
@Composable
fun WifiSignalStrength(
  deviceWifiSignalStrengthLevel: Int,
  deviceWifiSignalStrength: String
) {
  val animatedLevel by animateIntAsState(
    targetValue = deviceWifiSignalStrengthLevel,
    animationSpec = tween(durationMillis = 500)
  )

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier.padding(16.dp)
  ) {
    Box(
      modifier = Modifier
        .size(120.dp, 160.dp)
        .padding(8.dp)
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        drawWifiSignal(animatedLevel)
      }
    }
    Text(
      text = deviceWifiSignalStrength,
      fontSize = 18.sp,
      fontWeight = FontWeight.Medium,
      color = Color(0xFF333333)
    )
  }
}

/**
 * 小尺寸 WiFi 信号图标，用于设置列表等场景
 */
@Composable
fun WifiSignalIcon(
  level: Int,
  modifier: Modifier = Modifier.size(20.dp)
) {
  Canvas(modifier = modifier) {
    drawWifiSignal(level)
  }
}

/**
 * 根据 RSSI (dBm) 计算信号格数 (0-4)
 */
fun rssiToLevel(rssi: Int): Int = when {
  rssi >= -50 -> 4
  rssi >= -60 -> 3
  rssi >= -70 -> 2
  rssi >= -80 -> 1
  else -> 0
}

private fun DrawScope.drawWifiSignal(level: Int) {
  val centerX = size.width / 2
  val sweepAngle = 90f

  // 根据画布宽度计算最大弧线半径（不超出左右边界）
  val maxRadius = size.width / 2 / 0.707f  // sin(45°) ≈ 0.707
  val arcStrokeWidth = maxRadius / 10f
  val dotRadius = arcStrokeWidth * 1.2f

  // 图标实际高度 = 最大弧线半径 + 底部圆点半径
  val iconHeight = maxRadius + dotRadius
  // 将图标整体垂直居中于画布
  val centerY = (size.height - iconHeight) / 2 + maxRadius

  val inactiveColor = Color(0xFFE0E0E0)
  val activeColor = when {
    level >= 3 -> Color(0xFF2BA270)
    level == 2 -> Color(0xFFFFC107)
    level == 1 -> Color(0xFFFF5722)
    else -> inactiveColor
  }

  // 绘制 3 条扇形弧线（对应 level 2、3、4）
  for (i in 1..3) {
    val radius = maxRadius * i / 3f
    val startAngle = 270f - sweepAngle / 2
    val color = if (level >= i + 1) activeColor else inactiveColor

    drawArc(
      color = color,
      startAngle = startAngle,
      sweepAngle = sweepAngle,
      useCenter = false,
      topLeft = Offset(centerX - radius, centerY - radius),
      size = Size(radius * 2, radius * 2),
      style = Stroke(width = arcStrokeWidth, cap = StrokeCap.Round)
    )
  }

  // 绘制底部圆点（对应 level 1）
  drawCircle(
    color = if (level >= 1) activeColor else inactiveColor,
    radius = dotRadius,
    center = Offset(centerX, centerY)
  )
}