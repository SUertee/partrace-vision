package com.anker.esiotkit.sample.ui.composable

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * @version 2024/9/5
 */
fun Modifier.rotatingDashedBorder(
  color: Color = Color.Black,
  cornerRadius: Dp = 8.dp,
  strokeWidth: Dp = 1.dp,
  dashArray: FloatArray = floatArrayOf(20f, 20f),
  durationMillis: Int = 4000
): Modifier = composed {
  // 定义动画的无限旋转
  val infiniteTransition = rememberInfiniteTransition()
  val angle by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = durationMillis, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    )
  )

  this.then(
    Modifier.drawBehind {
      // 绘制断线边框
      drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(cornerRadius.toPx()),
        style = Stroke(
          width = strokeWidth.toPx(),
          pathEffect = PathEffect.dashPathEffect(dashArray, angle),
          cap = StrokeCap.Round,
          join = StrokeJoin.Round
        )
      )
    }
  )
}