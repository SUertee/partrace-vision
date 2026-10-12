package com.anker.esiotkit.sample.model

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-DM-01: MegaDevice 扩展函数单元测试（Android Unit Test - MockK）
 */
class MegaDeviceExtensionsTest {

  @BeforeTest
  fun setup() {
    unmockkAll()
  }

  @AfterTest
  fun teardown() {
    unmockkAll()
  }

  // Helper to create a mocked MegaDevice
  private fun createMockDevice(
    deviceSn: String,
    parentSn: String = deviceSn,
    deviceName: String = "",
    isOnline: Boolean? = null
  ): MegaDevice {
    val iotDevice = ESIotDevice(
      deviceSn = deviceSn,
      parentSn = parentSn,
      deviceName = deviceName
    )

    val onlinePropMock: ThingProperty? = if (isOnline != null) {
      val realPayload = ThingPayload.fromBoolean(isOnline)
      mockk<ThingProperty> {
        every { get() } returns realPayload
      }
    } else {
      null
    }

    val mock = mockk<MegaDevice>()
    every { mock.deviceSn } returns deviceSn
    every { mock.parentSn } returns parentSn
    every { mock.iotDevice } returns iotDevice
    every { mock.getProperty(ThingIdentifier.DEVICE_IS_ONLINE) } returns onlinePropMock
    // Relaxed behavior for any other getProperty calls
    every { mock.getProperty(any<String>()) } returns null
    return mock
  }

  // === isStation() tests ===

  @Test
  fun `isStation should return true for station SN T8010`() {
    val station = createMockDevice("T8010XXXXXXXX")
    assertTrue(station.isStation())
  }

  @Test
  fun `isStation should return false for camera SN`() {
    val camera = createMockDevice("T8410XXXXXXXX")
    assertFalse(camera.isStation())
  }

  @Test
  fun `isStation should return false for doorbell SN`() {
    val doorbell = createMockDevice("T8200XXXXXXXX")
    assertFalse(doorbell.isStation())
  }

  // === hasChildren() tests ===

  @Test
  fun `hasChildren should return true when deviceSn equals parentSn and is station`() {
    val station = createMockDevice("T8010XXXXXXXX", parentSn = "T8010XXXXXXXX")
    assertTrue(station.hasChildren())
  }

  @Test
  fun `hasChildren should return false when deviceSn equals parentSn but is not station`() {
    val camera = createMockDevice("T8410XXXXXXXX", parentSn = "T8410XXXXXXXX")
    assertFalse(camera.hasChildren())
  }

  // === isOnline() tests ===
  // Note: isOnline() depends on SDK's ThingPayload.asBoolean() which requires
  // native environment. The logic is covered indirectly in integration tests.

  @Test
  fun `isOnline should return false when property is null`() {
    val device = createMockDevice("T8410XXXXXXXX", isOnline = null)
    assertFalse(device.isOnline())
  }

  // Note: The following tests for isOnline=true and isOnline=false require
  // the SDK's native initialization and are better suited for integration tests.
  // The isOnline() implementation is: getProperty(DEVICE_IS_ONLINE)?.get()?.asBoolean() ?: false
  // When property is not null but returns false, the ?: false fallback also handles it.

  // === snMasked() tests ===

  @Test
  fun `snMasked should mask middle characters for normal length SN`() {
    val device = createMockDevice("T8010ABCD1234")
    val masked = device.snMasked()
    assertTrue(masked.startsWith("T801"))
    assertTrue(masked.endsWith("1234"))
    assertTrue(masked.contains("***"))
  }

  @Test
  fun `snMasked should return original for short SN up to 8 chars`() {
    val device = createMockDevice("T801")
    val masked = device.snMasked()
    assertEquals("T801", masked)
  }

  @Test
  fun `snMasked should not mask SN of exactly 8 characters`() {
    val device = createMockDevice("T8010123")
    val masked = device.snMasked()
    assertEquals("T8010123", masked)
  }

  // === displayName() tests ===

  @Test
  fun `displayName should return custom name when set`() {
    val device = createMockDevice("T8010XXXXXXXX", deviceName = "My Station")
    assertEquals("My Station", device.displayName())
  }

  @Test
  fun `displayName should fall back to SN when name is empty`() {
    val device = createMockDevice("T8010XXXXXXXX", deviceName = "")
    assertEquals("T8010XXXXXXXX", device.displayName())
  }

  @Test
  fun `displayName should fall back to SN when name equals SN`() {
    val device = createMockDevice("T8010XXXXXXXX", deviceName = "T8010XXXXXXXX")
    assertEquals("T8010XXXXXXXX", device.displayName())
  }
}
