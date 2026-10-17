package com.anker.esiotkit.sample.main.mega.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.view.TitleBar
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.dataLogger

/**
 * 批量下载队列页面 — 纯展示
 *
 * 职责：
 * - 通过 collectAsState 观察 DownloadQueueManager.tasks
 * - 显示任务列表、进度、状态
 * - 提供重试、清理操作入口
 *
 * 不持有任何执行状态，不创建 EventMediaPlayer
 * 页面销毁不影响下载执行
 */
class BatchDownloadScreen : Screen {

  @Composable
  override fun Content() {
    val navigator = LocalNavigator.currentOrThrow
    val tasks by DownloadQueueManager.tasks.collectAsState()

    dataLogger.i("EVT-BATCH BatchDownloadScreen: composed, tasks=${tasks.size}")

    Column(modifier = Modifier.fillMaxSize()) {
      TitleBar(
        title = "Batch Download (${tasks.size})",
        onBackClick = { navigator.pop() }
      )

      if (tasks.isEmpty()) {
        Text(
          text = "No download tasks",
          modifier = Modifier.padding(32.dp),
          color = Color.Gray
        )
        return@Column
      }

      // 总进度
      Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        val done = tasks.count { it.status == DownloadStatus.DONE || it.status == DownloadStatus.FAILED }
        Text(
          text = "$done/${tasks.size}",
          fontSize = 16.sp,
          modifier = Modifier.padding(end = 8.dp)
        )
        val overall = if (tasks.isNotEmpty()) done.toFloat() / tasks.size else 0f
        LinearProgressIndicator(
          progress = { overall },
          modifier = Modifier.weight(1f)
        )
        // Cancel All: 停止所有活跃下载并清空队列（Bug #10958）
        if (DownloadQueueManager.isRunning) {
          TextButton(
            onClick = {
              dataLogger.i("EVT-BATCH cancelAllClick: tasks=${tasks.size}, activeDownloads=${DownloadQueueManager.activeDownloads.value.size}")
              DownloadQueueManager.clearAll()
              emitToast("All downloads cancelled")
            },
            modifier = Modifier.padding(start = 8.dp)
          ) {
            Text("Cancel All", fontSize = 12.sp, color = Color.Red)
          }
        }
        // Clear: 清理已完成/失败的历史任务，带结果反馈（Bug #10958）
        TextButton(
          onClick = {
            val removed = DownloadQueueManager.clearFinished()
            dataLogger.i("EVT-BATCH clearClick: removed=$removed, tasks=${tasks.size}")
            emitToast(if (removed > 0) "Cleared $removed finished task(s)" else "No finished tasks to clear")
          },
          modifier = Modifier.padding(start = 8.dp)
        ) {
          Text("Clear", fontSize = 12.sp)
        }
      }

      // 任务列表
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        items(tasks, key = { it.taskId }) { task ->
          TaskRow(task, onRetry = {
            dataLogger.i("EVT-BATCH retry clicked: taskId=${task.taskId}, event=${task.event.megaEventId}")
            DownloadQueueManager.retry(task.taskId)
          })
        }
      }
    }

    LaunchedEffect(DownloadQueueManager.isAllDone, tasks.size) {
      if (DownloadQueueManager.isAllDone && tasks.isNotEmpty()) {
        emitToast("Batch download complete: ${tasks.size} events")
      }
    }
  }

  @Composable
  private fun TaskRow(task: DownloadTask, onRetry: () -> Unit = {}) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      when (task.status) {
        DownloadStatus.PENDING -> Text("⏳", fontSize = 18.sp)
        DownloadStatus.DOWNLOADING -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        DownloadStatus.DONE -> Text("✅", fontSize = 18.sp)
        DownloadStatus.FAILED -> Text("❌", fontSize = 18.sp)
      }

      Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
        Text(text = task.deviceName, fontSize = 13.sp)
        Text(text = task.event.startTime.substringAfter(" "), fontSize = 11.sp, color = Color.Gray)
        val durationSec = (task.event.endTimestamp - task.event.startTimestamp) / 1000
        if (durationSec > 0) {
          Text(text = "Duration: ${durationSec}s", fontSize = 11.sp, color = Color.Gray)
        }
        if (task.status == DownloadStatus.DOWNLOADING) {
          LinearProgressIndicator(
            progress = { task.progress },
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
          )
        }
        if (task.status == DownloadStatus.FAILED && task.errorMsg.isNotEmpty()) {
          Text(text = task.errorMsg, fontSize = 10.sp, color = Color.Red)
        }
        if (task.status == DownloadStatus.FAILED) {
          TextButton(
            onClick = onRetry,
            modifier = Modifier.padding(top = 2.dp)
          ) {
            Text("Retry", fontSize = 11.sp)
          }
        }
      }

      Text(
        text = when (task.status) {
          DownloadStatus.PENDING -> "Waiting"
          DownloadStatus.DOWNLOADING -> "${(task.progress * 100).toInt()}%"
          DownloadStatus.DONE -> "Done"
          DownloadStatus.FAILED -> "Failed"
        },
        fontSize = 11.sp,
        color = when (task.status) {
          DownloadStatus.DONE -> Color(0xFF4CAF50)
          DownloadStatus.FAILED -> Color.Red
          else -> Color.Gray
        }
      )
    }
  }
}
