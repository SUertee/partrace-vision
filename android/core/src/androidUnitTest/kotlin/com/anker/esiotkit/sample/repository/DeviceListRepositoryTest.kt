package com.anker.esiotkit.sample.repository

import com.anker.esiotkit.sample.model.DisplayDeviceItem
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-REP-01: 设备列表层级构建单元测试
 *
 * 测试 buildHierarchicalList 函数的各种场景：
 * - 无基站（全部独立设备）
 * - 有基站和子设备
 * - 多基站
 * - 基站无子设备
 * - 空列表
 */
class DeviceListRepositoryTest {

  @BeforeTest
  fun setup() {
    unmockkAll()
  }

  @AfterTest
  fun teardown() {
    unmockkAll()
  }

  // Helper to create a mocked MegaDevice
  // For stations: use T8010/T8020/T8030 prefix and deviceSn == parentSn
  // For non-stations: use T8410/T8200 prefix
  private fun createMockDevice(
    deviceSn: String,
    parentSn: String = deviceSn,
    deviceName: String = ""
  ): MegaDevice {
    val iotDevice = com.anker.esiotkit.core.entities.device.ESIotDevice(
      deviceSn = deviceSn,
      parentSn = parentSn,
      deviceName = deviceName
    )

    val mock = mockk<MegaDevice>()
    every { mock.deviceSn } returns deviceSn
    every { mock.parentSn } returns parentSn
    every { mock.iotDevice } returns iotDevice
    every { mock.getProperty(any<String>()) } returns null
    return mock
  }

  // === buildHierarchicalList tests ===

  @Test
  fun `buildHierarchicalList should return empty list for empty input`() {
    val result = DeviceListRepository.buildHierarchicalList(emptyList())
    assertEquals(0, result.size)
  }

  @Test
  fun `buildHierarchicalList should return StandaloneItem for single independent device`() {
    // T8410 is a camera, not a station
    val device = createMockDevice("T8410XXX", deviceName = "Camera 1")
    val result = DeviceListRepository.buildHierarchicalList(listOf(device))

    assertEquals(1, result.size)
    assertTrue(result[0] is DisplayDeviceItem.StandaloneItem)
    assertEquals("T8410XXX", result[0].id)
  }

  @Test
  fun `buildHierarchicalList should return all StandaloneItem for devices without station`() {
    // T8410 = camera, T8200 = doorbell - neither is a station
    val d1 = createMockDevice("T8410A", deviceName = "Camera 1")
    val d2 = createMockDevice("T8410B", deviceName = "Camera 2")
    val d3 = createMockDevice("T8200A", deviceName = "Doorbell 1")

    val result = DeviceListRepository.buildHierarchicalList(listOf(d1, d2, d3))

    assertEquals(3, result.size)
    assertTrue(result.all { it is DisplayDeviceItem.StandaloneItem })
  }

  @Test
  fun `buildHierarchicalList should return StationItem with children for station with sub-devices`() {
    // T8010 is a station prefix
    val station = createMockDevice("T8010STATION")
    val child1 = createMockDevice("T8410CHILD1", parentSn = "T8010STATION")
    val child2 = createMockDevice("T8410CHILD2", parentSn = "T8010STATION")

    val result = DeviceListRepository.buildHierarchicalList(listOf(station, child1, child2))

    assertEquals(1, result.size)
    val stationItem = result[0] as DisplayDeviceItem.StationItem
    assertEquals("T8010STATION", stationItem.id)
    assertEquals(2, stationItem.children.size)
  }

  @Test
  fun `buildHierarchicalList should return StationItem with empty children for station without sub-devices`() {
    val station = createMockDevice("T8010STATION")

    val result = DeviceListRepository.buildHierarchicalList(listOf(station))

    assertEquals(1, result.size)
    val stationItem = result[0] as DisplayDeviceItem.StationItem
    assertEquals(0, stationItem.children.size)
  }

  @Test
  fun `buildHierarchicalList should handle multiple stations with their children`() {
    val station1 = createMockDevice("T8010STA1")
    val station2 = createMockDevice("T8020STA2") // T8020 is also a station
    val child1 = createMockDevice("T8410C1", parentSn = "T8010STA1")
    val child2 = createMockDevice("T8410C2", parentSn = "T8020STA2")
    val child3 = createMockDevice("T8410C3", parentSn = "T8010STA1")
    val standalone = createMockDevice("T8410S")

    val result = DeviceListRepository.buildHierarchicalList(
      listOf(station1, station2, child1, child2, child3, standalone)
    )

    assertEquals(3, result.size) // 2 stations + 1 standalone

    val stationItems = result.filterIsInstance<DisplayDeviceItem.StationItem>()
    assertEquals(2, stationItems.size)

    val sta1 = stationItems.find { it.id == "T8010STA1" }!!
    assertEquals(2, sta1.children.size)

    val sta2 = stationItems.find { it.id == "T8020STA2" }!!
    assertEquals(1, sta2.children.size)
  }

  @Test
  fun `buildHierarchicalList should handle child device without parent in list`() {
    val child = createMockDevice("T8410CHILD", parentSn = "T8010MISSING")

    val result = DeviceListRepository.buildHierarchicalList(listOf(child))

    // Child without parent should become standalone
    assertEquals(1, result.size)
    assertTrue(result[0] is DisplayDeviceItem.StandaloneItem)
  }
}
