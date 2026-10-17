package com.anker.esiotkit.sample.repository

import com.anker.esiotkit.sample.model.DeviceStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-REP-02: DeviceStatusRepository 单元测试
 */
class DeviceStatusRepositoryTest {

  @AfterTest
  fun teardown() {
    // Reset internal state
    DeviceStatusRepository.clearDebounce()
  }

  // === getDeviceStatus tests ===

  @Test
  fun `getDeviceStatus should return UNKNOWN for unknown device`() = runBlocking {
    val result = DeviceStatusRepository.getDeviceStatus("unknown_device")
    assertTrue(result.isSuccess)
    assertEquals(DeviceStatus.UNKNOWN, result.getOrNull())
  }

  @Test
  fun `getDeviceStatus should return updated status`() = runBlocking {
    DeviceStatusRepository.updateDeviceStatus("device1", DeviceStatus.ONLINE)

    val result = DeviceStatusRepository.getDeviceStatus("device1")
    assertTrue(result.isSuccess)
    assertEquals(DeviceStatus.ONLINE, result.getOrNull())
  }

  // === getDeviceStatusBatch tests ===

  @Test
  fun `getDeviceStatusBatch should return status for multiple devices`() = runBlocking {
    DeviceStatusRepository.updateDeviceStatus("d1", DeviceStatus.ONLINE)
    DeviceStatusRepository.updateDeviceStatus("d2", DeviceStatus.OFFLINE)

    val result = DeviceStatusRepository.getDeviceStatusBatch(listOf("d1", "d2", "d3"))
    assertTrue(result.isSuccess)
    val statusMap = result.getOrNull()!!
    assertEquals(DeviceStatus.ONLINE, statusMap["d1"])
    assertEquals(DeviceStatus.OFFLINE, statusMap["d2"])
    assertEquals(DeviceStatus.UNKNOWN, statusMap["d3"])
  }

  @Test
  fun `getDeviceStatusBatch should handle empty list`() = runBlocking {
    val result = DeviceStatusRepository.getDeviceStatusBatch(emptyList())
    assertTrue(result.isSuccess)
    assertEquals(0, result.getOrNull()!!.size)
  }

  // === updateDeviceStatus tests ===

  @Test
  fun `updateDeviceStatus should update internal state`() = runBlocking {
    DeviceStatusRepository.updateDeviceStatus("device1", DeviceStatus.ONLINE)

    val flowValue = DeviceStatusRepository.observeDeviceStatus().first()
    assertEquals(DeviceStatus.ONLINE, flowValue["device1"])
  }

  @Test
  fun `updateDeviceStatusBatch should update multiple devices`() = runBlocking {
    val statusMap = mapOf(
      "d1" to DeviceStatus.ONLINE,
      "d2" to DeviceStatus.OFFLINE,
      "d3" to DeviceStatus.UPGRADING
    )
    DeviceStatusRepository.updateDeviceStatusBatch(statusMap)

    val flowValue = DeviceStatusRepository.observeDeviceStatus().first()
    assertTrue(flowValue.size >= 3)
    assertEquals(DeviceStatus.ONLINE, flowValue["d1"])
    assertEquals(DeviceStatus.OFFLINE, flowValue["d2"])
    assertEquals(DeviceStatus.UPGRADING, flowValue["d3"])
  }

  // === observeDeviceStatus tests ===

  @Test
  fun `observeDeviceStatus should emit initial empty map`() = runBlocking {
    // Clear state first
    DeviceStatusRepository.updateDeviceStatusBatch(emptyMap())

    val flowValue = DeviceStatusRepository.observeDeviceStatus().first()
    assertTrue(flowValue is Map<*, *>)
  }
}
