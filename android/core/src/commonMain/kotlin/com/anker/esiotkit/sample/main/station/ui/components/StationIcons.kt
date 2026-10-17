package com.anker.esiotkit.sample.main.station.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

internal enum class StationSymbol { Home, Events, Chat, Person, Bell, Message, Menu, Chevron, Back, Play, More, Settings, Search }

/** Small outlined symbols matching the supplied design, in a 28 unit viewport. */
@Composable
internal fun StationIcon(
  symbol: StationSymbol,
  description: String? = null,
  modifier: Modifier = Modifier.size(28.dp),
  color: Color = Color(0xFF383838)
) {
  Canvas(modifier) {
    val stroke = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
      drawLine(color, Offset(x1, y1), Offset(x2, y2), 1.8f, StrokeCap.Round)
    fun path(vararg points: Pair<Float, Float>, close: Boolean = false) {
      val p = Path().apply {
        moveTo(points[0].first, points[0].second)
        points.drop(1).forEach { lineTo(it.first, it.second) }
        if (close) close()
      }
      drawPath(p, color, style = stroke)
    }
    scale(size.width / 28f, size.height / 28f, pivot = Offset.Zero) {
      when (symbol) {
        StationSymbol.Home -> {
          path(4f to 11f, 14f to 3f, 24f to 11f, 24f to 27f, 4f to 27f, close = true)
          path(11f to 27f, 11f to 18f, 17f to 18f, 17f to 27f)
        }
        StationSymbol.Events -> {
          drawRoundRect(color, Offset(3f, 5f), Size(22f, 21f), CornerRadius(3f, 3f), style = stroke)
          line(8f, 2f, 8f, 8f); line(20f, 2f, 20f, 8f); line(3f, 11f, 25f, 11f)
          line(8f, 16f, 11f, 16f); line(15f, 16f, 20f, 16f)
          line(8f, 21f, 11f, 21f); line(15f, 21f, 20f, 21f)
        }
        StationSymbol.Chat -> {
          path(2f to 3f, 23f to 3f, 23f to 19f, 11f to 19f, 8f to 22f, 5f to 19f, 2f to 19f, close = true)
          path(14f to 20f, 14f to 25f, 21f to 25f, 24f to 27f, 26f to 24f, 28f to 24f, 28f to 14f, 24f to 14f)
          listOf(7f, 12f, 17f).forEach { drawCircle(color, 1f, Offset(it, 11f)) }
        }
        StationSymbol.Person -> {
          drawCircle(color, 4.6f, Offset(14f, 7f), style = stroke)
          val p = Path().apply { moveTo(2f,27f); lineTo(2f,23f); quadraticBezierTo(2f,17f,8f,17f); lineTo(20f,17f); quadraticBezierTo(26f,17f,26f,23f); lineTo(26f,27f); close() }
          drawPath(p, color, style = stroke)
        }
        StationSymbol.Bell -> {
          val p = Path().apply { moveTo(4f,22f); lineTo(24f,22f); moveTo(7f,22f); lineTo(7f,11f); cubicTo(7f,0f,21f,0f,21f,11f); lineTo(21f,22f) }
          drawPath(p, color, style = stroke)
          drawArc(color, 0f, 180f, false, Offset(11f,21f), Size(6f,5f), style = stroke)
        }
        StationSymbol.Message -> {
          val p = Path().apply { moveTo(3f,25f); lineTo(3f,15f); cubicTo(3f,0f,26f,0f,26f,14f); cubicTo(26f,22f,21f,25f,15f,25f); close() }
          drawPath(p, color, style = stroke)
          line(9f,11f,19f,11f); line(9f,16f,19f,16f); line(9f,21f,14f,21f)
        }
        StationSymbol.Menu -> { line(5f,7f,23f,7f); line(5f,14f,23f,14f); line(5f,21f,23f,21f) }
        StationSymbol.Chevron -> path(5f to 9f, 14f to 19f, 23f to 9f)
        StationSymbol.Back -> path(18f to 4f, 7f to 14f, 18f to 24f)
        StationSymbol.Play -> drawPath(Path().apply { moveTo(10f,7f); lineTo(22f,14f); lineTo(10f,21f); close() }, color)
        StationSymbol.More -> { drawCircle(color, 2.1f, Offset(10f,14f)); drawCircle(color, 2.1f, Offset(20f,14f)) }
        StationSymbol.Settings -> {
          path(8f to 4f, 20f to 4f, 26f to 14f, 20f to 24f, 8f to 24f, 2f to 14f, close = true)
          drawCircle(color, 3.3f, Offset(14f, 14f), style = stroke)
        }
        StationSymbol.Search -> {
          drawCircle(color, 9f, Offset(12f, 12f), style = stroke)
          line(19f, 19f, 26f, 26f)
          val glint = Path().apply { moveTo(8f, 8f); quadraticBezierTo(12f, 5f, 16f, 8f) }
          drawPath(glint, color, style = stroke)
        }
      }
    }
  }
}
