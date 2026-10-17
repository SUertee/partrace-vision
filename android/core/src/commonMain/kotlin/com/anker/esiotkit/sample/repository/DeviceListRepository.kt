package com.anker.esiotkit.sample.repository

import com.anker.esiotkit.sample.model.DisplayDeviceItem
import com.anker.esiotkit.sample.model.isStation
import com.anker.esiotkit.sample.support.perf.RepLog
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.support.platform.currentTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.Result
import kotlin.concurrent.Volatile

/**
 * T-REP-01: 设备列表 Repository
 *
 * 负责设备列表查询、层级构建和状态观察。
 *
 * 主要职责：
 * - 从 ESIotKit SDK 获取设备列表
 * - 构建两级层级结构（基站 → 子设备）
 * - 提供设备列表观察流
 */
object DeviceListRepository {

  private const val TAG = "DeviceListRepository"
  private const val CACHE_FRESH_MS = 5 * 60 * 1000L
  private val _deviceListFlow = MutableStateFlow<List<MegaDevice>>(emptyList())
  @Volatile
  private var lastRefreshTime = 0L

  /**
   *
   * @return Flow 发出最新的设备列表
   */
  fun observeDeviceList(): Flow<List<MegaDevice>> = _deviceListFlow

  /**
   * 上次从服务器成功刷新的时间戳，0 表示从未刷新
   */
  fun getLastRefreshTime(): Long = lastRefreshTime

  /**
   * 当前缓存数据的年龄（毫秒），从未刷新返回 -1
   */
  fun getCacheAge(): Long {
    return if (lastRefreshTime == 0L) -1L
    else currentTimeMillis() - lastRefreshTime
  }

  /**
   * 缓存是否已过期
   */
  fun isCacheStale(): Boolean {
    return lastRefreshTime == 0L || (currentTimeMillis() - lastRefreshTime) > CACHE_FRESH_MS
  }

  /**
   * 获取设备列表（带缓存）
   *
   * 优先返回缓存，缓存为空时自动刷新。
   * 可通过日志判断数据来源：cache HIT = 缓存，cache MISS = 服务器。
   *
   * @return 设备列表结果
   */
  suspend fun getDeviceList(): Result<List<MegaDevice>> {
    return try {
      val currentList = _deviceListFlow.value
      val cacheAge = getCacheAge()
      if (currentList.isNotEmpty()) {
        RepLog.d(TAG) { "getDeviceList: cache HIT (${currentList.size} devices, age=${cacheAge}ms${if (isCacheStale()) ", STALE" else ""})" }
        Result.success(currentList)
      } else {
        RepLog.d(TAG) { "getDeviceList: cache MISS → refreshing" }
        refreshDeviceList()
      }
    } catch (e: Exception) {
      RepLog.e(TAG) { "getDeviceList: FAILED — ${e.message}" }
      Result.failure(e)
    }
  }

  /**
   * 强制刷新设备列表（从服务器获取，跳过缓存）
   *
   * 保留旧数据直到新数据成功返回，刷新失败不丢弃旧数据。
   *
   * @return 刷新后的设备列表结果
   */
  suspend fun refreshDeviceList(): Result<List<MegaDevice>> {
    val startTime = currentTimeMillis()
    RepLog.i(TAG) { "refreshDeviceList: STARTED (force server fetch)" }
    return try {
      val oldList = _deviceListFlow.value

      // TODO: 调用 ESIotKit SDK 获取最新设备列表
      // 当前使用 SDK 已有的设备列表
      val newList = fetchDeviceListFromSdk()

      _deviceListFlow.value = newList
      lastRefreshTime = currentTimeMillis()
      val elapsed = currentTimeMillis() - startTime
      RepLog.i(TAG) { "refreshDeviceList: DONE — ${newList.size} devices, was ${oldList.size} (${elapsed}ms)" }
      Result.success(newList)
    } catch (e: Exception) {
      val elapsed = currentTimeMillis() - startTime
      RepLog.e(TAG) { "refreshDeviceList: FAILED (${elapsed}ms) — ${e.message}, keeping old data (${_deviceListFlow.value.size} devices)" }
      // 刷新失败，保留旧数据，不更新 lastRefreshTime
      Result.failure(e)
    }
  }

  /**
   * 更新设备列表（内部方法，供外部调用方推送新数据）
   *
   * @param devices 新的设备列表
   */
  fun updateDeviceList(devices: List<MegaDevice>) {
    val was = _deviceListFlow.value.size
    _deviceListFlow.value = devices
    RepLog.d(TAG) { "updateDeviceList: $was → ${devices.size} devices" }
  }

  /**
   * 构建两级层级结构
   *
   * 将扁平设备列表转换为层级结构：
   * - 基站设备聚合其子设备，返回 StationItem
   * - 独立设备返回 StandaloneItem
   *
   * 算法：单次遍历 + Map 构建，O(n) 复杂度
   *
   * @param devices 扁平设备列表
   * @return 层级化的设备项列表
   */
  fun buildHierarchicalList(devices: List<MegaDevice>): List<DisplayDeviceItem> {
    if (devices.isEmpty()) {
      RepLog.d(TAG) { "buildHierarchicalList: 0 devices → empty" }
      return emptyList()
    }

    // 1. 按 parentSn 分组，收集所有子设备（deviceSn != parentSn）
    val childrenByParent = devices
      .filter { it.deviceSn != it.parentSn }
      .groupBy { it.parentSn }

    // 2. 遍历所有顶层设备（deviceSn == parentSn），按是否有子设备区分
    //    有子设备 → StationItem（含 NVR / 基站等所有父类型）
    //    无子设备 → StandaloneItem
    //    保留原始顺序
    val result = mutableListOf<DisplayDeviceItem>()
    val parentSns = mutableSetOf<String>()

    devices.forEach { device ->
      if (device.deviceSn == device.parentSn) {
        val children = childrenByParent[device.deviceSn] ?: emptyList()
        if (children.isNotEmpty() || device.isStation()) {
          result.add(
            DisplayDeviceItem.StationItem(station = device, children = children)
          )
          parentSns.add(device.deviceSn)
        } else {
          result.add(DisplayDeviceItem.StandaloneItem(device))
        }
      }
    }

    // 3. 处理孤儿子设备（parent 不在列表中）
    val orphanChildren = devices.filter {
      it.deviceSn != it.parentSn && it.parentSn !in parentSns
    }
    orphanChildren.forEach { child ->
      result.add(DisplayDeviceItem.StandaloneItem(child))
    }

    val stations = result.count { it is DisplayDeviceItem.StationItem }
    val standalones = result.count { it is DisplayDeviceItem.StandaloneItem }
    RepLog.d(TAG) { "buildHierarchicalList: ${devices.size} devices → $stations stations + $standalones standalones (+${orphanChildren.size} orphans)" }
    return result
  }

  // === Private methods ===

  /**
   * 从 SDK 获取设备列表
   *
   * TODO: 实际对接 ESIotKit SDK
   */
  private suspend fun fetchDeviceListFromSdk(): List<MegaDevice> {
    // 占位实现，待对接 SDK
    val result = _deviceListFlow.value
    RepLog.d(TAG) { "fetchDeviceListFromSdk: returned ${result.size} devices (placeholder)" }
    return result
  }
}
