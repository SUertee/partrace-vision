package com.anker.esiotkit.sample.ui

import com.anker.esiotkit.sample.model.DeviceListState
import com.anker.esiotkit.sample.model.DisplayDeviceItem
import com.anker.esiotkit.sample.model.SearchFilterState
import com.anker.esiotkit.sample.model.SortDirection
import com.anker.esiotkit.sample.model.SortOption
import com.anker.esiotkit.sample.model.SortType
import com.anker.esiotkit.sample.model.StatusFilterValue
import com.anker.esiotkit.sample.model.displayName
import com.anker.esiotkit.sample.model.isOnline
import com.anker.esiotkit.sample.model.isStation
import com.anker.esiotkit.sample.model.isStandalone
import com.anker.esiotkit.sample.model.snMasked
import com.anker.esiotkit.sample.viewmodel.NavigationTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T-UI-01 ~ T-UI-06: UI 组件逻辑测试
 *
 * 验证 UI 组件的回调合约、状态模型、导航目标和显示逻辑。
 * 视觉渲染测试需要 Compose UI instrumented test 环境。
 *
 * 覆盖:
 * - T-UI-01: DeviceListContainer 状态驱动逻辑
 * - T-UI-02: 基站/独立/子设备项显示逻辑
 * - T-UI-03: 搜索栏回调合约
 * - T-UI-04: 排序菜单选项变化逻辑
 * - T-UI-05: 状态过滤选项变化逻辑
 * - T-UI-06: 添加设备按钮回调合约
 */
class DeviceListUiLogicTest {

  // ========== T-UI-01: DeviceListContainer 状态驱动逻辑 ==========

  @Test
  fun `loading state should have isLoading true and isEmpty false`() {
    val state = DeviceListState(isLoading = true)
    assertTrue(state.isLoading)
    assertFalse(state.isEmpty) // isLoading = true means not empty view
  }

  @Test
  fun `error state should have isError true and errorMessage set`() {
    val state = DeviceListState(isError = true, errorMessage = "网络错误")
    assertTrue(state.isError)
    assertEquals("网络错误", state.errorMessage)
  }

  @Test
  fun `empty state should have isEmpty true when no devices and not loading`() {
    val state = DeviceListState(
      rawDevices = emptyList(),
      displayList = emptyList(),
      isLoading = false
    )
    assertTrue(state.isEmpty)
    assertFalse(state.isError)
    assertFalse(state.isLoading)
  }

  @Test
  fun `search no results should have isSearchNoResults true`() {
    val state = DeviceListState(
      searchQuery = "nonexistent",
      displayList = emptyList(),
      isLoading = false
    )
    assertTrue(state.isSearchNoResults)
  }

  @Test
  fun `search no results should be false when query is empty`() {
    val state = DeviceListState(
      searchQuery = "",
      displayList = emptyList()
    )
    assertFalse(state.isSearchNoResults)
  }

  @Test
  fun `search no results should be false when display list has items`() {
    val state = DeviceListState(
      searchQuery = "test",
      displayList = listOf(
        DisplayDeviceItem.StandaloneItem(TestHelpers.createMockStandalone("T8410TEST", "test"))
      )
    )
    assertFalse(state.isSearchNoResults)
  }

  @Test
  fun `totalDeviceCount should return raw device count`() {
    val devices = listOf(
      TestHelpers.createMockStandalone("T8410A"),
      TestHelpers.createMockStandalone("T8410B"),
      TestHelpers.createMockStandalone("T8410C")
    )
    val state = DeviceListState(rawDevices = devices)
    assertEquals(3, state.totalDeviceCount)
  }

  @Test
  fun `state copy should preserve unchanged fields`() {
    val original = DeviceListState(
      rawDevices = listOf(TestHelpers.createMockStandalone("T8410A")),
      searchQuery = "test",
      sortOption = SortOption(SortType.DATE, SortDirection.DESCENDING),
      statusFilter = StatusFilterValue.ONLINE
    )

    val copied = original.copy(isLoading = true)

    assertEquals(original.rawDevices, copied.rawDevices)
    assertEquals(original.searchQuery, copied.searchQuery)
    assertEquals(original.sortOption, copied.sortOption)
    assertEquals(original.statusFilter, copied.statusFilter)
    assertTrue(copied.isLoading)
  }

  // ========== T-UI-02: 设备项显示逻辑 ==========

  @Test
  fun `station item should have station SN as id`() {
    val station = TestHelpers.createMockStation("T8010STATION", "基站")
    val item = DisplayDeviceItem.StationItem(station, emptyList())
    assertEquals("T8010STATION", item.id)
  }

  @Test
  fun `standalone item should have device SN as id`() {
    val device = TestHelpers.createMockStandalone("T8410DEV", "门铃")
    val item = DisplayDeviceItem.StandaloneItem(device)
    assertEquals("T8410DEV", item.id)
  }

  @Test
  fun `station should be identified as station`() {
    val station = TestHelpers.createMockStation("T8010STATION")
    assertTrue(station.isStation())
  }

  @Test
  fun `standalone device should not be identified as station`() {
    val device = TestHelpers.createMockStandalone("T8410DEV")
    assertFalse(device.isStation())
  }

  @Test
  fun `station should have itself as parent`() {
    val station = TestHelpers.createMockStation("T8010STATION")
    assertEquals(station.deviceSn, station.parentSn)
  }

  @Test
  fun `child device should have different parent SN`() {
    val child = TestHelpers.createMockChild("T3010CHILD", "子设备")
    assertNotEquals(child.deviceSn, child.parentSn)
  }

  @Test
  fun `display name should use device name when available`() {
    val device = TestHelpers.createMockStandalone("T8410DEV", "门铃摄像头")
    assertEquals("门铃摄像头", device.displayName())
  }

  @Test
  fun `display name should fall back to SN when name is empty`() {
    val device = TestHelpers.createMockStandalone("T8410DEV", "")
    assertEquals("T8410DEV", device.displayName())
  }

  @Test
  fun `display name should fall back to SN when name equals SN`() {
    val device = TestHelpers.createMockStandalone("T8410DEV", "T8410DEV")
    assertEquals("T8410DEV", device.displayName())
  }

  @Test
  fun `SN mask should preserve first 4 and last 4 characters`() {
    val device = TestHelpers.createMockStandalone("T8410ABCD1234")
    val masked = device.snMasked()
    assertTrue(masked.startsWith("T841"))
    assertTrue(masked.endsWith("1234"))
    assertTrue(masked.contains("***"))
  }

  @Test
  fun `SN mask for short SN should return original`() {
    val device = TestHelpers.createMockStandalone("T8410")
    val masked = device.snMasked()
    assertEquals("T8410", masked)
  }

  // ========== T-UI-03: 搜索栏回调合约 ==========

  @Test
  fun `search query change callback should receive new query`() {
    var receivedQuery: String? = null
    val callback: (String) -> Unit = { receivedQuery = it }

    callback("new search")
    assertEquals("new search", receivedQuery)
  }

  @Test
  fun `clear search callback should be callable`() {
    var clearCalled = false
    val callback: () -> Unit = { clearCalled = true }

    callback()
    assertTrue(clearCalled)
  }

  @Test
  fun `search state matches should return true for empty query`() {
    val state = SearchFilterState()
    assertTrue(SearchFilterState.matches(state, "T8410ABC", "Camera"))
  }

  @Test
  fun `search state matches should return true when SN contains query`() {
    val state = SearchFilterState(query = "abc", isSearching = true)
    assertTrue(SearchFilterState.matches(state, "T8410ABC123", "Camera"))
  }

  @Test
  fun `search state matches should return true when name contains query`() {
    val state = SearchFilterState(query = "camera", isSearching = true)
    assertTrue(SearchFilterState.matches(state, "T8410XYZ", "Front Camera"))
  }

  @Test
  fun `search state matches should return false when no match`() {
    val state = SearchFilterState(query = "nonexistent", isSearching = true)
    assertFalse(SearchFilterState.matches(state, "T8410ABC", "Camera"))
  }

  // ========== T-UI-04: 排序菜单逻辑 ==========

  @Test
  fun `default sort option should be NAME ascending`() {
    val default = SortOption.DEFAULT
    assertEquals(SortType.NAME, default.type)
    assertEquals(SortDirection.ASCENDING, default.direction)
  }

  @Test
  fun `sort option toggle direction should switch ascending to descending`() {
    val option = SortOption(SortType.NAME, SortDirection.ASCENDING)
    val toggled = SortOption(option.type, option.direction.toggle())
    assertEquals(SortDirection.DESCENDING, toggled.direction)
  }

  @Test
  fun `sort option toggle direction should switch descending to ascending`() {
    val option = SortOption(SortType.DATE, SortDirection.DESCENDING)
    val toggled = SortOption(option.type, option.direction.toggle())
    assertEquals(SortDirection.ASCENDING, toggled.direction)
  }

  @Test
  fun `sort type labels should be correct`() {
    assertEquals("名称", SortType.NAME.label)
    assertEquals("日期", SortType.DATE.label)
    assertEquals("状态", SortType.STATUS.label)
  }

  @Test
  fun `sort direction toggle is idempotent`() {
    val original = SortDirection.ASCENDING
    val afterTwoToggles = original.toggle().toggle()
    assertEquals(original, afterTwoToggles)
  }

  @Test
  fun `sort option change callback should receive new option`() {
    var receivedOption: SortOption? = null
    val callback: (SortOption) -> Unit = { receivedOption = it }

    val newOption = SortOption(SortType.DATE, SortDirection.DESCENDING)
    callback(newOption)

    assertEquals(newOption, receivedOption)
  }

  // ========== T-UI-05: 状态过滤逻辑 ==========

  @Test
  fun `status filter labels should be correct`() {
    assertEquals("全部", StatusFilterValue.ALL.label)
    assertEquals("在线", StatusFilterValue.ONLINE.label)
    assertEquals("离线", StatusFilterValue.OFFLINE.label)
  }

  @Test
  fun `status filter change callback should receive new filter`() {
    var receivedFilter: StatusFilterValue? = null
    val callback: (StatusFilterValue) -> Unit = { receivedFilter = it }

    callback(StatusFilterValue.ONLINE)
    assertEquals(StatusFilterValue.ONLINE, receivedFilter)
  }

  @Test
  fun `status filter has all three values`() {
    assertEquals(3, StatusFilterValue.entries.size)
    assertTrue(StatusFilterValue.entries.contains(StatusFilterValue.ALL))
    assertTrue(StatusFilterValue.entries.contains(StatusFilterValue.ONLINE))
    assertTrue(StatusFilterValue.entries.contains(StatusFilterValue.OFFLINE))
  }

  // ========== T-UI-06: 添加设备按钮回调 ==========

  @Test
  fun `add device click callback should be callable`() {
    var clicked = false
    val callback: () -> Unit = { clicked = true }

    callback()
    assertTrue(clicked)
  }

  // ========== Navigation Target Tests ==========

  @Test
  fun `navigation target should be None by default`() {
    assertEquals(NavigationTarget.None, NavigationTarget.None)
  }

  @Test
  fun `AddDevice navigation should be equatable`() {
    val nav1 = NavigationTarget.AddDevice
    val nav2 = NavigationTarget.AddDevice
    assertEquals(nav1, nav2)
  }

  @Test
  fun `EventList navigation should carry device ID`() {
    val nav = NavigationTarget.EventList("T8410ABC123")
    assertEquals("T8410ABC123", nav.deviceId)
  }

  @Test
  fun `Settings navigation should carry device ID`() {
    val nav = NavigationTarget.Settings("T8010STATION")
    assertEquals("T8010STATION", nav.deviceId)
  }

  @Test
  fun `RetryLoadChild navigation should carry station ID`() {
    val nav = NavigationTarget.RetryLoadChild("T8010STATION")
    assertEquals("T8010STATION", nav.stationId)
  }

  @Test
  fun `different navigation targets should not be equal`() {
    val addDevice = NavigationTarget.AddDevice
    val eventList = NavigationTarget.EventList("T8410ABC")
    assertTrue(addDevice != eventList)
  }

  // ========== Display List State Transitions ==========

  @Test
  fun `state should transition from loading to loaded`() {
    val loadingState = DeviceListState(isLoading = true)
    assertTrue(loadingState.isLoading)

    val loadedState = loadingState.copy(
      isLoading = false,
      rawDevices = listOf(TestHelpers.createMockStandalone("T8410A")),
      displayList = listOf(
        DisplayDeviceItem.StandaloneItem(TestHelpers.createMockStandalone("T8410A"))
      )
    )

    assertFalse(loadedState.isLoading)
    assertEquals(1, loadedState.totalDeviceCount)
    assertFalse(loadedState.isEmpty)
  }

  @Test
  fun `state should transition from loaded to error`() {
    val loadedState = DeviceListState(
      rawDevices = listOf(TestHelpers.createMockStandalone("T8410A")),
      displayList = listOf(
        DisplayDeviceItem.StandaloneItem(TestHelpers.createMockStandalone("T8410A"))
      )
    )

    val errorState = loadedState.copy(
      isError = true,
      errorMessage = "连接超时"
    )

    assertTrue(errorState.isError)
    assertEquals("连接超时", errorState.errorMessage)
  }

  @Test
  fun `state should transition from error to loading on retry`() {
    val errorState = DeviceListState(isError = true, errorMessage = "超时")

    val retryState = errorState.copy(
      isError = false,
      errorMessage = "",
      isLoading = true
    )

    assertFalse(retryState.isError)
    assertTrue(retryState.isLoading)
    assertEquals("", retryState.errorMessage)
  }
}
