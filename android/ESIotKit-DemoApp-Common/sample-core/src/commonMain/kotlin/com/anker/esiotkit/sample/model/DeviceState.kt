package com.anker.esiotkit.sample.model

import androidx.compose.runtime.Stable
import com.anker.esiotkit.domain_thing.model.device.MegaDevice

/**
 * T-DM-03: 显示设备项密封类
 *
 * 用于两级层级结构的设备列表展示，
 * 区分基站（含子设备）和独立设备。
 */
sealed class DisplayDeviceItem {
  abstract val id: String

  /**
   * 基站设备项，包含子设备列表
   *
   * @param station 基站设备
   * @param children 子设备列表
   * @param childrenLoadFailed 子设备加载是否失败
   * @param childrenErrorMessage 子设备加载失败时的错误信息
   */
  data class StationItem(
    val station: MegaDevice,
    val children: List<MegaDevice> = emptyList(),
    val childrenLoadFailed: Boolean = false,
    val childrenErrorMessage: String = ""
  ) : DisplayDeviceItem() {
    override val id: String = station.deviceSn
  }

  /**
   * 独立设备项
   *
   * @param device 设备实例
   */
  data class StandaloneItem(
    val device: MegaDevice
  ) : DisplayDeviceItem() {
    override val id: String = device.deviceSn
  }
}

/**
 * T-DM-03: 排序类型枚举
 */
enum class SortType {
  NAME,
  DATE,
  STATUS;

  val label: String
    get() = when (this) {
      NAME -> "名称"
      DATE -> "日期"
      STATUS -> "状态"
    }
}

/**
 * T-DM-03: 排序方向枚举
 */
enum class SortDirection {
  ASCENDING,
  DESCENDING;

  fun toggle(): SortDirection = when (this) {
    ASCENDING -> DESCENDING
    DESCENDING -> ASCENDING
  }
}

/**
 * T-DM-03: 排序选项
 *
 * @param type 排序类型
 * @param direction 排序方向
 */
data class SortOption(
  val type: SortType = SortType.NAME,
  val direction: SortDirection = SortDirection.ASCENDING
) {
  companion object {
    val DEFAULT = SortOption(type = SortType.NAME, direction = SortDirection.ASCENDING)
  }
}

/**
 * T-DM-03: 状态过滤值枚举
 */
enum class StatusFilterValue {
  ALL,
  ONLINE,
  OFFLINE;

  val label: String
    get() = when (this) {
      ALL -> "全部"
      ONLINE -> "在线"
      OFFLINE -> "离线"
    }
}

/**
 * T-DM-03: 搜索过滤状态
 *
 * @param query 搜索关键词
 * @param isSearching 是否正在搜索（query 非空）
 * @param matchedDeviceIds 匹配的设备 ID 集合
 */
data class SearchFilterState(
  val query: String = "",
  val isSearching: Boolean = false,
  val matchedDeviceIds: Set<String> = emptySet()
) {
  companion object {
    /**
     * 判断指定设备是否匹配当前搜索条件
     *
     * @param state 搜索状态
     * @param deviceSn 设备 SN
     * @param deviceName 设备名称
     * @return 是否匹配
     */
    fun matches(state: SearchFilterState, deviceSn: String, deviceName: String): Boolean {
      if (!state.isSearching) return true
      val q = state.query.lowercase()
      return deviceSn.lowercase().contains(q) || deviceName.lowercase().contains(q)
    }
  }
}

/**
 * T-DM-03: 设备列表状态
 *
 * @Stable 标注以优化 Compose 重组性能
 *
 * @param rawDevices 原始设备列表（未过滤排序）
 * @param displayList 展示用的设备项列表（已过滤排序，两级结构）
 * @param searchQuery 当前搜索关键词
 * @param sortOption 当前排序选项
 * @param statusFilter 当前状态过滤选项
 * @param isLoading 是否正在加载
 * @param isRefreshing 是否正在刷新
 * @param isError 是否有错误
 * @param errorMessage 错误信息
 */
@Stable
data class DeviceListState(
  val rawDevices: List<MegaDevice> = emptyList(),
  val displayList: List<DisplayDeviceItem> = emptyList(),
  val searchQuery: String = "",
  val sortOption: SortOption = SortOption.DEFAULT,
  val statusFilter: StatusFilterValue = StatusFilterValue.ALL,
  val isLoading: Boolean = true,
  val isRefreshing: Boolean = false,
  val isError: Boolean = false,
  val errorMessage: String = ""
) {
  /**
   * 设备列表是否为空（无设备且非加载中非错误状态）
   */
  val isEmpty: Boolean
    get() = rawDevices.isEmpty() && !isLoading && !isError

  /**
   * 搜索是否无结果
   */
  val isSearchNoResults: Boolean
    get() = searchQuery.isNotBlank() && displayList.isEmpty() && !isLoading

  /**
   * 设备总数
   */
  val totalDeviceCount: Int
    get() = rawDevices.size

  /**
   * 在线设备数
   */
  val onlineDeviceCount: Int
    get() = rawDevices.count { it.isOnline() }
}
