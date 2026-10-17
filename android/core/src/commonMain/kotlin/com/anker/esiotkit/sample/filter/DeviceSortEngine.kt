package com.anker.esiotkit.sample.filter

import com.anker.esiotkit.sample.model.SortOption
import com.anker.esiotkit.sample.model.SortType
import com.anker.esiotkit.sample.model.isOnline
import com.anker.esiotkit.sample.model.displayName
import com.anker.esiotkit.domain_thing.model.device.MegaDevice

/**
 * T-VM-02: 设备排序引擎
 *
 * 负责设备列表的排序逻辑。
 *
 * 主要功能：
 * - 按名称/日期/状态排序
 * - 支持升序和降序
 */
object DeviceSortEngine {

  /**
   * 应用排序
   *
   * @param devices 设备列表
   * @param option 排序选项
   * @return 排序后的设备列表
   */
  fun applySort(devices: List<MegaDevice>, option: SortOption): List<MegaDevice> {
    return when (option.type) {
      SortType.NAME -> sortByName(devices, option.direction)
      SortType.DATE -> sortByDate(devices, option.direction)
      SortType.STATUS -> sortByStatus(devices, option.direction)
    }
  }

  // === Private methods ===

  private fun sortByName(devices: List<MegaDevice>, direction: com.anker.esiotkit.sample.model.SortDirection): List<MegaDevice> {
    return when (direction) {
      com.anker.esiotkit.sample.model.SortDirection.ASCENDING ->
        devices.sortedBy { it.displayName().lowercase() }
      com.anker.esiotkit.sample.model.SortDirection.DESCENDING ->
        devices.sortedByDescending { it.displayName().lowercase() }
    }
  }

  private fun sortByDate(devices: List<MegaDevice>, direction: com.anker.esiotkit.sample.model.SortDirection): List<MegaDevice> {
    return when (direction) {
      com.anker.esiotkit.sample.model.SortDirection.ASCENDING ->
        devices.sortedBy { getBindTime(it) }
      com.anker.esiotkit.sample.model.SortDirection.DESCENDING ->
        devices.sortedByDescending { getBindTime(it) }
    }
  }

  private fun sortByStatus(devices: List<MegaDevice>, direction: com.anker.esiotkit.sample.model.SortDirection): List<MegaDevice> {
    return when (direction) {
      com.anker.esiotkit.sample.model.SortDirection.ASCENDING ->
        devices.sortedWith(onlineFirstComparator())
      com.anker.esiotkit.sample.model.SortDirection.DESCENDING ->
        devices.sortedWith(offlineFirstComparator())
    }
  }

  private fun onlineFirstComparator(): Comparator<MegaDevice> {
    return compareBy<MegaDevice> { device ->
      if (device.isOnline()) 0 else 1
    }
  }

  private fun offlineFirstComparator(): Comparator<MegaDevice> {
    return compareBy<MegaDevice> { device ->
      if (device.isOnline()) 1 else 0
    }
  }

  private fun getBindTime(device: MegaDevice): Int {
    return try {
      device.iotDevice.bindTime as? Int ?: 0
    } catch (e: Exception) {
      0
    }
  }

  // Extension function already imported from MegaDeviceExtensions.kt
}
