@file:OptIn(com.anker.esiotkit.support.InternalIotApi::class)

package com.anker.esiotkit.sample.main.mega.event

import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.media.VideoInfo
import com.anker.esiotkit.media.event_stream.EventDownloadState
import com.anker.esiotkit.media.event_stream.EventMediaPlayer
import com.anker.esiotkit.media.event_stream.EventPlayListener
import com.anker.esiotkit.media.event_stream.EventPlayerState
import com.anker.esiotkit.sample.main.mega.media.stopAndRelease
import com.anker.esiotkit.support.log.dataLogger
import com.anker.esiotkit.support.platform.currentTimeMillis
import com.anker.esiotkit.support.platform.ensureDirectoryExists
import com.anker.esiotkit.support.platform.getDocumentFilePath
import com.anker.esiotkit.support.platform.platformFileSystem
import com.anker.esiotkit.sample.support.platform.saveVideo
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath

enum class DownloadStatus { PENDING, DOWNLOADING, DONE, FAILED }

data class DownloadTask(
  val taskId: String,
  val event: MegaEvent,
  val device: MegaDevice,
  val deviceSn: String,
  val deviceName: String,
  val status: DownloadStatus = DownloadStatus.PENDING,
  val progress: Float = 0f,
  val errorMsg: String = "",
  val savePath: String? = null,
  val createdAt: Long,
)

/**
 * 活跃下载 Job — 协程等待 Composable 提供 player
 */
data class ActiveDownloadJob(
  val taskId: String,
  val deviceSn: String,
  val playerDeferred: CompletableDeferred<EventMediaPlayer>,
  val coroutineJob: Job,
)

/**
 * Application 级下载队列管理器
 *
 * 职责：任务数据管理 + 协程调度 + 下载执行
 * 生命周期：App 进程级，页面销毁不影响下载
 *
 * 与 UI 解耦：UI 通过 tasks StateFlow 观察状态，纯展示
 * iOS UIKitView 宿主通过 activeDownloads StateFlow 观察，在 DownloadWorkerHost 中创建 player
 */
object DownloadQueueManager {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
  val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

  private val _activeDownloads = MutableStateFlow<List<ActiveDownloadJob>>(emptyList())
  val activeDownloads: StateFlow<List<ActiveDownloadJob>> = _activeDownloads.asStateFlow()

  val totalDone: Int
    get() = _tasks.value.count { it.status == DownloadStatus.DONE }

  val isRunning: Boolean
    get() = _tasks.value.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }

  val isAllDone: Boolean
    get() = _tasks.value.isNotEmpty() && _tasks.value.all { it.status == DownloadStatus.DONE || it.status == DownloadStatus.FAILED }

  // 每设备活跃下载 Job，用于串行控制
  private val deviceJobs = mutableMapOf<String, Job>()
  private val deviceJobsLock = kotlinx.atomicfu.locks.SynchronizedObject()
  private val pendingJobSlots = mutableMapOf<String, ActiveDownloadJob>()

  private val maxConcurrent = 2

  /**
   * 添加任务到队列 — 追加模式，不清空已有任务
   * 按 eventId 去重
   */
  fun addToQueue(events: List<MegaEvent>) {
    if (events.isEmpty()) {
      dataLogger.i("EVT-BATCH addToQueue: events empty, skip")
      return
    }

    val currentTaskIds = _tasks.value.map { it.event.megaEventId }.toSet()
    val newTasks = events.mapNotNull { event ->
      if (event.megaEventId in currentTaskIds) {
        dataLogger.i("EVT-BATCH addToQueue: skip duplicate eventId=${event.megaEventId}")
        return@mapNotNull null
      }
      val device = MegaDeviceManager.getDeviceBy(event.deviceSn) ?: run {
        dataLogger.w("EVT-BATCH addToQueue: device not found for sn=${event.deviceSn}, skip eventId=${event.megaEventId}")
        return@mapNotNull null
      }
      DownloadTask(
        taskId = "${event.megaEventId}_${currentTimeMillis()}",
        event = event,
        device = device,
        deviceSn = event.deviceSn,
        deviceName = event.deviceName,
        createdAt = currentTimeMillis(),
      )
    }

    if (newTasks.isEmpty()) {
      dataLogger.w("EVT-BATCH addToQueue: no new tasks after dedup, events=${events.size}")
      return
    }

    _tasks.update { it + newTasks }
    dataLogger.i("EVT-BATCH addToQueue: +${newTasks.size} tasks, total=${_tasks.value.size} tasks")
    newTasks.forEach { t ->
      dataLogger.i("EVT-BATCH task added: taskId=${t.taskId}, event=${t.event.megaEventId}, deviceSn=${t.deviceSn}, videoPath=${t.event.videoPath}")
    }

    // 按设备分组触发调度
    newTasks.groupBy { it.deviceSn }.keys.forEach { sn ->
      scheduleNext(sn)
    }
  }

  /**
   * 重试单个任务
   */
  fun retry(taskId: String) {
    val task = _tasks.value.find { it.taskId == taskId } ?: run {
      dataLogger.w("EVT-BATCH retry: task not found, taskId=$taskId")
      return
    }
    dataLogger.i("EVT-BATCH retry: taskId=$taskId, event=${task.event.megaEventId}")

    updateTask(taskId, status = DownloadStatus.PENDING, progress = 0f, errorMsg = "")
    scheduleNext(task.deviceSn)
  }

  /**
   * 清理已完成和失败的任务
   * Bug #10958: 返回清理数量，供 UI 层 toast 反馈（无可清理时用户需要知道按钮作用）
   */
  fun clearFinished(): Int {
    val before = _tasks.value.size
    val doneCount = _tasks.value.count { it.status == DownloadStatus.DONE }
    val failedCount = _tasks.value.count { it.status == DownloadStatus.FAILED }
    _tasks.update { tasks -> tasks.filter { it.status != DownloadStatus.DONE && it.status != DownloadStatus.FAILED } }
    val removed = before - _tasks.value.size
    dataLogger.i("EVT-BATCH clearFinished/result: removed=$removed (done=$doneCount, failed=$failedCount), before=$before, remaining=${_tasks.value.size}")
    return removed
  }

  /**
   * 清空所有任务（取消活跃下载）
   * Bug #10958: 接线到 UI 的 Cancel All 按钮。
   * 先调用 SDK 层 stopDownload() 主动停流（player 已创建时），再取消协程兜底释放。
   */
  fun clearAll() {
    dataLogger.i("EVT-BATCH clearAll/start: activeDownloads=${_activeDownloads.value.size}, tasks=${_tasks.value.size}, statuses=${_tasks.value.groupBy { it.status }.mapKeys { it.key.name }}")
    _activeDownloads.value.forEach { job ->
      // player 已由 DownloadWorkerHost 创建时，先让 SDK 层主动停止下载流；
      // player 未创建（PENDING 等待期）时只需取消协程
      job.playerDeferred.takeIf { it.isCompleted }?.getCompleted()?.let { player ->
        try {
          player.stopDownload()
          dataLogger.i("EVT-BATCH clearAll/stopDownload: taskId=${job.taskId}")
        } catch (e: Exception) {
          dataLogger.i("EVT-BATCH clearAll/stopDownload/exception: taskId=${job.taskId}, error=${e.message}")
        }
      }
      job.coroutineJob.cancel()
      job.playerDeferred.cancel()
      dataLogger.i("EVT-BATCH clearAll/cancelJob: taskId=${job.taskId}")
    }
    _activeDownloads.value = emptyList()
    kotlinx.atomicfu.locks.synchronized(deviceJobsLock) {
      deviceJobs.clear()
    }
    _tasks.value = emptyList()
    dataLogger.i("EVT-BATCH clearAll/result: cleared all, activeDownloads=0, tasks=0")
  }

  // ─── 内部调度 ───

  private fun scheduleNext(deviceSn: String) {
    kotlinx.atomicfu.locks.synchronized(deviceJobsLock) {
      deviceJobs[deviceSn]?.let { if (it.isActive) return }

      val activeCount = _activeDownloads.value.size
      if (activeCount >= maxConcurrent) {
        dataLogger.i("EVT-BATCH scheduleNext: maxConcurrent=$maxConcurrent reached, activeCount=$activeCount, deviceSn=$deviceSn waiting")
        return
      }

      val nextTask = _tasks.value.find { it.deviceSn == deviceSn && it.status == DownloadStatus.PENDING } ?: run {
        dataLogger.i("EVT-BATCH scheduleNext: no PENDING task for deviceSn=$deviceSn")
        return
      }

      dataLogger.i("EVT-BATCH scheduleNext: starting taskId=${nextTask.taskId}, deviceSn=$deviceSn, activeCount=${activeCount + 1}")

      // 先同步占坑再 launch：占坑与并发检查必须在同一临界区内，
      // 否则多设备背靠背 addToQueue 时各 scheduleNext 都读到旧 size，并发上限会被突破
      val placeholderJob = SupervisorJob()
      val activeJob = ActiveDownloadJob(
        taskId = nextTask.taskId,
        deviceSn = deviceSn,
        playerDeferred = CompletableDeferred(),
        coroutineJob = placeholderJob,
      )
      _activeDownloads.update { it + activeJob }
      pendingJobSlots[nextTask.taskId] = activeJob

      val job = scope.launch {
        executeDownload(nextTask, activeJob)
      }
      deviceJobs[deviceSn] = job
    }
  }

  private suspend fun executeDownload(task: DownloadTask, reservedSlot: ActiveDownloadJob) {
    dataLogger.i("EVT-BATCH executeDownload: taskId=${task.taskId}, event=${task.event.megaEventId}, deviceSn=${task.deviceSn}, videoPath=${task.event.videoPath}")

    updateTask(task.taskId, status = DownloadStatus.DOWNLOADING)

    kotlinx.atomicfu.locks.synchronized(deviceJobsLock) {
      pendingJobSlots.remove(task.taskId)
    }
    _activeDownloads.update { jobs ->
      jobs.map { if (it.taskId == task.taskId) it.copy(coroutineJob = kotlin.coroutines.coroutineContext[Job]!!) else it }
    }
    val playerDeferred = reservedSlot.playerDeferred
    dataLogger.i("EVT-BATCH executeDownload: waiting for player from WorkerHost, taskId=${task.taskId}")

    var player: EventMediaPlayer? = null
    try {
      player = withTimeout(10_000) {
        playerDeferred.await()
      }
      dataLogger.i("EVT-BATCH executeDownload: player received, taskId=${task.taskId}")

      var result = withTimeout(120_000) {
        performDownload(player, task)
      }

      if (!result.first && player != null) {
        dataLogger.w("EVT-BATCH executeDownload: first attempt FAILED, retrying once with same player, taskId=${task.taskId}")
        try { player.stopPlay() } catch (_: Exception) {}
        kotlinx.coroutines.delay(500)
        result = withTimeout(120_000) {
          performDownload(player!!, task)
        }
      }

      if (result.first) {
        val sdkPath = result.second ?: getDownloadPath(task)
        dataLogger.i("EVT-BATCH executeDownload: SUCCESS, taskId=${task.taskId}, sdkPath=$sdkPath")
        saveVideo(sdkPath) { savedToAlbum ->
          if (savedToAlbum) {
            dataLogger.i("EVT-BATCH executeDownload: saved to album, taskId=${task.taskId}, path=$sdkPath")
          } else {
            dataLogger.e("EVT-BATCH executeDownload: save to album failed, taskId=${task.taskId}")
          }
        }
        updateTask(task.taskId, status = DownloadStatus.DONE, progress = 1f, savePath = sdkPath)
      } else {
        dataLogger.w("EVT-BATCH executeDownload: FAILED, taskId=${task.taskId}")
        updateTask(task.taskId, status = DownloadStatus.FAILED, errorMsg = "download failed")
      }

    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
      dataLogger.e("EVT-BATCH executeDownload: TIMEOUT, taskId=${task.taskId}")
      updateTask(task.taskId, status = DownloadStatus.FAILED, errorMsg = "timeout")
    } catch (e: Exception) {
      dataLogger.e("EVT-BATCH executeDownload: EXCEPTION, taskId=${task.taskId}, error=${e.message}")
      updateTask(task.taskId, status = DownloadStatus.FAILED, errorMsg = e.message ?: "error")
    } finally {
      player?.let {
        dataLogger.i("EVT-BATCH executeDownload: finally, stopAndRelease for taskId=${task.taskId}")
        try { it.stopAndRelease() } catch (e: Exception) { dataLogger.i("EVT-BATCH executeDownload: stopAndRelease exc: ${e.message}") }
      }
      _activeDownloads.update { jobs -> jobs.filter { it.taskId != task.taskId } }
      dataLogger.i("EVT-BATCH executeDownload: finally, removed activeJob, activeDownloads=${_activeDownloads.value.size}, taskId=${task.taskId}")
      kotlinx.atomicfu.locks.synchronized(deviceJobsLock) {
        deviceJobs.remove(task.deviceSn)
      }
      dataLogger.i("EVT-BATCH executeDownload: scheduling next for deviceSn=${task.deviceSn}")
      scheduleNext(task.deviceSn)
      tryScheduleWaitingDevices()
    }
  }

  /**
   * 尝试调度等待中的设备（全局并发有空位时）
   */
  private fun tryScheduleWaitingDevices() {
    val pendingDeviceSns = _tasks.value
      .filter { it.status == DownloadStatus.PENDING }
      .map { it.deviceSn }
      .distinct()
    pendingDeviceSns.forEach { sn ->
      val isActive = kotlinx.atomicfu.locks.synchronized(deviceJobsLock) {
        deviceJobs[sn]?.isActive == true
      }
      if (!isActive && _activeDownloads.value.size < maxConcurrent) {
        scheduleNext(sn)
      }
    }
  }

  /**
   * 执行下载 — 回调转协程。返回 (success, sdkReportedPath?)。
   * SDK onSucceed 回调的 message 是真实落盘路径（Movies/xxx.mp4），
   * 业务层用此路径存相册，而非自拼的 batch_download/{taskId}.mp4。
   */
  private suspend fun performDownload(player: EventMediaPlayer, task: DownloadTask): Pair<Boolean, String?> {
    dataLogger.i("EVT-BATCH performDownload: taskId=${task.taskId}, event=${task.event.megaEventId}, videoPath=${task.event.videoPath}")

    return suspendCancellableCoroutine { cont ->
      var downloadPath = ""
      var hasStartedDownload = false
      var sdkReportedPath: String? = null

      player.setPlayListener(object : EventPlayListener {
        override fun onPlayState(status: EventPlayerState, message: String) {
          dataLogger.i("EVT-BATCH onPlayState: taskId=${task.taskId}, status=${status.name}, msg=$message")
          if (status == EventPlayerState.Playing && !hasStartedDownload) {
            hasStartedDownload = true
            try {
              player.pause()
              dataLogger.i("EVT-BATCH pause before mute, taskId=${task.taskId}")
            } catch (e: Exception) {
              dataLogger.i("EVT-BATCH pause failed: ${e.message}, taskId=${task.taskId}")
            }
            try {
              player.setMute(true)
              dataLogger.i("EVT-BATCH setMute(true) confirmed, taskId=${task.taskId}")
            } catch (e: Exception) {
              dataLogger.i("EVT-BATCH setMute(true) failed: ${e.message}, taskId=${task.taskId}")
            }

            val dir = getDocumentFilePath().toPath().resolve("batch_download")
            platformFileSystem.ensureDirectoryExists(dir)
            downloadPath = dir.resolve("${task.taskId}.mp4").toString()
            dataLogger.i("EVT-BATCH startDownload: path=$downloadPath, taskId=${task.taskId}")
            try { player.startDownload(path = downloadPath) } catch (e: Exception) {
              dataLogger.i("EVT-BATCH startDownload exception: ${e.message}, taskId=${task.taskId}")
            }
          }
        }

        override fun onDownloadState(status: EventDownloadState, progress: Float, message: String) {
          val clamped = progress.coerceIn(0f, 1f)
          dataLogger.i("EVT-BATCH onDownloadState: taskId=${task.taskId}, status=${status.name}, progress=$clamped, msg=$message")
          when (status) {
            EventDownloadState.Recording -> {
              updateTask(task.taskId, status = DownloadStatus.DOWNLOADING, progress = clamped)
            }
            EventDownloadState.Success -> {
              sdkReportedPath = message.ifEmpty { null }
              dataLogger.i("EVT-BATCH onDownloadState Success: taskId=${task.taskId}, sdkReportedPath=$sdkReportedPath, bizPath=$downloadPath")
              if (cont.isActive) cont.resumeWith(kotlin.Result.success(true to sdkReportedPath))
            }
            EventDownloadState.Failed -> {
              sdkReportedPath = message.ifEmpty { null }
              dataLogger.w("EVT-BATCH onDownloadState Failed: taskId=${task.taskId}, msg=$message")
              if (cont.isActive) cont.resumeWith(kotlin.Result.success(false to sdkReportedPath))
            }
            EventDownloadState.Timeout -> {
              dataLogger.w("EVT-BATCH onDownloadState Timeout: taskId=${task.taskId}")
              if (cont.isActive) cont.resumeWith(kotlin.Result.success(false to null))
            }
            EventDownloadState.Idle -> {}
          }
        }

        override fun onMuteState(isMute: Boolean) {
          dataLogger.i("EVT-BATCH onMuteState: isMute=$isMute, taskId=${task.taskId}")
        }

        override fun onProgress(progress: Float) {}

        override fun onVideoInfo(info: VideoInfo) {
          dataLogger.i("EVT-BATCH onVideoInfo: taskId=${task.taskId}, info=$info")
        }
      })

      dataLogger.i("EVT-BATCH startPlay: taskId=${task.taskId}")
      try { player.startPlay() } catch (e: Exception) {
        dataLogger.i("EVT-BATCH startPlay exception: ${e.message}, taskId=${task.taskId}")
      }

      cont.invokeOnCancellation {
        dataLogger.i("EVT-BATCH performDownload cancelled: taskId=${task.taskId}")
        try { player.stopAndRelease() } catch (_: Exception) {}
      }
    }
  }

  private fun getDownloadPath(task: DownloadTask): String {
    val dir = getDocumentFilePath().toPath().resolve("batch_download")
    platformFileSystem.ensureDirectoryExists(dir)
    return dir.resolve("${task.taskId}.mp4").toString()
  }

  private fun updateTask(
    taskId: String,
    status: DownloadStatus,
    progress: Float = 0f,
    errorMsg: String = "",
    savePath: String? = null,
  ) {
    _tasks.update { tasks ->
      tasks.map { if (it.taskId == taskId) it.copy(status = status, progress = progress, errorMsg = errorMsg, savePath = savePath) else it }
    }
    val task = _tasks.value.find { it.taskId == taskId }
    if (task != null) {
      dataLogger.i("EVT-BATCH updateTask: taskId=$taskId, status=$status, progress=$progress, totalDone=$totalDone/${_tasks.value.size}")
    }
  }
}
