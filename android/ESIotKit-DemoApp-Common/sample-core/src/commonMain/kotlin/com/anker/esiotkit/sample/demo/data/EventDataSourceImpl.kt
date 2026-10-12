@file:OptIn(com.anker.esiotkit.support.InternalIotApi::class)

package com.anker.esiotkit.sample.demo.data

import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.domain.model.MegaEvent
import com.anker.esiotkit.event.model.EventCondition
import com.anker.esiotkit.manager.MegaEventManager
import com.anker.esiotkit.media.VideoInfo
import com.anker.esiotkit.media.event_stream.EventDownloadState
import com.anker.esiotkit.media.event_stream.EventMediaPlayer
import com.anker.esiotkit.media.event_stream.EventPlayListener
import com.anker.esiotkit.media.event_stream.EventPlayerState
import com.anker.esiotkit.network.constants.NetConstant
import com.anker.esiotkit.sample.main.mega.media.stopAndRelease
import com.anker.esiotkit.sample.demo.progress.DemoProgress
import com.anker.esiotkit.sample.model.isOnline
import com.anker.esiotkit.support.ktx.toDateStart
import com.anker.esiotkit.support.log.dataLogger
import com.anker.esiotkit.support.platform.currentTimeMillis
import com.anker.esiotkit.support.platform.ensureDirectoryExists
import com.anker.esiotkit.support.platform.getDocumentFilePath
import com.anker.esiotkit.support.platform.platformFileSystem
import com.anker.esiotkit.support.utils.DateUtil
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toPath

/**
 * IEventDataSource 的 SDK 实现（参考 AutoCaptureScreen 的已验证链路）。
 *
 * 关键点：
 * - `MegaEventManager.query` 为回调式 API，统一包装为 suspendCancellableCoroutine
 * - `getEventImageBase64` 失败时回退到 event.pictures 列表
 * - 视频下载使用 EventMediaPlayer.startDownload（SDK 回调返回实际文件路径）
 */
class EventDataSourceImpl : IEventDataSource {

  companion object {
    private const val TAG = "EventDataSourceImpl"
    private const val QUERY_TIMEOUT_MS = 20_000L
    private const val THUMB_TIMEOUT_MS = 15_000L
    private const val DOWNLOAD_TIMEOUT_MS = 120_000L
  }

  override suspend fun fetchTodayEvents(dayOffset: Int, deviceSn: String): List<MegaEvent> {
    dataLogger.i("$TAG/fetchTodayEvents/start: dayOffset=$dayOffset deviceSn=${deviceSn.ifBlank { "<all>" }}")
    val startMs = currentTimeMillis()
    DemoProgress.start("events", "事件数据提取", "SDK 事件库 + 本地素材")

    val cacheKey = "events:$dayOffset"
    DemoAnalysisCache.getEvents(cacheKey)?.let { cached ->
      dataLogger.i("$TAG/fetchTodayEvents/cache: hit, count=${cached.size}")
      DemoProgress.success("events", "缓存命中 · ${cached.size} 条")
      return cached
    }

    val snList = resolveParentSnList(deviceSn)
    if (snList.isEmpty()) {
      dataLogger.w("$TAG/fetchTodayEvents/empty: no security station found")
      DemoProgress.success("events", "未发现安防基站 · 0 条")
      return emptyList()
    }

    val now = currentTimeMillis()
    val dateTime = DateUtil.timeMillisToDateTimeString(now)
    val todayStart = DateUtil.dateTimeStringToTimeMillis(
      try { dateTime.toDateStart() } catch (_: Exception) { dateTime }
    )
    val dayStart = todayStart - dayOffset * 24L * 60 * 60 * 1000
    val dayEnd = dayStart + 24L * 60 * 60 * 1000

    val condition = EventCondition(
      _parentSnList = snList,
      _startTime = dayStart,
      _endTime = dayEnd,
    )

    val events = withTimeoutOrNull(QUERY_TIMEOUT_MS) {
      withContext(Dispatchers.IO) {
        suspendCancellableCoroutine<List<MegaEvent>> { cont ->
          MegaEventManager.query(
            condition, refresh = true, lastEventTime = 0,
            onSuccess = { list ->
              if (cont.isActive) cont.resume(list.distinctBy { it.megaEventId })
            },
            onFailure = { code, msg ->
              dataLogger.e("$TAG/fetchTodayEvents/failure: code=$code msg=$msg")
              DemoProgress.fail("events", "查询失败 · code=$code")
              if (cont.isActive) cont.resumeWithException(
                IllegalStateException("event query failed: code=$code msg=$msg")
              )
            }
          )
        }
      }
    } ?: run {
      dataLogger.w("$TAG/fetchTodayEvents/timeout: ${QUERY_TIMEOUT_MS}ms")
      DemoProgress.fail("events", "查询超时 ${QUERY_TIMEOUT_MS}ms")
      throw IllegalStateException("event query timeout: ${QUERY_TIMEOUT_MS}ms")
    }

    val elapsed = currentTimeMillis() - startMs
    dataLogger.i("$TAG/fetchTodayEvents/success: eventCount=${events.size} elapsed=${elapsed}ms deviceSn=${deviceSn.ifBlank { "<all>" }}")
    events.forEach { e ->
      dataLogger.i("$TAG/fetchTodayEvents/event: id=${e.megaEventId} deviceSn=${e.deviceSn} startTime=${e.startTime} videoPath=${e.videoPath.ifBlank { "<none>" }}")
    }

    // 本地样本补位：追加在真实事件之后，分析上限（5/10）会优先消耗真实事件
    val localSamples = LocalSampleSource.load()
    val merged = if (localSamples.isEmpty()) events else events + localSamples
    if (localSamples.isNotEmpty()) {
      dataLogger.i("$TAG/fetchTodayEvents/merge: real=${events.size} localSample=${localSamples.size} total=${merged.size}")
    }

    DemoAnalysisCache.putEvents(cacheKey, merged)
    val totalElapsed = currentTimeMillis() - startMs
    dataLogger.i("$TAG/fetchTodayEvents/cache: miss, stored count=${merged.size}, elapsedMs=$totalElapsed")
    DemoProgress.success("events", "实时拉取 · 真实${events.size} + 素材${localSamples.size} = ${merged.size} 条 · ${totalElapsed}ms")
    return merged
  }

  override suspend fun fetchThumbnailBase64(event: MegaEvent): String? {
    val picture = event.thumbnail
    val path = picture?.picturePath ?: ""
    val sn = picture?.parentSn ?: ""
    dataLogger.i("$TAG/fetchThumbnailBase64/start: eventId=${event.megaEventId} path=$path")

    DemoAnalysisCache.getThumbnail(event.megaEventId)?.let { cached ->
      dataLogger.i("$TAG/fetchThumbnailBase64/cache: hit, eventId=${event.megaEventId}")
      return cached
    }

    if (LocalSampleSource.isLocalSampleEvent(event)) {
      val local = LocalSampleSource.readThumbnailBase64(event)
      if (!local.isNullOrEmpty()) {
        DemoAnalysisCache.putThumbnail(event.megaEventId, local)
        dataLogger.i("$TAG/fetchThumbnailBase64/success: source=local_sample, eventId=${event.megaEventId}, base64Length=${local.length}")
        return local
      }
      dataLogger.w("$TAG/fetchThumbnailBase64/failure: eventId=${event.megaEventId} local sample unreadable (text fallback)")
      return null
    }

    // 主路径：event.thumbnail
    val primary = if (path.isNotBlank() && sn.isNotBlank()) {
      requestImageBase64(path, sn, event.megaEventId)
    } else null
    if (!primary.isNullOrEmpty()) {
      DemoAnalysisCache.putThumbnail(event.megaEventId, primary)
      dataLogger.i("$TAG/fetchThumbnailBase64/success: eventId=${event.megaEventId} base64Length=${primary.length}")
      dataLogger.i("$TAG/fetchThumbnailBase64/cache: miss, stored eventId=${event.megaEventId}, base64Length=${primary.length}")
      return primary
    }

    // 回退：event.pictures 列表中的其他图片
    for (pic in event.pictures) {
      if (pic.picturePath.isBlank() || pic.parentSn.isBlank()) continue
      val alt = requestImageBase64(pic.picturePath, pic.parentSn, event.megaEventId)
      if (!alt.isNullOrEmpty()) {
        DemoAnalysisCache.putThumbnail(event.megaEventId, alt)
        dataLogger.i("$TAG/fetchThumbnailBase64/success(alt-pic): eventId=${event.megaEventId} base64Length=${alt.length}")
        dataLogger.i("$TAG/fetchThumbnailBase64/cache: miss, stored eventId=${event.megaEventId}, base64Length=${alt.length}")
        return alt
      }
    }

    dataLogger.w("$TAG/fetchThumbnailBase64/failure: eventId=${event.megaEventId} no thumbnail data (text fallback)")
    return null
  }

  override suspend fun downloadEventVideo(event: MegaEvent): String? {
    if (event.videoPath.isBlank()) {
      dataLogger.w("$TAG/downloadEventVideo/skip: eventId=${event.megaEventId} videoPath is blank")
      return null
    }
    val device = MegaDeviceManager.getDeviceBy(event.deviceSn) ?: run {
      dataLogger.w("$TAG/downloadEventVideo/skip: device not found sn=${event.deviceSn}")
      return null
    }

    val dir = getDocumentFilePath().toPath().resolve("aidemo_videos")
    platformFileSystem.ensureDirectoryExists(dir)
    val dest = dir.resolve("${event.megaEventId}.mp4")

    dataLogger.i("$TAG/downloadEventVideo/start: eventId=${event.megaEventId} dest=$dest")
    val startMs = currentTimeMillis()
    val deferred = CompletableDeferred<Pair<Boolean, String?>>()

    val player = withContext(Dispatchers.Main) {
      EventMediaPlayer(event, device).apply {
        setPlayListener(object : EventPlayListener {
          override fun onPlayState(status: EventPlayerState, message: String) {}
          override fun onDownloadState(status: EventDownloadState, progress: Float, message: String) {
            dataLogger.i("$TAG/downloadEventVideo/state: eventId=${event.megaEventId} status=${status.name} progress=$progress msg=$message")
            when (status) {
              EventDownloadState.Success -> deferred.complete(true to message.ifEmpty { null })
              EventDownloadState.Failed -> deferred.complete(false to null)
              EventDownloadState.Timeout -> deferred.complete(false to null)
              else -> {}
            }
          }
          override fun onMuteState(isMute: Boolean) {}
          override fun onProgress(progress: Float) {}
          override fun onVideoInfo(info: VideoInfo) {}
        })
        // 压测结论：直接 startDownload 即可，无需先 startPlay
        startDownload(path = dest.toString())
      }
    }

    val result = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { deferred.await() }
    withContext(Dispatchers.Main) {
      try { player.stopAndRelease() } catch (_: Exception) {}
    }

    val elapsed = currentTimeMillis() - startMs
    if (result == null) {
      dataLogger.e("$TAG/downloadEventVideo/failure: eventId=${event.megaEventId} timeout ${DOWNLOAD_TIMEOUT_MS}ms")
      return null
    }
    if (!result.first) {
      dataLogger.e("$TAG/downloadEventVideo/failure: eventId=${event.megaEventId} failed elapsed=${elapsed}ms")
      return null
    }

    // SDK 可能上报其自身落盘路径，优先确认 bizPath
    val sdkPath = result.second
    val finalPath = when {
      platformFileSystem.exists(dest) -> dest.toString()
      sdkPath != null && platformFileSystem.exists(sdkPath.toPath()) -> sdkPath
      else -> null
    }
    if (finalPath == null) {
      dataLogger.e("$TAG/downloadEventVideo/failure: eventId=${event.megaEventId} file not found after success")
      return null
    }
    dataLogger.i("$TAG/downloadEventVideo/success: eventId=${event.megaEventId} path=$finalPath elapsed=${elapsed}ms")
    return finalPath
  }

  override suspend fun isDeviceOnline(deviceSn: String): Boolean {
    // 本地样本不是真实设备，直接判定在线，避免样本事件把整体流程拉成"全部离线"
    if (deviceSn == LocalSampleSource.DEVICE_SN) {
      dataLogger.i("$TAG/isDeviceOnline: deviceSn=$deviceSn online=true source=local_sample")
      return true
    }
    val device = MegaDeviceManager.getDeviceBy(deviceSn) ?: return false
    val online = runCatching { device.isOnline() }.getOrDefault(true)
    dataLogger.i("$TAG/isDeviceOnline: deviceSn=$deviceSn online=$online")
    return online
  }

  // ─── private ───

  private fun resolveParentSnList(deviceSn: String): List<String> {
    val all = MegaDeviceManager.deviceList.value
    return if (deviceSn.isBlank()) {
      all.filter { it.deviceSn == it.parentSn }
        .filter { it.category == NetConstant.CATEGORY_SECURITY }
        .map { it.parentSn }
        .distinct()
    } else {
      val device = MegaDeviceManager.getDeviceBy(deviceSn)
      val parentSn = device?.parentSn?.takeIf { it.isNotBlank() } ?: deviceSn
      listOf(parentSn)
    }
  }

  private suspend fun requestImageBase64(path: String, sn: String, eventId: String): String? =
    withTimeoutOrNull(THUMB_TIMEOUT_MS) {
      suspendCancellableCoroutine<String?> { cont ->
        MegaEventManager.getEventImageBase64(
          path, sn,
          onSuccess = { b64 -> if (cont.isActive) cont.resume(b64) },
          onFailure = { code, msg ->
            dataLogger.e("$TAG/requestImageBase64/failure: eventId=$eventId code=$code msg=$msg")
            if (cont.isActive) cont.resume(null)
          }
        )
      }
    }
}
