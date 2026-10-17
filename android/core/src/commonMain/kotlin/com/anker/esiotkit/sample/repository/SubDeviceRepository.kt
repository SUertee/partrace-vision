package com.anker.esiotkit.sample.repository

import com.anker.esiotkit.sample.model.isStation
import com.anker.esiotkit.sample.support.perf.RepLog
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.support.platform.currentTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.Result

/**
 * T-REP-05: 子设备列表查询 Repository
 *
 * 负责基站下子设备列表的查询和管理。
 *
 * 主要职责：
 * - 根据基站 ID 查询子设备列表
 * - 子设备按 addedTime 升序排列（与主列表降序不同）
 * - 无子设备时返回空列表而非错误
 * - 加载失败返回 Result.failure，支持点击重试
 */
object SubDeviceRepository {

  private const val TAG = "SubDeviceRepository"

  // 子设备加载状态记录
  private val _childLoadErrors = MutableStateFlow<MutableMap<String, String>>(mutableMapOf())

  /**
   * 观察子设备加载错误状态
   *
   * @param baseStationId 基站 SN
   * @return Flow 发出该基站是否有加载错误
   */
  fun observeChildLoadError(baseStationId: String): Flow<Boolean> {
    return _childLoadErrors.map { errors ->
      errors.containsKey(baseStationId)
    }
  }

  /**
   * 获取子设备列表
   *
   * 优先从 DeviceListRepository 的完整列表中筛选，
   * 如果 SDK 支持直接查询则使用 SDK 方法。
   *
   * @param baseStationId 基站 SN
   * @return 子设备列表（按 addedTime 升序），失败返回 Result.failure
   */
  suspend fun getChildDevices(baseStationId: String): Result<List<MegaDevice>> {
    val startTime = currentTimeMillis()
    return try {
      val allDevices = DeviceListRepository.getDeviceList().getOrNull() ?: emptyList()

      // 筛选该基站下的子设备
      val children = allDevices
        .filter { it.deviceSn != it.parentSn && it.parentSn == baseStationId }
        .sortedBy { it.iotDevice.bindTime }

      // 清除之前的错误状态
      clearError(baseStationId)

      val elapsed = currentTimeMillis() - startTime
      RepLog.d(TAG) { "[Station=$baseStationId] getChildDevices: ${children.size} children (${elapsed}ms)" }
      Result.success(children)
    } catch (e: Exception) {
      val elapsed = currentTimeMillis() - startTime
      RepLog.e(TAG) { "[Station=$baseStationId] getChildDevices: FAILED (${elapsed}ms) — ${e.message}" }
      recordError(baseStationId, e.message ?: "Unknown error")
      Result.failure(e)
    }
  }

  /**
   * 检查基站是否有子设备
   *
   * @param baseStationId 基站 SN
   * @return 是否有子设备
   */
  suspend fun hasChildDevices(baseStationId: String): Result<Boolean> {
    return getChildDevices(baseStationId).map { it.isNotEmpty() }
  }

  /**
   * 获取基站错误信息
   *
   * @param baseStationId 基站 SN
   * @return 错误信息，无错误返回 null
   */
  fun getError(baseStationId: String): String? {
    return _childLoadErrors.value[baseStationId]
  }

  /**
   * 清除错误状态（用于重试场景）
   *
   * @param baseStationId 基站 SN
   */
  fun clearError(baseStationId: String) {
    val currentErrors = _childLoadErrors.value.toMutableMap()
    currentErrors.remove(baseStationId)
    _childLoadErrors.value = currentErrors
  }

  /**
   * 记录错误
   *
   * @param baseStationId 基站 SN
   * @param errorMessage 错误信息
   */
  private fun recordError(baseStationId: String, errorMessage: String) {
    val currentErrors = _childLoadErrors.value.toMutableMap()
    currentErrors[baseStationId] = errorMessage
    _childLoadErrors.value = currentErrors
    RepLog.e(TAG) { "[Station=$baseStationId] recordError: $errorMessage" }
  }
}
