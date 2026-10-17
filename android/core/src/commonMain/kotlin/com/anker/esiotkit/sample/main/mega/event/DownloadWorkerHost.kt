@file:OptIn(com.anker.esiotkit.support.InternalIotApi::class)

package com.anker.esiotkit.sample.main.mega.event

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anker.esiotkit.media.event_stream.EventMediaPlayer
import com.anker.esiotkit.sample.view.EventStreamPlayView
import com.anker.esiotkit.support.log.dataLogger

@Composable
fun DownloadWorkerHost() {
  val activeDownloads by DownloadQueueManager.activeDownloads.collectAsState()

  activeDownloads.forEach { job ->
    androidx.compose.runtime.key(job.taskId) {
      DownloadWorkerSlot(
        key = job.taskId,
        activeJob = job,
      )
    }
  }
}

@Composable
private fun DownloadWorkerSlot(
  key: String,
  activeJob: ActiveDownloadJob,
) {
  androidx.compose.runtime.key(key) {
    val task = DownloadQueueManager.tasks.value.find { it.taskId == key }

    if (task != null) {
      dataLogger.i("EVT-BATCH DownloadWorkerSlot: mounting player for taskId=$key, event=${task.event.megaEventId}")

      val player = androidx.compose.runtime.remember { EventMediaPlayer(task.event, task.device) }

      EventStreamPlayView(
        modifier = Modifier.size(1.dp),
        player = player,
        onVideoViewReady = {
          if (!activeJob.playerDeferred.isCompleted) {
            dataLogger.i("EVT-BATCH DownloadWorkerSlot: completing playerDeferred (factory done) for taskId=$key")
            activeJob.playerDeferred.complete(player)
          }
        },
      )

      DisposableEffect(player) {
        onDispose {
          dataLogger.i("EVT-BATCH DownloadWorkerSlot: disposing player for taskId=$key")
        }
      }
    } else {
      dataLogger.w("EVT-BATCH DownloadWorkerSlot: task not found for taskId=$key")
    }
  }
}
