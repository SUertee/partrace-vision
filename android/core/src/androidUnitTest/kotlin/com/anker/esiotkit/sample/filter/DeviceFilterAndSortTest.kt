package com.anker.esiotkit.sample.filter

import com.anker.esiotkit.sample.model.SortDirection
import com.anker.esiotkit.sample.model.SortOption
import com.anker.esiotkit.sample.model.SortType
import com.anker.esiotkit.sample.model.StatusFilterValue
import com.anker.esiotkit.sample.model.displayName
import com.anker.esiotkit.core.entities.device.ESIotDevice
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.domain_thing.model.thing.ThingProperty
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-VM-02: DeviceFilterEngine 和 DeviceSortEngine 单元测试
 *
 * Note: isOnline() depends on SDK's ThingPayload.asBoolean() which requires
 * native environment. Status-related tests are covered by integration tests.
 * For unit tests, we use a mock-friendly isOnline override via property injection.
 */
class DeviceFilterAndSortTest {

  @BeforeTest
  fun setup() {
    unmockkAll()
  }

  @AfterTest
  fun teardown() {
    unmockkAll()
  }

  // Helpers
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

    val onlinePropMock = mockk<ThingProperty> {
      val realPayload = ThingPayload.fromBoolean(false)
      every { get() } returns realPayload
    }

    val mock = mockk<MegaDevice>()
    every { mock.deviceSn } returns deviceSn
    every { mock.parentSn } returns parentSn
    every { mock.iotDevice } returns iotDevice
    every { mock.getProperty(ThingIdentifier.DEVICE_IS_ONLINE) } returns onlinePropMock
    every { mock.getProperty("device_pn") } returns null
    every { mock.getProperty(any<String>()) } answers { null }
    return mock
  }

  // === Filter Engine tests ===

  @Test
  fun `applySearchFilter should return all devices for empty query`() {
    val devices = listOf(
      createMockDevice("T8410A", deviceName = "Camera 1"),
      createMockDevice("T8410B", deviceName = "Camera 2")
    )

    val result = DeviceFilterEngine.applySearchFilter(devices, "")
    assertEquals(2, result.size)
  }

  @Test
  fun `applySearchFilter should match by device name`() {
    val devices = listOf(
      createMockDevice("T8410A", deviceName = "Front Camera"),
      createMockDevice("T8410B", deviceName = "Back Door")
    )

    val result = DeviceFilterEngine.applySearchFilter(devices, "front")
    assertEquals(1, result.size)
    assertEquals("Front Camera", result[0].displayName())
  }

  @Test
  fun `applySearchFilter should be case insensitive`() {
    val devices = listOf(
      createMockDevice("T8410A", deviceName = "My Camera")
    )

    val result = DeviceFilterEngine.applySearchFilter(devices, "MY CAMERA")
    assertEquals(1, result.size)
  }

  @Test
  fun `applySearchFilter should match by SN`() {
    val devices = listOf(
      createMockDevice("T8410ABC123", deviceName = "Device 1"),
      createMockDevice("T8410XYZ789", deviceName = "Device 2")
    )

    val result = DeviceFilterEngine.applySearchFilter(devices, "ABC")
    assertEquals(1, result.size)
    assertEquals("T8410ABC123", result[0].deviceSn)
  }

  @Test
  fun `applySearchFilter should include station when child matches`() {
    val station = createMockDevice("T8010STATION")
    val child = createMockDevice("T8410CHILD", parentSn = "T8010STATION", deviceName = "Matching Child")

    val devices = listOf(station, child)

    val result = DeviceFilterEngine.applySearchFilter(devices, "Matching")
    assertEquals(2, result.size)
    assertTrue(result.any { it.deviceSn == "T8010STATION" })
    assertTrue(result.any { it.deviceSn == "T8410CHILD" })
  }

  @Test
  fun `applyStatusFilter ALL should return all devices`() {
    val device1 = createMockDevice("T8410A", deviceName = "Device 1")
    val device2 = createMockDevice("T8410B", deviceName = "Device 2")

    val result = DeviceFilterEngine.applyStatusFilter(listOf(device1, device2), StatusFilterValue.ALL)
    assertEquals(2, result.size)
  }

  // Note: applyStatusFilter ONLINE/OFFLINE tests require SDK's ThingPayload.asBoolean()
  // which needs native environment. Covered by integration tests.

  @Test
  fun `buildSearchState should set isSearching for non-empty query`() {
    val state = DeviceFilterEngine.buildSearchState("test")
    assertTrue(state.isSearching)
    assertEquals("test", state.query)
  }

  @Test
  fun `buildSearchState should not set isSearching for empty query`() {
    val state = DeviceFilterEngine.buildSearchState("")
    assertEquals(false, state.isSearching)
  }

  // === Sort Engine tests ===

  @Test
  fun `applySort by NAME ascending should sort alphabetically`() {
    val devices = listOf(
      createMockDevice("T8410C", deviceName = "Charlie"),
      createMockDevice("T8410A", deviceName = "Alpha"),
      createMockDevice("T8410B", deviceName = "Bravo")
    )

    val result = DeviceSortEngine.applySort(devices, SortOption(SortType.NAME, SortDirection.ASCENDING))
    assertEquals("Alpha", result[0].displayName())
    assertEquals("Bravo", result[1].displayName())
    assertEquals("Charlie", result[2].displayName())
  }

  @Test
  fun `applySort by NAME descending should sort reverse alphabetically`() {
    val devices = listOf(
      createMockDevice("T8410C", deviceName = "Charlie"),
      createMockDevice("T8410A", deviceName = "Alpha"),
      createMockDevice("T8410B", deviceName = "Bravo")
    )

    val result = DeviceSortEngine.applySort(devices, SortOption(SortType.NAME, SortDirection.DESCENDING))
    assertEquals("Charlie", result[0].displayName())
    assertEquals("Bravo", result[1].displayName())
    assertEquals("Alpha", result[2].displayName())
  }

  @Test
  fun `applySort by DATE ascending should sort by bindTime`() {
    val devices = listOf(
      createMockDevice("T8410C", deviceName = "C", bindTime = 300),
      createMockDevice("T8410A", deviceName = "A", bindTime = 100),
      createMockDevice("T8410B", deviceName = "B", bindTime = 200)
    )

    val result = DeviceSortEngine.applySort(devices, SortOption(SortType.DATE, SortDirection.ASCENDING))
    assertEquals(100, (result[0].iotDevice.bindTime as? Int))
    assertEquals(200, (result[1].iotDevice.bindTime as? Int))
    assertEquals(300, (result[2].iotDevice.bindTime as? Int))
  }

  @Test
  fun `applySort by DATE descending should sort by bindTime reverse`() {
    val devices = listOf(
      createMockDevice("T8410C", deviceName = "C", bindTime = 300),
      createMockDevice("T8410A", deviceName = "A", bindTime = 100),
      createMockDevice("T8410B", deviceName = "B", bindTime = 200)
    )

    val result = DeviceSortEngine.applySort(devices, SortOption(SortType.DATE, SortDirection.DESCENDING))
    assertEquals(300, (result[0].iotDevice.bindTime as? Int))
    assertEquals(200, (result[1].iotDevice.bindTime as? Int))
    assertEquals(100, (result[2].iotDevice.bindTime as? Int))
  }

  // Note: applySort by STATUS tests require SDK's ThingPayload.asBoolean()
  // which needs native environment. Covered by integration tests.
}
