package com.anker.esiotkit.sample.main.station.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val StationPageBackground = Color(0xFFF6F6F6)
internal val StationPageInk = Color(0xFF353535)

internal enum class StationPageAction { Settings, Search }

/** Coordinates are relative to the host's safe content area; system bars are handled by the host. */
@Composable
internal fun StationPageTopBar(title: String, onBack: () -> Unit, action: StationPageAction,
  onAction: () -> Unit) {
  Spacer(Modifier.height(17.dp))
  Box(Modifier.fillMaxWidth().height(56.dp)) {
    IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(48.dp)) {
      StationPageIcon(StationPageGlyph.Back, "返回")
    }
    Text(title, modifier = Modifier.align(Alignment.Center), color = Color.Black,
      fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
    IconButton(onClick = onAction, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).size(48.dp)) {
      StationPageIcon(if (action == StationPageAction.Settings) StationPageGlyph.Settings else StationPageGlyph.Search,
        if (action == StationPageAction.Settings) "设置" else "搜索审核记录")
    }
  }
}

@Composable
internal fun StationNotificationBanner(onNotificationSettings: () -> Unit) {
  var showHelp by rememberSaveable { mutableStateOf(false) }
  val yellow = Color(0xFFFFC400)
  Row(
    Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 70.dp)
      .background(Color(0xFFF5EBC0), RoundedCornerShape(11.dp))
      .padding(start = 23.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text("无法收到APP消息？\n请开启手机通知", Modifier.weight(1f), color = yellow,
      fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold)
    IconButton(onClick = { showHelp = true }, modifier = Modifier.size(36.dp).semantics { contentDescription = "通知帮助" }) {
      Box(Modifier.size(22.dp).background(yellow, CircleShape), contentAlignment = Alignment.Center) {
        Text("?", color = Color.White, fontSize = 20.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
      }
    }
    Spacer(Modifier.width(1.dp))
    TextButton(onClick = onNotificationSettings, modifier = Modifier.width(60.dp).height(48.dp),
      contentPadding = PaddingValues(0.dp)) {
      Box(Modifier.fillMaxWidth().heightIn(min = 24.dp).background(yellow, CircleShape), contentAlignment = Alignment.Center) {
        Text("启用", color = Color.White, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
      }
    }
  }
  if (showHelp) AlertDialog(
    onDismissRequest = { showHelp = false }, title = { Text("开启手机通知") },
    text = { Text("在系统设置中允许本应用发送通知。勿扰模式和省电设置也可能影响消息送达。") },
    confirmButton = { TextButton(onClick = { showHelp = false; onNotificationSettings() }) { Text("前往设置") } },
    dismissButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } }
  )
}

internal enum class StationPageGlyph { Back, Settings, Search, Chevron, Picture }

@Composable
internal fun StationPageIcon(glyph: StationPageGlyph, description: String?, modifier: Modifier = Modifier.size(24.dp),
  color: Color = StationPageInk) {
  Canvas(modifier.semantics { description?.let { contentDescription = it } }) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter)
    fun path(vararg points: Pair<Float, Float>, closed: Boolean = false) {
      val p = Path().apply {
        moveTo(points[0].first * w, points[0].second * h)
        points.drop(1).forEach { lineTo(it.first * w, it.second * h) }
        if (closed) close()
      }
      drawPath(p, color, style = stroke)
    }
    when (glyph) {
      StationPageGlyph.Back -> path(.67f to .18f, .30f to .5f, .67f to .82f)
      StationPageGlyph.Chevron -> path(.30f to .18f, .67f to .5f, .30f to .82f)
      StationPageGlyph.Settings -> {
        path(.28f to .14f, .72f to .14f, .92f to .5f, .72f to .86f, .28f to .86f, .08f to .5f, closed = true)
        drawCircle(color, w * .12f, Offset(w / 2, h / 2), style = stroke)
      }
      StationPageGlyph.Search -> {
        drawCircle(color, w * .34f, Offset(w * .45f, h * .43f), style = stroke)
        path(.7f to .69f, .92f to .92f)
        val p = Path().apply { moveTo(w * .3f, h * .29f); quadraticBezierTo(w * .44f, h * .15f, w * .57f, h * .29f) }
        drawPath(p, color, style = stroke)
      }
      StationPageGlyph.Picture -> {
        val p = Path().apply {
          moveTo(w * .07f, h * .12f); lineTo(w * .93f, h * .12f)
          quadraticBezierTo(w * .97f, h * .12f, w * .97f, h * .17f)
          lineTo(w * .97f, h * .87f); lineTo(w * .03f, h * .87f); lineTo(w * .03f, h * .17f)
          quadraticBezierTo(w * .03f, h * .12f, w * .07f, h * .12f); close()
        }
        drawPath(p, color, style = stroke)
        drawCircle(color, w * .04f, Offset(w * .27f, h * .33f), style = stroke)
        path(.04f to .72f, .27f to .50f, .41f to .60f, .55f to .43f, .96f to .72f)
      }
    }
  }
}
