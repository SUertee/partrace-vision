package com.anker.esiotkit.sample.main.mega

import com.anker.esiotkit.core.entities.device.ESIotDevice
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdkDeviceOrderTest {
  @Test
  fun `cameras stay visible when their parents are absent from the response`() {
    val entrance = device("entrance-camera", parentSn = "missing-parent-a")
    val shelves = device("shelf-camera", parentSn = "missing-parent-b")

    assertEquals(
      listOf("entrance-camera", "shelf-camera"),
      sortSdkDeviceList(listOf(entrance, shelves)).map { it.deviceSn }
    )
  }

  @Test
  fun `newer parents appear first with their newest children immediately below them`() {
    val olderParent = device("older-station", bindTime = 100)
    val newerParent = device("newer-station", bindTime = 200)
    val olderCamera = device("older-camera", parentSn = "older-station", bindTime = 10)
    val newerCamera = device("newer-camera", parentSn = "older-station", bindTime = 30)
    val newestStationCamera = device("station-camera", parentSn = "newer-station", bindTime = 5)

    assertEquals(
      listOf("newer-station", "station-camera", "older-station", "newer-camera", "older-camera"),
      sortSdkDeviceList(
        listOf(olderCamera, olderParent, newerCamera, newestStationCamera, newerParent)
      ).map { it.deviceSn }
    )
  }

  @Test
  fun `an empty SDK response produces an empty list`() {
    assertTrue(sortSdkDeviceList(emptyList()).isEmpty())
  }

  private fun device(sn: String, parentSn: String = sn, bindTime: Int = 0): MegaDevice {
    val metadata = ESIotDevice(deviceSn = sn, parentSn = parentSn, bindTime = bindTime)
    val device = mockk<MegaDevice>()
    every { device.deviceSn } returns sn
    every { device.parentSn } returns parentSn
    every { device.iotDevice } returns metadata
    return device
  }
}
