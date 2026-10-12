package com.anker.esiotkit.sample.viewmodel

import com.anker.esiotkit.core.config.IotDebugConfig
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.model.DeviceListState
import com.anker.esiotkit.sample.model.isStation
import com.anker.esiotkit.sample.repository.DeviceListRepository
import com.anker.esiotkit.sample.repository.DeviceStatusRepository
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * T-VM-01: 设备列表 ViewModel 状态管理
 *
 * 负责设备列表的状态管理和业务逻辑。
 *
 * 主要职责：
 * - 管理设备列表加载和刷新
 * - 处理设备状态推送更新
 * - 暴露 UI 状态流给界面层
 * - 提供缓存和错误恢复机制
 */

/**
 * T-VM-03: 导航目标
 *
 * 表示 ViewModel 触发的页面导航行为。
 */
sealed class NavigationTarget {
  object None : NavigationTarget()
  object AddDevice : NavigationTarget()
  data class EventList(val deviceId: String) : NavigationTarget()
  data class Settings(val deviceId: String) : NavigationTarget()
  data class LiveVideo(val deviceId: String) : NavigationTarget()
  data class RetryLoadChild(val stationId: String) : NavigationTarget()
}

class DevicesViewModel : BaseScreenModel() {

  private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
    _uiState.value = _uiState.value.copy(
      isError = true,
      errorMessage = throwable.message ?: "Unknown error"
    )
  }

  private val viewModelScopeWithSupervisor = viewModelScope + exceptionHandler

  companion object {
    private const val CACHE_VALID_MS = 5 * 60 * 1000L // 5 分钟缓存有效
    private const val STATUS_CHANGE_DEBOUNCE_MS = 5_000L // 状态变化 5 秒防抖
  }

  // UI 状态
  private val _uiState = MutableStateFlow(DeviceListState())
  val uiState: StateFlow<DeviceListState> = _uiState.asStateFlow()

  // Toast 消息（独立状态流）
  private val _toastMessage = MutableStateFlow<String?>(null)
  val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

  // 导航目标（T-VM-03）
  private val _navigationTarget = MutableStateFlow<NavigationTarget>(NavigationTarget.None)
  val navigationTarget: StateFlow<NavigationTarget> = _navigationTarget.asStateFlow()

  // 原始设备列表（未层级化）
  private val rawDevices = mutableListOf<MegaDevice>()

  // 上次加载时间
  private var lastLoadTime = 0L

  // 状态变化防抖记录
  private var lastStatusChangeTime = mutableMapOf<String, Long>()

  // 后台任务
  private var statusCollectJob: Job? = null

  init {
    subscribeToStatusChanges()
    // 超时保护：5 秒后如果仍无数据，退出 loading 避免无限转圈
    viewModelScopeWithSupervisor.launch {
      delay(5000)
      if (_uiState.value.isLoading && lastLoadTime == 0L) {
        _uiState.value = _uiState.value.copy(isLoading = false)
      }
    }
  }

  /**
   * 从外部直接推送设备列表（由 MegaMainScreen 驱动）
   *
   * 若首次推送为空列表，保持 isLoading=true 等待真实数据。
   */
  fun updateDevices(devices: List<MegaDevice>) {
    if (devices.isEmpty() && lastLoadTime == 0L) return // 跳过初始空推送
    rawDevices.clear()
    rawDevices.addAll(devices)
    lastLoadTime = currentTimeMillis()
    updateDisplayList()
  }

  /**
   * 加载设备列表（保留兼容性）
   */
  fun loadDeviceList() {
    updateDisplayList()
  }

  /**
   * 刷新设备列表
   *
   * - 下拉刷新时保留旧数据
   * - 刷新结果经由 DeviceListRepository 的 Flow 回传，驱动 UI 更新
   */
  fun refreshDeviceList() {
    viewModelScopeWithSupervisor.launch {
      _uiState.value = _uiState.value.copy(isRefreshing = true)
      try {
        MegaDeviceManager.refreshDeviceList().await()
        // 刷新成功后由 subscribeToDeviceList 自动更新 UI，这里只需清除刷新状态
        _uiState.value = _uiState.value.copy(isRefreshing = false)
      } catch (e: Exception) {
        val errorJson = e.message ?: "{}"
        _uiState.value = _uiState.value.copy(isRefreshing = false)
        if (IotDebugConfig.enableDetailDebugLog) {
          appLogger.e("DevicesViewModel refreshDeviceList: FAILED — $errorJson")
        }
        _toastMessage.value = "刷新失败，请稍后重试"
        clearToastAfterDelay()
      }
    }
  }

  /**
   * 重试加载
   */
  fun retryLoad() {
    _uiState.value = _uiState.value.copy(isError = false, errorMessage = "")
    loadDeviceList()
  }

  /**
   * T-VM-03: 点击设备卡片
   *
   * 基站跳设置页，摄像头/独立设备跳直播流。
   */
  fun onDeviceClick(device: MegaDevice) {
    if (device.isStation()) {
      _navigationTarget.value = NavigationTarget.Settings(device.deviceSn)
    } else {
      _navigationTarget.value = NavigationTarget.LiveVideo(device.deviceSn)
    }
  }

  /**
   * T-VM-03: 点击添加设备
   *
   * 触发导航到设备添加页面。
   */
  fun onAddDeviceClick() {
    _navigationTarget.value = NavigationTarget.AddDevice
  }

  /**
   * T-VM-03: 设备添加完成回调
   *
   * 添加成功后刷新设备列表并显示 Toast。
   *
   * @param success 是否添加成功
   */
  fun onAddDeviceResult(success: Boolean) {
    if (success) {
      _toastMessage.value = "设备添加成功"
      clearToastAfterDelay()
      loadDeviceList()
    }
    _navigationTarget.value = NavigationTarget.None
  }

  /**
   * T-VM-03: 点击事件查看
   *
   * 导航到设备事件列表页。
   *
   * @param deviceId 设备 SN
   */
  fun onEventClick(deviceId: String) {
    _navigationTarget.value = NavigationTarget.EventList(deviceId)
  }

  /**
   * T-VM-03: 点击设备设置
   *
   * 导航到设备设置页。
   *
   * @param deviceId 设备 SN
   */
  fun onSettingsClick(deviceId: String) {
    _navigationTarget.value = NavigationTarget.Settings(deviceId)
  }

  /**
   * T-VM-03: 重试加载子设备
   *
   * 当子设备加载失败时，触发重试。
   *
   * @param stationId 基站 SN
   */
  fun retryLoadChildDevices(stationId: String) {
    _navigationTarget.value = NavigationTarget.RetryLoadChild(stationId)
  }

  /**
   * T-VM-03: 清除导航目标
   *
   * 页面导航完成后调用，重置导航状态。
   */
  fun consumeNavigation() {
    _navigationTarget.value = NavigationTarget.None
  }

  /**
   * 搜索词变化
   */
  fun onSearchQueryChange(query: String) {
    _uiState.value = _uiState.value.copy(searchQuery = query)
  }

  /**
   * 清空搜索
   */
  fun onClearSearch() {
    _uiState.value = _uiState.value.copy(searchQuery = "")
  }

  /**
   * 订阅设备状态推送
   */
  private fun subscribeToStatusChanges() {
    statusCollectJob = DeviceStatusRepository.observeDeviceStatus()
      .onEach { statusMap ->
        var changed = false
        for ((deviceSn, newStatus) in statusMap) {
          val device = rawDevices.find { it.deviceSn == deviceSn }
          if (device != null) {
            changed = true
          }

          // 防抖：5 秒内同一设备不重复通知
          val now = currentTimeMillis()
          val lastTime = lastStatusChangeTime[deviceSn] ?: 0L
          if (now - lastTime >= STATUS_CHANGE_DEBOUNCE_MS) {
            lastStatusChangeTime[deviceSn] = now
          }
        }

        if (changed) {
          updateDisplayList()
        }
      }
      .catch {
        // 状态推送失败，不影响主流程
      }
      .launchIn(viewModelScopeWithSupervisor)
  }

  /**
   * 更新显示列表（层级化 + 过滤 + 排序）
   */
  private fun updateDisplayList() {
    val hierarchicalList = DeviceListRepository.buildHierarchicalList(rawDevices)

    _uiState.value = _uiState.value.copy(
      isLoading = false,
      isRefreshing = false,
      isError = false,
      errorMessage = "",
      rawDevices = rawDevices.toList(),
      displayList = hierarchicalList
    )
  }

  /**
   * 清除 Toast 消息（延迟后自动清除）
   */
  private fun clearToastAfterDelay() {
    viewModelScopeWithSupervisor.launch {
      delay(3000)
      _toastMessage.value = null
    }
  }

  override fun onDispose() {
    statusCollectJob?.cancel()
  }
}
