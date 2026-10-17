package com.anker.esiotkit.sample.main.mega.device_settings.video_settings

import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.support.log.appLogger

/**
 * 灯光属性解析器 - 处理不同设备物模型属性名差异
 * 
 * 不同设备使用不同的属性名来表示相同的灯光功能：
 * - T8124: floodlight_brightness_value, floodlight_color_temperature_value
 * - T8214: spotlight_brightness_level, light_color_temperature
 * - 其他设备可能使用 spotlight_brightness, spotlight_color_temperature
 * 
 * 本解析器按优先级检测属性，返回设备实际支持的属性名。
 * 扩展新设备时，只需在 candidates 列表中添加新的属性名即可。
 * 
 * @version 2026/07/15
 */
object LightPropertyResolver {
    
    /**
     * 灯光开关属性候选列表（按优先级排序）
     */
    private val LIGHT_SWITCH_CANDIDATES = listOf(
        "light_switch",              // 标准命名
        "spotlight_switch",          // T8214 等门铃设备
        "floodlight_manual_switch"   // T8124 等摄像头设备（P2P 控制，物模型只读）
    )
    
    /**
     * 亮度属性候选列表（按优先级排序）
     */
    private val BRIGHTNESS_CANDIDATES = listOf(
        "spotlight_brightness_level",    // T8124/T8214 实际有值
        "spotlight_brightness",          // 旧命名（可能为空对象）
        "floodlight_brightness_value"    // T8124 等摄像头设备(P2P)
    )
    
    private val COLOR_TEMP_CANDIDATES = listOf(
        "light_color_temperature",            // T8124/T8214 实际有值
        "spotlight_color_temperature",        // 旧命名（可能为空对象）
        "floodlight_color_temperature_value"  // T8124 等摄像头设备(P2P)
    )
    
    /**
     * 解析灯光开关属性
     * @return 设备支持的属性名，如果不支持返回 null
     */
    fun resolveLightSwitch(megaDevice: MegaDevice): String? {
        return resolve(megaDevice, LIGHT_SWITCH_CANDIDATES, "LightSwitch")
    }
    
    /**
     * 解析亮度属性
     * @return 设备支持的属性名，如果不支持返回 null
     */
    fun resolveBrightness(megaDevice: MegaDevice): String? {
        return resolve(megaDevice, BRIGHTNESS_CANDIDATES, "Brightness")
    }
    
    /**
     * 解析色温属性
     * @return 设备支持的属性名，如果不支持返回 null
     */
    fun resolveColorTemp(megaDevice: MegaDevice): String? {
        return resolve(megaDevice, COLOR_TEMP_CANDIDATES, "ColorTemp")
    }
    
    /**
     * 检查设备是否支持灯光开关
     */
    fun hasLightSwitch(megaDevice: MegaDevice): Boolean {
        return resolveLightSwitch(megaDevice) != null
    }
    
    /**
     * 检查设备是否支持亮度调节
     */
    fun hasBrightness(megaDevice: MegaDevice): Boolean {
        return resolveBrightness(megaDevice) != null
    }
    
    /**
     * 检查设备是否支持色温调节
     */
    fun hasColorTemp(megaDevice: MegaDevice): Boolean {
        return resolveColorTemp(megaDevice) != null
    }
    
    /**
     * 通用解析方法 - 按优先级检测属性
     * @param megaDevice 设备实例
     * @param candidates 候选属性名列表（按优先级排序）
     * @param tag 日志标签
     * @return 第一个存在的属性名，如果都不存在返回 null
     */
    private fun resolve(
        megaDevice: MegaDevice,
        candidates: List<String>,
        tag: String
    ): String? {
        for (candidate in candidates) {
            val hasInModel = megaDevice.hasProperty(candidate)
            val propertyExists = megaDevice.getProperty(candidate) != null
            appLogger.i("LightPropertyResolver/resolve: $tag candidate=$candidate, hasProperty=$hasInModel, getProperty=${propertyExists}")
        if (hasInModel) {
            val prop = megaDevice.getProperty(candidate)
            val specsMin = prop?.td?.dataType?.specs?.min
            val specsMax = prop?.td?.dataType?.specs?.max
            val specsValid = !specsMin.isNullOrBlank() && !specsMax.isNullOrBlank()
            appLogger.i("LightPropertyResolver/resolve: $tag resolved to '$candidate', specsMin=$specsMin, specsMax=$specsMax, specsValid=$specsValid")
            return candidate
        }
        }
        appLogger.w("LightPropertyResolver/resolve: $tag no matching property found")
        return null
    }
}
