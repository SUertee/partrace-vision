package com.anker.esiotkit.sample.main.mega.device_settings

import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import org.junit.Test
import org.junit.Assert.*
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * MegaDevice 构造说明：
 * MegaDevice 是 open class，主构造需要 ESIotDevice（DI 体系对象），
 * 无法在单元测试中直接实例化，使用 Mockito mock deviceSn 属性。
 *
 * 依赖：testImplementation("org.mockito.kotlin:mockito-kotlin:5.x")
 *      testImplementation("org.mockito:mockito-core:5.x")
 */
class DeviceSettingsConfigTest {

    private fun fakeDevice(sn: String): MegaDevice {
        val device = mock<MegaDevice>()
        whenever(device.deviceSn).thenReturn(sn)
        return device
    }

    @Test
    fun `T8N00 设备应使用 T8N00SettingsConfig`() {
        val device = fakeDevice("T8N00TESTDEVICE1")
        val config = DeviceSettingsConfigFactory.getConfig(device)
        assertTrue(config is T8N00SettingsConfig)
    }

    @Test
    fun `T8E00 设备应使用 NVRSettingsConfig`() {
        val device = fakeDevice("T8E00TESTDEVICE1")
        val config = DeviceSettingsConfigFactory.getConfig(device)
        assertTrue(config is NVRSettingsConfig)
    }

    @Test
    fun `T8P00 设备应使用 NVRSettingsConfig`() {
        val device = fakeDevice("T8P00TESTDEVICE1")
        val config = DeviceSettingsConfigFactory.getConfig(device)
        assertTrue(config is NVRSettingsConfig)
    }

    @Test
    fun `T8P10 设备应使用 NVRSettingsConfig`() {
        val device = fakeDevice("T8P10TESTDEVICE1")
        val config = DeviceSettingsConfigFactory.getConfig(device)
        assertTrue(config is NVRSettingsConfig)
    }

    @Test
    fun `T8024 设备应使用 T8024SettingsConfig`() {
        val device = fakeDevice("T8024TESTDEVICE1")
        val config = DeviceSettingsConfigFactory.getConfig(device)
        assertTrue(config is T8024SettingsConfig)
    }

    @Test
    fun `T8024 设备时间设置不可见`() {
        val device = fakeDevice("T8024TESTDEVICE1")
        val config = DeviceSettingsConfigFactory.getConfig(device)
        assertFalse(config.isTimeSettingsVisible())
    }

    @Test
    fun `T8020 设备应使用 NVRSettingsConfig`() {
        val device = fakeDevice("T8020TESTDEVICE1")
        val config = DeviceSettingsConfigFactory.getConfig(device)
        assertTrue(config is NVRSettingsConfig)
    }

    @Test
    fun `未知设备应使用 DefaultSettingsConfig`() {
        val device = fakeDevice("T9999TESTDEVICE1")
        val config = DeviceSettingsConfigFactory.getConfig(device)
        assertTrue(config is DefaultSettingsConfig)
    }

    @Test
    fun `T8417 设备应使用 T8417SettingsConfig`() {
        val device = fakeDevice("T8417TESTDEVICE1")
        val config = DeviceSettingsConfigFactory.getConfig(device)
        assertTrue(config is T8417SettingsConfig)
    }
}
