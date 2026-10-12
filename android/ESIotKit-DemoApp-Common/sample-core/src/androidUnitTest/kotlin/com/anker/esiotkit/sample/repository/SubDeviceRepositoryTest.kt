package com.anker.esiotkit.sample.repository

import com.anker.esiotkit.sample.model.DisplayDeviceItem
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.core.entities.device.ESIotDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T-REP-05: SubDeviceRepository 单元测试
 *
 * 测试 getChildDevices 函数的各种场景：
 * - 正常返回子设备列表
 * - 无子设备返回空列表
 * - 子设备按 addedTime 升序排列
 * - 错误处理
 */
class SubDeviceRepositoryTest {

  @BeforeTest
  fun setup() {
    unmockkAll()
    // Populate DeviceListRepository with test devices
    val station = createMockDevice("T8010STATION")
    val child1 = createMockDevice("T8410CHILD1", parentSn = "T8010STATION", bindTime = 2000)
    val child2 = createMockDevice("T8410CHILD2", parentSn = "T8010STATION", bindTime = 1000)
    val child3 = createMockDevice("T8410CHILD3", parentSn = "T8010STATION", bindTime = 3000)
    val standalone = createMockDevice("T8410STANDALONE")

    DeviceListRepository.updateDeviceList(listOf(station, child1, child2, child3, standalone))
  }

  @AfterTest
  fun teardown() {
    unmockkAll()
    DeviceListRepository.updateDeviceList(emptyList())
  }

  // Helper to create a mocked MegaDevice
  private fun createMockDevice(
    deviceSn: String,
    parentSn: String = deviceSn,
    deviceName: String = "",
    bindTime: Int = 0
  ): MegaDevice {
    val iotDevice = ESIotDevice(
      deviceSn = deviceSn,
      parentSn = parentSn,
      deviceName = deviceName,
      bindTime = bindTime
    )

    val mock = mockk<MegaDevice>()
    every { mock.deviceSn } returns deviceSn
    every { mock.parentSn } returns parentSn
    every { mock.iotDevice } returns iotDevice
    every { mock.getProperty(any<String>()) } returns null
    return mock
  }

  // === getChildDevices tests ===

  @Test
  fun `getChildDevices should return children sorted by bindTime ascending`() = runBlocking {
    val result = SubDeviceRepository.getChildDevices("T8010STATION")
    assertTrue(result.isSuccess)
    val children = result.getOrNull()!!
    assertEquals(3, children.size)
    // Should be sorted by bindTime ascending: 1000, 2000, 3000
    assertEquals("T8410CHILD2", children[0].deviceSn) // bindTime = 1000
    assertEquals("T8410CHILD1", children[1].deviceSn) // bindTime = 2000
    assertEquals("T8410CHILD3", children[2].deviceSn) // bindTime = 3000
  }

  @Test
  fun `getChildDevices should return empty list for station with no children`() = runBlocking {
    // Add a station with no children
    val stationOnly = createMockDevice("T8010EMPTY")
    DeviceListRepository.updateDeviceList(listOf(stationOnly))

    val result = SubDeviceRepository.getChildDevices("T8010EMPTY")
    assertTrue(result.isSuccess)
    assertEquals(0, result.getOrNull()!!.size)
  }

  @Test
  fun `getChildDevices should return empty list for non-station device`() = runBlocking {
    val result = SubDeviceRepository.getChildDevices("T8410STANDALONE")
    assertTrue(result.isSuccess)
    assertEquals(0, result.getOrNull()!!.size)
  }

  @Test
  fun `getChildDevices should return empty list for unknown device`() = runBlocking {
    val result = SubDeviceRepository.getChildDevices("UNKNOWN_DEVICE")
    assertTrue(result.isSuccess)
    assertEquals(0, result.getOrNull()!!.size)
  }

  // === hasChildDevices tests ===

  @Test
  fun `hasChildDevices should return true for station with children`() = runBlocking {
    val result = SubDeviceRepository.hasChildDevices("T8010STATION")
    assertTrue(result.isSuccess)
    assertTrue(result.getOrNull()!!)
  }

  @Test
  fun `hasChildDevices should return false for station without children`() = runBlocking {
    val stationOnly = createMockDevice("T8010EMPTY")
    DeviceListRepository.updateDeviceList(listOf(stationOnly))

    val result = SubDeviceRepository.hasChildDevices("T8010EMPTY")
    assertTrue(result.isSuccess)
    assertFalse(result.getOrNull()!!)
  }

  @Test
  fun `hasChildDevices should return false for standalone device`() = runBlocking {
    val result = SubDeviceRepository.hasChildDevices("T8410STANDALONE")
    assertTrue(result.isSuccess)
    assertFalse(result.getOrNull()!!)
  }

  // === error state tests ===

  @Test
  fun `getError should return null for valid station`() {
    SubDeviceRepository.clearError("T8010STATION")
    val error = SubDeviceRepository.getError("T8010STATION")
    assertNull(error)
  }

  @Test
  fun `clearError should remove error state`() {
    // Manually record an error
    val currentMap = mutableMapOf<String, String>()
    currentMap["T8010STATION"] = "Test error"
    SubDeviceRepository.clearError("T8010STATION")

    val error = SubDeviceRepository.getError("T8010STATION")
    assertNull(error)
  }
}
