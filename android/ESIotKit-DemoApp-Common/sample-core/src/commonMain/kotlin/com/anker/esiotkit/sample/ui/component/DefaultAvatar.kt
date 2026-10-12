package com.anker.esiotkit.sample.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp

/**
 * Matches Security-Devices-Page HTML 设计稿的头像。
 * 使用左右双色分层（主色 + 较深右侧阴影）+ 头部圆形 + 身体弧形。
 * 身体区域通过与底圆求交实现底部裁剪（不凸出圆外）。
 */
@Composable
fun DefaultAvatar(modifier: Modifier = Modifier) {
  val bgLeft = Color(0xFF80AFFF)
  val bgRight = Color(0xFF6EA3FF)
  val bodyLeft = Color(0xFFD1E1FF)
  val bodyRight = Color(0xFFC1D8FF)
  val headLeft = Color(0xFFDAE7FF)
  val headRight = Color(0xFFC1D8FF)

  Canvas(modifier = modifier.size(32.dp)) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = w / 2f

    // Base circle — left half 主色, right half 阴影色
    drawCircle(color = bgLeft, radius = r, center = Offset(cx, cy))
    clipRect(left = cx, top = 0f, right = w, bottom = h) {
      drawCircle(color = bgRight, radius = r, center = Offset(cx, cy))
    }

    // Body (oval), clipped to base circle so底部完美贴合圆形
    val bodyWidth = w * 0.78f
    val bodyHeight = h * 0.54f
    val bodyTop = h * 0.60f
    val bodyLeftX = (w - bodyWidth) / 2f
    val circlePath = Path().apply { addOval(androidx.compose.ui.geometry.Rect(0f, 0f, w, h)) }
    clipPath(path = circlePath) {
      drawOval(
        color = bodyLeft,
        topLeft = Offset(bodyLeftX, bodyTop),
        size = Size(bodyWidth, bodyHeight)
      )
      clipRect(left = cx, top = 0f, right = w, bottom = h) {
        drawOval(
          color = bodyRight,
          topLeft = Offset(bodyLeftX, bodyTop),
          size = Size(bodyWidth, bodyHeight)
        )
      }
    }

    // Head — split left/right
    val headRadius = w * 0.172f
    val headCenterY = h * 0.41f
    drawCircle(color = headLeft, radius = headRadius, center = Offset(cx, headCenterY))
    clipRect(left = cx, top = 0f, right = w, bottom = h) {
      drawCircle(color = headRight, radius = headRadius, center = Offset(cx, headCenterY))
    }
  }
}
