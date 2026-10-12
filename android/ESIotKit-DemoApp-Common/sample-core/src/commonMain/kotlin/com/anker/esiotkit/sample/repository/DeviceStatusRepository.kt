package com.anker.esiotkit.sample.repository

import com.anker.esiotkit.sample.model.DeviceStatus
import com.anker.esiotkit.sample.support.perf.RepLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * T-REP-02: 设备状态 Repository
 *
 * 负责设备状态查询和推送订阅，支持实时状态更新。
 *
 * 主要职责：
 * - 查询单个/批量设备状态
 * - 提供设备状态变化观察流
 * - 状态变化去重（30 秒防抖）
 */
object DeviceStatusRepository {

  private const val TAG = "DeviceStatusRepository"
  private const val STATUS_DEBOUNCE_MS = 30_000L // 30 秒内同一设备不重复通知

  /**
   * 设备状态变更事件
   *
   * @param deviceSn 设备 SN
   * @param newStatus 新的设备状态
   * @param timestamp 变更时间戳（毫秒）
   */
  data class DeviceStatusChange(
    val deviceSn: String,
    val newStatus: DeviceStatus,
    val timestamp: Long
  )

  // 内部状态存储
  private val _statusFlow = MutableStateFlow<Map<String, DeviceStatus>>(emptyMap())

  // 最后通知时间记录（用于防抖）
  private val _lastNotifyTime = mutableMapOf<String, Long>()

  /**
   * 观察所有设备状态变化
   *
   * @return Flow 发出状态变更事件
   */
  fun observeDeviceStatus(): Flow<Map<String, DeviceStatus>> = _statusFlow.asStateFlow()

  /**
   * 观察单个设备状态变化
   *
   * @param deviceSn 设备 SN
   * @return Flow 发出该设备的状态
   */
  fun observeDeviceStatus(deviceSn: String): Flow<DeviceStatus> {
    return _statusFlow.asStateFlow()
      .stateIn(
        scope = kotlinx.coroutines.GlobalScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyMap()
      )
      .mapIfPresent(deviceSn)
  }

  /**
   * 获取设备状态
   *
   * @param deviceSn 设备 SN
   * @return 设备状态结果
   */
  suspend fun getDeviceStatus(deviceSn: String): Result<DeviceStatus> {
    return try {
      val status = _statusFlow.value[deviceSn] ?: DeviceStatus.UNKNOWN
      Result.success(status)
    } catch (e: Exception) {
      RepLog.e(TAG) { "[SN=$deviceSn] getDeviceStatus: FAILED — ${e.message}" }
      Result.failure(e)
    }
  }

  /**
   * 批量获取设备状态
   *
   * @param deviceSns 设备 SN 列表
   * @return 设备状态映射结果
   */
  suspend fun getDeviceStatusBatch(deviceSns: List<String>): Result<Map<String, DeviceStatus>> {
    return try {
      val currentStatus = _statusFlow.value
      val result = deviceSns.associateWith { sn ->
        currentStatus[sn] ?: DeviceStatus.UNKNOWN
      }
      Result.success(result)
    } catch (e: Exception) {
      RepLog.e(TAG) { "getDeviceStatusBatch: FAILED (${deviceSns.size} devices) — ${e.message}" }
      Result.failure(e)
    }
  }

  /**
   * 更新设备状态（供 SDK 推送或手动更新调用）
   *
   * @param deviceSn 设备 SN
   * @param status 新状态
   */
  fun updateDeviceStatus(deviceSn: String, status: DeviceStatus) {
    val now = com.anker.esiotkit.support.platform.currentTimeMillis()
    val lastTime = _lastNotifyTime[deviceSn] ?: 0L
    val oldStatus = _statusFlow.value[deviceSn]

    // 防抖：30 秒内同一设备不重复通知
    if (now - lastTime < STATUS_DEBOUNCE_MS) {
      RepLog.d(TAG) { "[SN=$deviceSn] updateDeviceStatus: ${oldStatus}→$status (debounced)" }
      // 静默更新内部状态，不触发通知
      val currentMap = _statusFlow.value.toMutableMap()
      currentMap[deviceSn] = status
      _statusFlow.value = currentMap
      return
    }

    _lastNotifyTime[deviceSn] = now
    RepLog.i(TAG) { "[SN=$deviceSn] updateDeviceStatus: ${oldStatus}→$status" }

    val currentMap = _statusFlow.value.toMutableMap()
    currentMap[deviceSn] = status
    _statusFlow.value = currentMap
  }

  /**
   * 批量更新设备状态
   *
   * @param statusMap 设备 SN -> 状态 映射
   */
  fun updateDeviceStatusBatch(statusMap: Map<String, DeviceStatus>) {
    val currentMap = _statusFlow.value.toMutableMap()
    currentMap.putAll(statusMap)
    _statusFlow.value = currentMap
    RepLog.d(TAG) { "updateDeviceStatusBatch: ${statusMap.size} devices" }
  }

  /**
   * 清除防抖记录（可用于手动刷新场景）
   */
  fun clearDebounce() {
    _lastNotifyTime.clear()
    RepLog.d(TAG) { "clearDebounce: all records cleared" }
  }

  // === Private methods ===

  private fun Flow<Map<String, DeviceStatus>>.mapIfPresent(deviceSn: String): Flow<DeviceStatus> {
    val self = this
    return kotlinx.coroutines.flow.channelFlow {
      self.collect { statusMap ->
        statusMap[deviceSn]?.let { send(it) }
      }
    }
  }
}
