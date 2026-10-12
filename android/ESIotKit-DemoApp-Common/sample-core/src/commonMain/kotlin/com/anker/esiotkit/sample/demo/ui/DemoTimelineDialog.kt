package com.anker.esiotkit.sample.demo.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.anker.esiotkit.sample.demo.progress.DemoProgress

@Composable
fun DemoTimelineDialog(visible: Boolean, onClose: () -> Unit) {
  if (!visible) return
  val steps by DemoProgress.steps.collectAsState()
  val finished = steps.isNotEmpty() && steps.none { it.status == DemoProgress.Status.RUNNING }
  Dialog(onDismissRequest = onClose) {
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(20.dp),
      color = Color.White,
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("执行过程", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))
          Spacer(Modifier.weight(1f))
          if (finished) {
            Icon(
              imageVector = Icons.Filled.CheckCircle,
              contentDescription = null,
              tint = Color(0xFF1A7F37),
              modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("已完成", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1A7F37))
          }
        }
        Spacer(Modifier.height(4.dp))
        Text("异步节点实时进度 · 数据来源与处理方式", fontSize = 12.sp, color = Color(0xFF8A9199))
        Spacer(Modifier.height(16.dp))
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 430.dp)
            .verticalScroll(rememberScrollState())
            .animateContentSize(),
        ) {
          if (steps.isEmpty()) {
            Text("等待执行…", fontSize = 13.sp, color = Color(0xFF8A9199))
          }
          steps.forEachIndexed { index, step ->
            TimelineStepRow(step = step, isLast = index == steps.lastIndex)
          }
        }
        Spacer(Modifier.height(18.dp))
        Button(
          onClick = onClose,
          modifier = Modifier.fillMaxWidth().height(46.dp),
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF005D8E), contentColor = Color.White),
        ) {
          Text(if (finished) "完成" else "后台继续执行", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
      }
    }
  }
}

@Composable
private fun TimelineStepRow(step: DemoProgress.Step, isLast: Boolean) {
  Row(modifier = Modifier.fillMaxWidth()) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        when (step.status) {
          DemoProgress.Status.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = Color(0xFF005D8E),
          )
          DemoProgress.Status.SUCCESS -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF1A7F37),
            modifier = Modifier.size(18.dp),
          )
          DemoProgress.Status.FAILED -> Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = Color(0xFFD92D20),
            modifier = Modifier.size(18.dp),
          )
        }
      }
      if (!isLast) {
        Box(modifier = Modifier.width(2.dp).height(34.dp).background(Color(0xFFE5EAF0)))
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.padding(top = 1.dp, bottom = if (isLast) 0.dp else 12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = step.title,
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold,
          color = if (step.status == DemoProgress.Status.RUNNING) Color(0xFF005D8E) else Color(0xFF1F1F1F),
        )
        step.elapsedMs?.let { elapsed ->
          Spacer(Modifier.width(6.dp))
          Text("${elapsed}ms", fontSize = 11.sp, color = Color(0xFF8A9199))
        }
      }
      if (step.detail.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(step.detail, fontSize = 12.sp, color = Color(0xFF6B7280), lineHeight = 17.sp)
      }
    }
  }
}
