package com.anker.esiotkit.sample.repository

import com.anker.esiotkit.data_center.export.LocalEventRepository
import com.anker.esiotkit.event.domain.repository.HistoryRecordSearchParams
import com.anker.esiotkit.sample.cache.DeviceCoverCache
import com.anker.esiotkit.sample.support.perf.RepLog
import com.anker.esiotkit.support.di.esInject
import com.anker.esiotkit.support.platform.currentTimeMillis
import com.anker.esiotkit.support.utils.DateUtil
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * T-REP-03: Event 查询与封面图 Repository
 *
 * 负责设备事件查询和封面图管理。
 *
 * 主要职责：
 * - 查询设备最新事件（用于封面图）
 * - 分页查询设备事件列表
 * - 事件缩略图 URL 缓存
 *
 * v2.2.7 增强：`getLatestEvent` / `refreshCoverUrl` / `getCoverUrlWithTimeout`
 * 新增可选参数 `parentSn`。不传 `parentSn`（默认空串）时行为与旧版一致（返回 null / 降级缓存）；
 * 传入 `parentSn` 后执行实际 SDK 查询。
 */
object EventRepository {

  private const val TAG = "EventRepository"
  private const val THUMBNAIL_TIMEOUT_MS = 5_000L // 5 秒超时

  // === 并发控制：per-device Mutex 串行化 ===

  private val mutexGuard = SynchronizedObject()
  private val queryMutexes = mutableMapOf<String, Mutex>()

  private fun getOrCreateMutex(deviceSn: String): Mutex = synchronized(mutexGuard) {
    queryMutexes.getOrPut(deviceSn) { Mutex() }
  }

  // === 依赖获取 ===

  private val localEventRepository: LocalEventRepository? by lazy {
    try {
      esInject<LocalEventRepository>()
    } catch (e: Exception) {
      RepLog.e(TAG) { "LocalEventRepository injection failed: ${e.message}" }
      null
    }
  }

  /**
   * 最新事件数据类
   *
   * @param eventId 事件 ID
   * @param thumbnailUrl 缩略图路径（P2P/本地路径，非 HTTP URL）
   * @param eventType 事件类型
   * @param timestamp 事件时间戳
   */
  data class LatestEvent(
    val eventId: String,
    val thumbnailUrl: String?,
    val eventType: String,
    val timestamp: Long
  )

  /**
   * 事件分页结果
   *
   * @param events 事件列表
   * @param hasNextPage 是否有下一页
   * @param totalCount 总事件数
   */
  data class EventPageResult(
    val events: List<EventItem>,
    val hasNextPage: Boolean,
    val totalCount: Int
  )

  /**
   * 事件数据类
   *
   * @param eventId 事件 ID
   * @param deviceSn 设备 SN
   * @param deviceName 设备名称
   * @param thumbnailUrl 缩略图 URL
   * @param eventType 事件类型
   * @param timestamp 事件时间戳
   * @param duration 事件持续时长（毫秒）
   */
  data class EventItem(
    val eventId: String,
    val deviceSn: String,
    val deviceName: String,
    val thumbnailUrl: String?,
    val eventType: String,
    val timestamp: Long,
    val duration: Long
  )

  /**
   * 获取设备最新事件
   *
   * v2.2.7 增强：传入 `parentSn` 后通过 SDK 查询最新事件。
   * 不传 `parentSn`（默认空串）时保留旧行为（返回 null）。
   *
   * @param deviceId 设备 SN
   * @param parentSn 基站 SN（传入后执行实际查询；默认空串保留旧行为）
   * @return 最新事件，无事件时返回 null
   */
  suspend fun getLatestEvent(deviceId: String, parentSn: String = ""): Result<LatestEvent?> {
    // 向后兼容：parentSn 为空时保留旧行为（返回 null）
    if (parentSn.isBlank()) {
      return Result.success(null)
    }

    val mutex = getOrCreateMutex(deviceId)
    return try {
      mutex.withLock {
        queryLatestEventInternal(deviceId, parentSn)
      }
    } catch (e: CancellationException) {
      // 不吞 CancellationException，向上传播以维护结构化并发
      throw e
    } catch (e: Exception) {
      RepLog.e(TAG) { "[SN=$deviceId] getLatestEvent: FAILED — ${e.message}" }
      Result.failure(e)
    }
  }

  /**
   * 分页查询设备事件列表
   *
   * @param deviceId 设备 SN
   * @param page 页码（从 0 开始）
   * @param pageSize 每页数量
   * @return 分页事件结果
   */
  suspend fun getEventList(
    deviceId: String,
    page: Int,
    pageSize: Int
  ): Result<EventPageResult> {
    val startTime = currentTimeMillis()
    return try {
      // TODO: 对接 ESIotKit SDK 分页查询
      // val sdkEvents = fetchEventsFromSdk(deviceId, page, pageSize)
      // val events = sdkEvents.map { it.toEventItem() }
      // val hasNextPage = sdkEvents.size == pageSize
      // Result.success(EventPageResult(events, hasNextPage, totalCount))

      // 占位实现
      Result.success(EventPageResult(emptyList(), false, 0))
    } catch (e: Exception) {
      val elapsed = currentTimeMillis() - startTime
      RepLog.e(TAG) { "[SN=$deviceId] getEventList: FAILED (page=$page, size=$pageSize, ${elapsed}ms) — ${e.message}" }
      Result.failure(e)
    }
  }

  /**
   * 获取封面图 URL（优先从缓存获取）
   *
   * @param deviceId 设备 SN
   * @return 封面图 URL，无缓存时返回 null
   */
  fun getCoverUrl(deviceId: String): String? {
    val url = DeviceCoverCache.get(deviceId)
    if (url != null) RepLog.d(TAG) { "[SN=$deviceId] getCoverUrl: cache HIT" }
    return url
  }

  /**
   * 刷新封面图（从最新事件获取缩略图）
   *
   * v2.2.7 增强：传入 `parentSn` 后执行实际查询并缓存。
   * 不传 `parentSn`（默认空串）时保留旧行为（getLatestEvent 返回 null → 不写缓存）。
   *
   * @param deviceId 设备 SN
   * @param parentSn 基站 SN（传入后执行实际查询；默认空串保留旧行为）
   * @return 刷新后的封面图 URL
   */
  suspend fun refreshCoverUrl(deviceId: String, parentSn: String = ""): Result<String?> {
    val startTime = currentTimeMillis()
    return try {
      val latestEvent = getLatestEvent(deviceId, parentSn).getOrNull()
      val coverUrl = latestEvent?.thumbnailUrl
      if (coverUrl != null) {
        DeviceCoverCache.put(deviceId, coverUrl)
      }
      val elapsed = currentTimeMillis() - startTime
      RepLog.d(TAG) { "[SN=$deviceId] refreshCoverUrl: → ${if (coverUrl != null) "updated" else "no cover"} (${elapsed}ms)" }
      Result.success(coverUrl)
    } catch (e: Exception) {
      RepLog.e(TAG) { "[SN=$deviceId] refreshCoverUrl: FAILED — ${e.message}" }
      Result.failure(e)
    }
  }

  /**
   * 带超时的封面图加载
   *
   * v2.2.7 增强：传入 `parentSn` 后执行实际查询。
   * 不传 `parentSn`（默认空串）时保留旧行为（refreshCoverUrl 无效 → 超时降级到缓存）。
   *
   * @param deviceId 设备 SN
   * @param parentSn 基站 SN（传入后执行实际查询；默认空串保留旧行为）
   * @return 封面图 URL，超时或失败时返回 null
   */
  suspend fun getCoverUrlWithTimeout(deviceId: String, parentSn: String = ""): String? {
    return withTimeoutOrNull(THUMBNAIL_TIMEOUT_MS) {
      refreshCoverUrl(deviceId, parentSn).getOrNull()
    }?.also { url ->
      if (url == null) {
        RepLog.w(TAG) { "[SN=$deviceId] getCoverUrlWithTimeout: timeout → fallback to cache" }
      }
    } ?: getCoverUrl(deviceId) // 超时则返回缓存
  }

  /**
   * 批量刷新封面图
   *
   * @param deviceIds 设备 SN 列表
   */
  suspend fun refreshCoverUrlsBatch(deviceIds: List<String>) {
    val startTime = currentTimeMillis()
    val coverMap = mutableMapOf<String, String>()
    for (deviceId in deviceIds) {
      val latestEvent = getLatestEvent(deviceId).getOrNull()
      latestEvent?.thumbnailUrl?.let { url ->
        coverMap[deviceId] = url
      }
    }
    if (coverMap.isNotEmpty()) {
      DeviceCoverCache.putAll(coverMap)
    }
    val elapsed = currentTimeMillis() - startTime
    RepLog.i(TAG) { "refreshCoverUrlsBatch: ${deviceIds.size} devices → ${coverMap.size} covers (${elapsed}ms)" }
  }

  /**
   * 清除指定设备的封面图缓存
   *
   * @param deviceId 设备 SN
   */
  fun invalidateCover(deviceId: String) {
    DeviceCoverCache.invalidate(deviceId)
    RepLog.d(TAG) { "[SN=$deviceId] invalidateCover: cache cleared" }
  }

  // === 内部实现 ===

  /**
   * 通过 SDK 查询设备最新事件（纯查询，不写缓存）。
   *
   * 使用 suspendCancellableCoroutine 包装回调式 API，所有 resume 前检查 isActive 守卫。
   * 超时由 withTimeoutOrNull 控制；晚到/重复回调通过 isActive 守卫静默丢弃。
   */
  private suspend fun queryLatestEventInternal(
    deviceSn: String,
    parentSn: String
  ): Result<LatestEvent?> {
    val repo = localEventRepository
      ?: run {
        RepLog.e(TAG) { "[SN=$deviceSn] queryLatestEvent: LocalEventRepository not available" }
        return Result.failure(IllegalStateException("LocalEventRepository not available"))
      }

    val today = DateUtil.getToday()
    val params = HistoryRecordSearchParams(parentSn, today, today)
      .setDeviceSnList(listOf(deviceSn))
      .setCount(1)

    RepLog.d(TAG) { "[SN=$deviceSn] queryLatestEvent: querying SDK (parentSn=$parentSn, today=$today)" }

    return withTimeoutOrNull(THUMBNAIL_TIMEOUT_MS) {
      suspendCancellableCoroutine<Result<LatestEvent?>> { cont ->
        repo.getHistoryRecord(isRefresh = true, params = params) { historyResult ->
          // isActive 守卫：晚到/取消后/重复回调静默丢弃
          if (!cont.isActive) {
            RepLog.d(TAG) { "[SN=$deviceSn] queryLatestEvent: callback ignored (continuation inactive)" }
            return@getHistoryRecord
          }

          val records = historyResult.recordList
          if (records.isEmpty()) {
            RepLog.d(TAG) { "[SN=$deviceSn] queryLatestEvent: no events found" }
            cont.resume(Result.success(null))
            return@getHistoryRecord
          }

          val record = records.first()
          val rawPath = record.thumb_path
          if (rawPath.isEmpty()) {
            RepLog.d(TAG) { "[SN=$deviceSn] queryLatestEvent: thumb_path is empty" }
            cont.resume(Result.success(null))
            return@getHistoryRecord
          }

          val cleanPath = cleanThumbPath(rawPath, deviceSn)
          val event = LatestEvent(
            eventId = record.record_id.toString(),
            thumbnailUrl = cleanPath,
            eventType = record.trigger_type.toString(),
            timestamp = record.start_time.toLongOrNull() ?: 0L
          )
          RepLog.d(TAG) { "[SN=$deviceSn] queryLatestEvent: success, path=$cleanPath" }
          cont.resume(Result.success(event))
        }

        // 取消处理：若 getHistoryRecord 返回可取消句柄，在此取消
        // 当前未知返回类型，仅靠 isActive 守卫处理晚到回调
        cont.invokeOnCancellation {
          RepLog.d(TAG) { "[SN=$deviceSn] queryLatestEvent: cancelled" }
        }
      }
    } ?: run {
      RepLog.w(TAG) { "[SN=$deviceSn] queryLatestEvent: timeout (${THUMBNAIL_TIMEOUT_MS}ms)" }
      Result.success(null)
    }
  }

  /**
   * 清理 thumb_path 的设备 SN 前缀。
   * 参考 DeviceList.kt:218-224 的现有实现。
   */
  private fun cleanThumbPath(thumbPath: String, deviceSn: String): String {
    return when {
      thumbPath.startsWith("$deviceSn-") -> thumbPath.removePrefix("$deviceSn-")
      thumbPath.startsWith("$deviceSn~") -> thumbPath.removePrefix("$deviceSn~")
      else -> thumbPath
    }
  }
}
