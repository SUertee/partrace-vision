package com.anker.esiotkit.sample.filter

import com.anker.esiotkit.sample.model.SearchFilterState
import com.anker.esiotkit.sample.model.StatusFilterValue
import com.anker.esiotkit.sample.model.isOnline
import com.anker.esiotkit.sample.model.isStation
import com.anker.esiotkit.sample.model.displayName
import com.anker.esiotkit.domain_thing.model.device.MegaDevice

/**
 * T-VM-02: 设备过滤引擎
 *
 * 负责设备列表的搜索过滤和状态过滤。
 *
 * 主要功能：
 * - 模糊搜索（名称/SN/PN，不区分大小写）
 * - 状态过滤（全部/在线/离线）
 * - 子设备匹配时展开基站
 */
object DeviceFilterEngine {

  /**
   * 应用搜索过滤
   *
   * 对设备列表进行模糊搜索，匹配名称、SN、PN。
   * 搜索为空时返回完整列表。
   * 当子设备匹配时，同时保留其所属基站。
   *
   * @param devices 设备列表
   * @param query 搜索关键词
   * @return 匹配的设备列表
   */
  fun applySearchFilter(devices: List<MegaDevice>, query: String): List<MegaDevice> {
    if (query.isBlank()) return devices

    val q = query.trim().lowercase()

    // 找出匹配的设备
    val matchedDevices = devices.filter { device ->
      matchesQuery(device, q)
    }.toSet()

    // 当子设备匹配时，确保其所属基站也在结果中
    val stationSns = devices
      .filter { it.deviceSn == it.parentSn && it.isStation() }
      .map { it.deviceSn }
      .toSet()

    val result = matchedDevices.toMutableSet()

    for (device in matchedDevices) {
      // 如果匹配的是子设备，添加其所属基站
      if (device.deviceSn != device.parentSn && device.parentSn in stationSns) {
        val station = devices.find { it.deviceSn == device.parentSn }
        station?.let { result.add(it) }
      }
    }

    return result.toList()
  }

  /**
   * 应用状态过滤
   *
   * @param devices 设备列表
   * @param filter 状态过滤值
   * @return 过滤后的设备列表
   */
  fun applyStatusFilter(devices: List<MegaDevice>, filter: StatusFilterValue): List<MegaDevice> {
    return when (filter) {
      StatusFilterValue.ALL -> devices
      StatusFilterValue.ONLINE -> devices.filter { it.isOnline() }
      StatusFilterValue.OFFLINE -> devices.filter { !it.isOnline() }
    }
  }

  /**
   * 构建搜索过滤状态
   *
   * @param query 搜索关键词
   * @return 搜索过滤状态
   */
  fun buildSearchState(query: String): SearchFilterState {
    return SearchFilterState(
      query = query,
      isSearching = query.isNotBlank()
    )
  }

  // === Private methods ===

  /**
   * 判断设备是否匹配搜索关键词
   */
  private fun matchesQuery(device: MegaDevice, query: String): Boolean {
    return try {
      val name = device.displayName().lowercase()
      val sn = device.deviceSn.lowercase()
      val pn = (device.getProperty("device_pn")?.get()?.asString() ?: "").lowercase()

      name.contains(query) || sn.contains(query) || pn.contains(query)
    } catch (e: Exception) {
      false
    }
  }
}
