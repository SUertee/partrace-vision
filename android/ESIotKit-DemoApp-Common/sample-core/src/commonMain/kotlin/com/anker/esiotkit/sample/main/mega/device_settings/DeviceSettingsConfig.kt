package com.anker.esiotkit.sample.main.mega.device_settings

import com.anker.esiotkit.domain_thing.model.device.MegaDevice

/**
 * 设备设置页面配置基类
 * 用于控制不同设备型号的功能项可见性
 * 参考: BatteryCam/feature_security/homebase/adapter/HBNewGeneralSettingConfig.kt
 */
abstract class DeviceSettingsConfig(protected val megaDevice: MegaDevice) {

    /**
     * 时间设置是否可见
     */
    open fun isTimeSettingsVisible(): Boolean = true

    /**
     * 电源管理是否可见
     */
    open fun isPowerManagerVisible(): Boolean = true

    /**
     * 灯光设置是否可见
     */
    open fun isLightSettingsVisible(): Boolean = true

    /**
     * 夜视设置是否可见
     */
    open fun isNightVisionVisible(): Boolean = true

    /**
     * HomeBase连接是否可见
     */
    open fun isHomeBaseVisible(): Boolean = true

    /**
     * 运动检测是否可见
     */
    open fun isMotionDetectionVisible(): Boolean = true

    /**
     * 仪表盘是否可见
     */
    open fun isDashboardVisible(): Boolean = true

    /**
     * 视频设置是否可见
     */
    open fun isVideoSettingsVisible(): Boolean = true

    /**
     * 音频设置是否可见
     */
    open fun isAudioSettingsVisible(): Boolean = true

    /**
     * PTZ设置（云台/角度设置）是否可见
     */
    open fun isPTZSettingsVisible(): Boolean = true
}

/**
 * T8024 设备配置
 * 特点：不显示时间设置
 */
class T8024SettingsConfig(megaDevice: MegaDevice) : DeviceSettingsConfig(megaDevice) {
    override fun isTimeSettingsVisible(): Boolean = false
}

/**
 * NVR 设备配置
 * 适用：T8E00/T8P00/T8P10/T8020（NVR 摄像头系列）
 */
class NVRSettingsConfig(megaDevice: MegaDevice) : DeviceSettingsConfig(megaDevice)

/**
 * T8N00 设备配置
 * 特点：与 DefaultSettingsConfig 相同，显式路由便于后续差异化
 */
class T8N00SettingsConfig(megaDevice: MegaDevice) : DeviceSettingsConfig(megaDevice)

/**
 * T8417 设备配置
 * 特点：独立摄像头设备（非 NVR 子设备），显式路由便于后续差异化
 */
class T8417SettingsConfig(megaDevice: MegaDevice) : DeviceSettingsConfig(megaDevice)

/**
 * 门铃设备配置
 * 适用：T8210/T8214/T8223/T8224 等门铃设备
 * 特点：门铃物模型可能包含 ptz 相关属性但物理不支持 PTZ，通过 Config 排除
 * 依据: Mega Android DoorbellGeneralStrategy 不调用 isPtDevice，门铃不进入 PTZ 设置
 */
class DoorbellSettingsConfig(megaDevice: MegaDevice) : DeviceSettingsConfig(megaDevice) {
    override fun isPTZSettingsVisible(): Boolean = false
}

/**
 * 默认设备配置
 */
class DefaultSettingsConfig(megaDevice: MegaDevice) : DeviceSettingsConfig(megaDevice)

/**
 * 设备配置工厂
 */
object DeviceSettingsConfigFactory {
    fun getConfig(megaDevice: MegaDevice): DeviceSettingsConfig {
        return when {
            megaDevice.deviceSn.startsWith("T8N00") -> T8N00SettingsConfig(megaDevice)
            megaDevice.deviceSn.startsWith("T8417") -> T8417SettingsConfig(megaDevice)
            megaDevice.deviceSn.startsWith("T8E00") -> NVRSettingsConfig(megaDevice)
            megaDevice.deviceSn.startsWith("T8P00") -> NVRSettingsConfig(megaDevice)
            megaDevice.deviceSn.startsWith("T8P10") -> NVRSettingsConfig(megaDevice)
            megaDevice.deviceSn.startsWith("T8024") -> T8024SettingsConfig(megaDevice)
            megaDevice.deviceSn.startsWith("T8020") -> NVRSettingsConfig(megaDevice)
            megaDevice.deviceSn.startsWith("T8210") ||
            megaDevice.deviceSn.startsWith("T8214") ||
            megaDevice.deviceSn.startsWith("T8223") ||
            megaDevice.deviceSn.startsWith("T8224") -> DoorbellSettingsConfig(megaDevice)
            else -> DefaultSettingsConfig(megaDevice)
        }
    }
}