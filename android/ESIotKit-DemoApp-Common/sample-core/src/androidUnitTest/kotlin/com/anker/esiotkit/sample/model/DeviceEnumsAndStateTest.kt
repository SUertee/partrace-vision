package com.anker.esiotkit.sample.model

import io.mockk.mockk
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-DM-02: DeviceType 和 DeviceStatus 枚举单元测试
 */
class DeviceEnumsTest {

  @BeforeTest
  fun setup() {
    unmockkAll()
  }

  @AfterTest
  fun teardown() {
    unmockkAll()
  }

  // === DeviceType tests ===

  @Test
  fun `DeviceType BASE_STATION should have isStation true`() {
    assertTrue(DeviceType.BASE_STATION.isStation)
  }

  @Test
  fun `DeviceType CAMERA should not be a station`() {
    assertFalse(DeviceType.CAMERA.isStation)
  }

  @Test
  fun `DeviceType CAMERA and DOORBELL should support thumbnail`() {
    assertTrue(DeviceType.CAMERA.supportsThumbnail)
    assertTrue(DeviceType.DOORBELL.supportsThumbnail)
  }

  @Test
  fun `DeviceType BASE_STATION should not support thumbnail`() {
    assertFalse(DeviceType.BASE_STATION.supportsThumbnail)
  }

  @Test
  fun `DeviceType fromCode should return matching type`() {
    assertEquals(DeviceType.BASE_STATION, DeviceType.fromCode(0))
    assertEquals(DeviceType.CAMERA, DeviceType.fromCode(1))
    assertEquals(DeviceType.DOORBELL, DeviceType.fromCode(2))
  }

  @Test
  fun `DeviceType fromCode should return OTHER for unknown code`() {
    assertEquals(DeviceType.OTHER, DeviceType.fromCode(999))
  }

  // === DeviceStatus tests ===

  @Test
  fun `DeviceStatus ONLINE should be reachable`() {
    assertTrue(DeviceStatus.ONLINE.isReachable)
  }

  @Test
  fun `DeviceStatus OFFLINE should not be reachable`() {
    assertFalse(DeviceStatus.OFFLINE.isReachable)
  }

  @Test
  fun `DeviceStatus UPGRADING should have upgrading card variant`() {
    assertEquals("upgrading", DeviceStatus.UPGRADING.cardStyleVariant)
  }

  @Test
  fun `DeviceStatus fromCode should return matching status`() {
    assertEquals(DeviceStatus.ONLINE, DeviceStatus.fromCode(1))
    assertEquals(DeviceStatus.OFFLINE, DeviceStatus.fromCode(0))
  }

  @Test
  fun `DeviceStatus fromCode should return UNKNOWN for unknown code`() {
    assertEquals(DeviceStatus.UNKNOWN, DeviceStatus.fromCode(-999))
  }
}

/**
 * T-DM-03: 状态模型单元测试
 */
class DeviceStateTest {

  @AfterTest
  fun teardown() {
    unmockkAll()
  }

  // Helper to create a mocked MegaDevice
  private fun createMockDevice(
    deviceSn: String,
    parentSn: String = deviceSn,
    deviceName: String = ""
  ): com.anker.esiotkit.domain_thing.model.device.MegaDevice {
    val iotDevice = com.anker.esiotkit.core.entities.device.ESIotDevice(
      deviceSn = deviceSn,
      parentSn = parentSn,
      deviceName = deviceName
    )

    val mock = mockk<com.anker.esiotkit.domain_thing.model.device.MegaDevice>()
    io.mockk.every { mock.deviceSn } returns deviceSn
    io.mockk.every { mock.parentSn } returns parentSn
    io.mockk.every { mock.iotDevice } returns iotDevice
    io.mockk.every { mock.getProperty(any<String>()) } returns null
    return mock
  }

  // === DisplayDeviceItem tests ===

  @Test
  fun `StationItem id should be station deviceSn`() {
    val station = createMockDevice("T8010XXXXXXXX")
    val item = DisplayDeviceItem.StationItem(station)
    assertEquals("T8010XXXXXXXX", item.id)
  }

  @Test
  fun `StandaloneItem id should be device deviceSn`() {
    val device = createMockDevice("T8410XXXXXXXX")
    val item = DisplayDeviceItem.StandaloneItem(device)
    assertEquals("T8410XXXXXXXX", item.id)
  }

  // === SortOption tests ===

  @Test
  fun `SortOption DEFAULT should be NAME ASCENDING`() {
    assertEquals(SortType.NAME, SortOption.DEFAULT.type)
    assertEquals(SortDirection.ASCENDING, SortOption.DEFAULT.direction)
  }

  // === SearchFilterState tests ===

  @Test
  fun `matches should return true for empty query`() {
    val state = SearchFilterState(query = "", isSearching = false)
    assertTrue(SearchFilterState.matches(state, "T8010XXX", "My Device"))
  }

  @Test
  fun `matches should return true when SN matches query`() {
    val state = SearchFilterState(query = "T8010", isSearching = true)
    assertTrue(SearchFilterState.matches(state, "T8010XXX", "My Device"))
  }

  @Test
  fun `matches should return true when name matches query`() {
    val state = SearchFilterState(query = "my device", isSearching = true)
    assertTrue(SearchFilterState.matches(state, "T8010XXX", "My Device"))
  }

  @Test
  fun `matches should return false when neither SN nor name matches`() {
    val state = SearchFilterState(query = "NOTFOUND", isSearching = true)
    assertFalse(SearchFilterState.matches(state, "T8010XXX", "My Device"))
  }

  // === DeviceListState tests ===

  @Test
  fun `DeviceListState isEmpty should be true for empty list`() {
    val state = DeviceListState(rawDevices = emptyList(), isLoading = false)
    assertTrue(state.isEmpty)
  }

  @Test
  fun `DeviceListState isEmpty should be false when loading`() {
    val state = DeviceListState(rawDevices = emptyList(), isLoading = true)
    assertFalse(state.isEmpty)
  }

  @Test
  fun `DeviceListState isSearchNoResults should be true when search yields nothing`() {
    val state = DeviceListState(searchQuery = "test", displayList = emptyList(), isLoading = false)
    assertTrue(state.isSearchNoResults)
  }

  @Test
  fun `DeviceListState isSearchNoResults should be false when query is empty`() {
    val state = DeviceListState(searchQuery = "", displayList = emptyList())
    assertFalse(state.isSearchNoResults)
  }

  @Test
  fun `DeviceListState totalDeviceCount should reflect raw device count`() {
    val d1 = createMockDevice("T8010A")
    val d2 = createMockDevice("T8010B")
    val state = DeviceListState(rawDevices = listOf(d1, d2))
    assertEquals(2, state.totalDeviceCount)
  }
}
