package com.anker.esiotkit.sample.main.mega.device_settings

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anker.esiotkit.core.constants.ServiceConstants
import com.anker.esiotkit.core.entities.zone.ZonePoint
import com.anker.esiotkit.core.entities.zone.ZonePot
import com.anker.esiotkit.core.iotmodel.default_service.SpotlightSettingService
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.compat.ESIotCompatSwitch
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.domain_thing.api.domain.NightVisionMode
import com.anker.esiotkit.domain_thing.api.domain.VideoQuality
import com.anker.esiotkit.domain_thing.api.domain.VideoSettings
import com.anker.esiotkit.domain_thing.api.model.ThingCancellable
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.net_api.response.thing.TDDataType
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.main.mega.device_settings.video_settings.RectZone
import com.anker.esiotkit.sample.main.mega.device_settings.video_settings.parsePrivacyZones
import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.sample.support.createPayloadByPropertyType
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import kotlin.math.round

/**
 * 视频设置 ViewModel（迁移到 ESIotDeviceManager API）
 *
 * 迁移策略：
 * - 使用 ESIotDeviceManager 处理标准视频设置（quality, nightVision, watermark 等）
 * - 保留 MegaDevice 处理特殊功能（ActivityZone, PrivacyZone 等）
 *
 * @param megaDevice 设备实例（用于特殊功能）
 * @param deviceManager 设备管理器（用于标准功能）
 * @param parent 父级 ScreenModel
 */
class VideoSettingsViewModel(
    megaDevice: MegaDevice,
    private val deviceManager: ESIotDeviceManager,
    parent: BaseScreenModel
) : SubSettingsViewModel(megaDevice, parent) {

    private val deviceSn: String = megaDevice.deviceSn

    // ==================== 新 API：使用 ESIotDeviceManager ====================

    /**
     * 视频设置（使用新 API）
     */
    var videoSettings by mutableStateOf<VideoSettings?>(null)
        private set

    private var videoSubscription: ThingCancellable? = null

    init {
        // 订阅视频设置变化（新 API）
        videoSubscription = deviceManager.video(deviceSn).observe { settings ->
            videoSettings = settings
            appLogger.i("VideoSettings updated: quality=${settings.quality}, nightVision=${settings.nightVision}, watermark=${settings.watermark}, spotlight=${settings.spotlightEnabled}, statusLed=${settings.statusLedEnabled}, wdr=${settings.wdrEnabled}, hdr=${settings.hdrEnabled}")
        }

        // 初始加载视频设置
        launchWithLoading {
            try {
                videoSettings = deviceManager.video(deviceSn).get()
                appLogger.i("VideoSettings initial load: quality=${videoSettings?.quality}, nightVision=${videoSettings?.nightVision}, watermark=${videoSettings?.watermark}, watermarkState=${watermarkState}")
            } catch (e: Exception) {
                appLogger.e("Failed to load video settings: ${e.message}")
            }
        }
    }

    /**
     * 设置水印（新 API）
     * @param apiValue 发送给设备的协议值（T8414 等设备为 associatedValue，普通设备与 displayValue 一致）
     * @param displayValue 本地状态使用的展示值（物模型的 item.value），用于乐观更新 UI
     * @param label Toast 显示的文本标签（如 "Off", "Timestamp" 等），为 null 时显示数字
     */
    fun setWatermark(
        apiValue: Int,
        displayValue: Int = apiValue,
        label: String? = null
    ) {
        launchWithLoading {
            val isNewApi = ESIotCompatSwitch.isNewApiCompatEnabled()
            val result = if (isNewApi) {
                appLogger.i("setWatermark [NEW API]: apiValue=$apiValue displayValue=$displayValue")
                deviceManager.video(deviceSn).setWatermark(apiValue)
            } else {
                val isBoolWatermark = megaDevice.getProperty(ThingIdentifier.WATERMARK_SET)
                    ?.td?.dataType?.type == TDDataType.TYPE_BOOL
                if (isBoolWatermark) {
                    appLogger.i("setWatermark [OLD API/BOOL]: apiValue=$apiValue displayValue=$displayValue, mapped=${apiValue != 0}")
                    deviceManager.video(deviceSn).setWatermark(apiValue != 0)
                } else {
                    appLogger.i("setWatermark [OLD API/ENUM]: apiValue=$apiValue displayValue=$displayValue")
                    deviceManager.video(deviceSn).setWatermark(apiValue)
                }
            }
            result.onSuccess {
                    // 乐观更新用 displayValue，确保 UI 选中状态与展示值一致
                    videoSettings = videoSettings?.copy(watermark = displayValue)
                    emitToast(label ?: "Watermark set to $apiValue")
                    appLogger.i("Watermark set successfully: apiValue=$apiValue displayValue=$displayValue isNewApi=$isNewApi")
                }
                .onError { code, message ->
                    emitToast("Failed to set watermark: $message")
                    appLogger.e("Failed to set watermark: code=$code, message=$message")
                }
        }
    }

    /**
     * 设置视频质量（新 API）
     */
    fun setVideoQuality(quality: VideoQuality) {
        launchWithLoading {
            deviceManager.video(deviceSn).setQuality(quality)
                .onSuccess {
                    emitToast("Video quality set to ${quality.name}")
                    appLogger.i("Video quality set successfully: $quality")
                }
                .onError { code, message ->
                    emitToast("Failed to set video quality: $message")
                    appLogger.e("Failed to set video quality: code=$code, message=$message")
                }
        }
    }

    /**
     * 设置夜视模式（新 API）
     */
    fun setNightVision(mode: NightVisionMode) {
        launchWithLoading {
            deviceManager.video(deviceSn).setNightVision(mode)
                .onSuccess {
                    emitToast("Night vision set to ${mode.name}")
                    appLogger.i("Night vision set successfully: $mode")
                }
                .onError { code, message ->
                    emitToast("Failed to set night vision: $message")
                    appLogger.e("Failed to set night vision: code=$code, message=$message")
                }
        }
    }

    /**
     * 设置分辨率（新 API）
     */
    fun setResolution(resolution: String) {
        launchWithLoading {
            deviceManager.video(deviceSn).setResolution(resolution)
                .onSuccess {
                    emitToast("Resolution set to $resolution")
                    appLogger.i("Resolution set successfully: $resolution")
                }
                .onError { code, message ->
                    emitToast("Failed to set resolution: $message")
                    appLogger.e("Failed to set resolution: code=$code, message=$message")
                }
        }
    }

    /**
     * 设置画面翻转（新 API）
     */
    fun setFlipImage(enabled: Boolean) {
        launchWithLoading {
            deviceManager.video(deviceSn).setFlip(enabled)
                .onSuccess {
                    emitToast("Flip image ${if (enabled) "enabled" else "disabled"}")
                    appLogger.i("Flip image set successfully: $enabled")
                }
                .onError { code, message ->
                    emitToast("Failed to set flip image: $message")
                    appLogger.e("Failed to set flip image: code=$code, message=$message")
                }
        }
    }

    /**
     * 设置画面镜像（新 API）
     */
    fun setMirrorImage(enabled: Boolean) {
        launchWithLoading {
            deviceManager.video(deviceSn).setMirror(enabled)
                .onSuccess {
                    emitToast("Mirror image ${if (enabled) "enabled" else "disabled"}")
                    appLogger.i("Mirror image set successfully: $enabled")
                }
                .onError { code, message ->
                    emitToast("Failed to set mirror image: $message")
                    appLogger.e("Failed to set mirror image: code=$code, message=$message")
                }
        }
    }

    // ==================== 旧 API：保留用于特殊功能 ====================

    /**
     * 兼容旧 API：设置水印（使用 ThingPayload）
     * 保留此方法用于向后兼容，内部调用新 API
     */
    @Deprecated("Use setWatermark(Int) instead", ReplaceWith("setWatermark(payload.asInt())"))
    fun setWatermarkState(payload: ThingPayload) {
        setWatermark(payload.asInt())
    }

    /**
     * 兼容旧 API：设置直播质量
     * 直接使用 MegaDevice 设置，因为不同设备的分辨率标识可能不同
     * 根据属性数据类型自动选择正确的 payload 格式
     */
    fun setStreamingQualityState(value: String) {
        launchWithLoading {
            if (InfoCache.useNewApi) {
                // New API path
                appLogger.i("setStreamingQualityState [NEW API]: value=$value")
                deviceManager.video(deviceSn).setResolution(value)
                    .onSuccess {
                        // 乐观更新 _streamingQuality.payload，确保 UI 即时响应，不等 observe 异步回调
                        _streamingQuality.payload.value = ThingPayload.fromString(value)
                        emitToast("Streaming quality set successfully")
                    }
                    .onError { code, msg ->
                        appLogger.e("setStreamingQualityState [NEW API] failed: code=$code, msg=$msg")
                        emitToast("Failed to set streaming quality: $msg")
                    }
            } else {
                // Old API path
                val property = megaDevice.getProperty(ThingIdentifier.LIVE_STREAM_RESOLUTION)
                val payload = createPayloadByPropertyType(property, value)
                appLogger.i("setStreamingQualityState [OLD API]: value=$value, payload=${payload.asJson()}")
                _streamingQuality.set(payload)?.onSuccess {
                    emitToast("Streaming quality set successfully")
                }?.onFailure { code, msg ->
                    appLogger.e("setStreamingQualityState [OLD API] failed: code=$code, msg=$msg")
                    emitToast("Failed to set streaming quality: $msg")
                }
            }
        }
    }

    /**
     * 兼容旧 API：设置录制质量
     * TODO: 可以考虑添加到 StorageAPI 中
     * 根据属性数据类型自动选择正确的 payload 格式
     */
    fun setRecordingQualityState(value: String) {
        launchWithLoading {
            val property = megaDevice.getProperty(ThingIdentifier.RECORD_RESOLUTION)
            // 使用工具函数自动选择正确的 payload 格式
            val payload = createPayloadByPropertyType(property, value)
            appLogger.i("setRecordingQualityState: value=$value, dataType=${property?.td?.dataType?.type}, payload=${payload.asJson()}")
            _recordingQuality.set(payload)?.onSuccess {
                emitToast("Set succeed")
                appLogger.i("recordingQualityState: $value")
            }.onNotSuccess()
        }
    }

    /**
     * 兼容旧 API：设置显示流
     * TODO: 特殊功能，暂时保留使用 MegaDevice
     */
    fun setStreamingDisplayState(value: Int) {
        launchWithLoading {
            val payload = ThingPayload.fromInt(value)
            _streamingDisplay.set(payload)?.onSuccess {
                emitToast("Set succeed")
            }.onNotSuccess()
        }
    }

    /**
     * 设置夜视模式
     * 自动根据设备属性类型选择 bool/int payload
     */
    fun setNightVisionState(mode: Int) {
        launchWithLoading {
            val property = megaDevice.getProperty(nightVisionIdentifier)
            val isBoolType = property?.td?.dataType?.type == TDDataType.TYPE_BOOL
            val payload = if (isBoolType) ThingPayload.fromBoolean(mode != 0) else ThingPayload.fromInt(mode)
            if (InfoCache.useNewApi) {
                val nvMode = NightVisionMode.entries.firstOrNull { it.value == mode }
                if (nvMode != null) {
                    // 标准枚举值，走 VideoAPI
                    appLogger.i("setNightVisionState [NEW API]: identifier=$nightVisionIdentifier, mode=$mode → ${nvMode.name}")
                    deviceManager.video(deviceSn).setNightVision(nvMode)
                        .onSuccess {
                            // 乐观更新 _nightVision.payload，确保 UI 即时响应，不等 observe 异步回调
                            _nightVision.payload.value = payload
                            emitToast("Night vision: ${nvMode.name}")
                        }
                        .onError { code, msg ->
                            appLogger.e("setNightVisionState [NEW API] failed: code=$code, msg=$msg")
                            emitToast("Failed to set night vision: $msg")
                        }
                } else {
                    // 设备特定值（不在 NightVisionMode 枚举中），直接发送原始 Int 值
                    appLogger.i("setNightVisionState [NEW API RAW]: identifier=$nightVisionIdentifier, mode=$mode (not in enum, sending raw)")
                    property?.set(payload)?.onSuccess {
                        _nightVision.payload.value = payload
                        val enumList = property.td?.dataType?.specs?.enumList
                        val modeName = enumList?.firstOrNull { it.value.toIntOrNull() == mode }?.desc ?: "Mode $mode"
                        emitToast("Night vision: $modeName")
                    }?.onFailure { code, msg ->
                        appLogger.e("setNightVisionState [NEW API RAW] failed: code=$code, msg=$msg")
                        emitToast("Failed to set night vision: $msg")
                    }
                }
            } else {
                // Old API path
                appLogger.i("setNightVisionState [OLD API]: identifier=$nightVisionIdentifier, mode=$mode")
                _nightVision.set(payload)?.onSuccess {
                    val enumList = property?.td?.dataType?.specs?.enumList
                    val modeName = enumList?.firstOrNull { it.value.toIntOrNull() == mode }?.desc
                        ?: when (mode) { 0 -> "Off"; 1 -> "On"; else -> "Mode $mode" }
                    emitToast("Night vision: $modeName")
                }?.onFailure { code, msg ->
                    appLogger.e("setNightVisionState [OLD API] failed: code=$code, msg=$msg")
                    emitToast("Failed to set night vision: $msg")
                }
            }
        }
    }

    /**
     * 设置 HDR
     */
    fun setHDRState(value: Boolean) {
        launchWithLoading {
            _hdr.set(ThingPayload.fromBoolean(value))?.onSuccess {
                emitToast("HDR ${if (value) "enabled" else "disabled"}")
            }.onNotSuccess()
        }
    }

    // 活动区域配置（使用缓存的 ThingPropertyState，确保 flow 订阅不丢失）
    private val _activityZoneConfig = megaDevice.propertyState(ThingIdentifier.ACTIVITYZONE_CONFIG)

    /**
     * 设置活动区域配置（特殊功能，使用 MegaDevice）
     */
    fun setActivityZoneConfig(value: String) {
        launchWithLoading {
            appLogger.i("setActivityZoneConfig: value=$value, deviceSn=${megaDevice.deviceSn}")
            val property = megaDevice.getProperty(ThingIdentifier.ACTIVITYZONE_CONFIG)
            if (property == null) {
                appLogger.e("setActivityZoneConfig: property not found, deviceSn=${megaDevice.deviceSn}")
                emitToast("Activity zones not supported by this device")
                return@launchWithLoading
            }
            appLogger.i("setActivityZoneConfig: property found, TD type=${property.td?.dataType?.type}")
            
            // from(value) 会将 JSON 字符串解析为对象展开，而非 fromString 包进 payload-value 字符串
            _activityZoneConfig.set(ThingPayload.from(value))
                ?.onSuccess {
                    appLogger.i("setActivityZoneConfig: success, deviceSn=${megaDevice.deviceSn}")
                    emitToast("Activity zones saved successfully")
                }?.onFailure { code, msg ->
                    appLogger.e("setActivityZoneConfig failed: code=$code, msg=$msg, deviceSn=${megaDevice.deviceSn}")
                    emitToast("Failed to save activity zones (code: $code): $msg")
                } ?: run {
                    appLogger.e("setActivityZoneConfig: result is null, deviceSn=${megaDevice.deviceSn}")
                    emitToast("Failed to save activity zones: operation returned null")
                }
        }
    }

    /**
     * 隐私区域/隐私模式功能是否支持（RL-002：能力判断下沉到 SDK 层 isSupported()）
     * 检查链：ThingModel privacy_mode_enable → ThingModel set_privacy_zone → P2P PrivacyZoneService 注册
     */
    val isPrivacyZonesSupported: Boolean
        get() = deviceManager.privacyMask(deviceSn).isSupported()

    /**
     * 设置隐私区域配置（v2.4: 对齐 Mega，走 PrivacyMaskAPI.setZones，
     * 无会话缓存，ThingModel set 无条件执行，set 成功/失败后立即回读）
     */
    fun setPrivacyZoneConfig(value: String) {
        launchWithLoading {
            appLogger.i("setPrivacyZoneConfig: value=$value, deviceSn=${megaDevice.deviceSn}")
            val api = deviceManager.privacyMask(deviceSn)
            if (!api.isSupported()) {
                appLogger.e("setPrivacyZoneConfig: privacy zones not supported, deviceSn=${megaDevice.deviceSn}")
                emitToast("Privacy zones not supported by this device")
                return@launchWithLoading
            }
            val zones = parsePrivacyZones(value).map { rz ->
                ZonePot(points = listOf(
                    ZonePoint(x = rz.x1.toFloat(), y = rz.y1.toFloat()),
                    ZonePoint(x = rz.x2.toFloat(), y = rz.y2.toFloat()),
                ))
            }
            appLogger.i("setPrivacyZoneConfig: parsed zoneCount=${zones.size}, deviceSn=${megaDevice.deviceSn}")
            api.setZones(0, zones)
                .onSuccess {
                    appLogger.i("setPrivacyZoneConfig: success, triggering reload, deviceSn=${megaDevice.deviceSn}")
                    emitToast("Privacy zones saved successfully")
                    loadPrivacyZones { }
                }
                .onError { code, msg ->
                    appLogger.e("setPrivacyZoneConfig failed: code=$code, msg=$msg, triggering reload, deviceSn=${megaDevice.deviceSn}")
                    emitToast("Failed to save privacy zones (code: $code): $msg")
                    loadPrivacyZones { }
                }
        }
    }

    /**
     * 回读已保存的隐私区（v2.4: 对齐 Mega，只读 ThingModel 属性缓存，不降级 P2P param 1295）。
     * 缓存为空时返回 emptyList（和 Mega 一致，显示空不报错）。
     */
    fun loadPrivacyZones(onLoaded: (List<RectZone>) -> Unit) {
        launchWithLoading {
            appLogger.i("loadPrivacyZones: start, deviceSn=$deviceSn")
            deviceManager.privacyMask(deviceSn).getZones(0)
                .onSuccess { zones ->
                    appLogger.i("loadPrivacyZones: success, zoneCount=${zones.size}, deviceSn=$deviceSn")
                    onLoaded(zones.mapNotNull { it.toRectZone() })
                }
                .onError { code, msg ->
                    appLogger.e("loadPrivacyZones: failed, code=$code, msg=$msg, deviceSn=$deviceSn")
                }
        }
    }

    private fun ZonePot.toRectZone(): RectZone? {
        if (points.size < 2) return null
        return RectZone(
            x1 = permilleToDouble(points[0].x),
            y1 = permilleToDouble(points[0].y),
            x2 = permilleToDouble(points[1].x),
            y2 = permilleToDouble(points[1].y),
        )
    }

    // 千分比精度取整：消除 Float(0.248f)→Double 展开的 0.24799999892... 长尾小数，
    // 对齐 ActivityZoneScreen 既有修法（Bug #7758 精度问题）
    private fun permilleToDouble(v: Float): Double = round((v * 1000).toDouble()) / 1000.0

    val maxPrivacyZoneCount: Int
        get() = deviceManager.privacyMask(deviceSn).getMaxZoneCount()

    fun clearAllPrivacyZones() {
        launchWithLoading {
            appLogger.i("clearAllPrivacyZones: start, deviceSn=$deviceSn")
            deviceManager.privacyMask(deviceSn).clearZones(0)
                .onSuccess {
                    appLogger.i("clearAllPrivacyZones: success, deviceSn=$deviceSn")
                    emitToast("All privacy zones cleared")
                }
                .onError { code, msg ->
                    appLogger.e("clearAllPrivacyZones: failed, code=$code, msg=$msg, deviceSn=$deviceSn")
                    emitToast("Clear failed (code: $code): $msg")
                }
        }
    }

    /**
     * 设置连续录制（特殊功能，使用 MegaDevice）
     */
    fun setContinuousRecording(enabled: Boolean) {
        launchWithLoading {
            appLogger.i("setContinuousRecording: enabled=$enabled")
            val property = megaDevice.getProperty("continuous_recording")
            if (property == null) {
                appLogger.e("setContinuousRecording: property not found")
                emitToast("Continuous recording not supported by this device")
                return@launchWithLoading
            }
            megaDevice.propertyState("continuous_recording")
                .set(ThingPayload.fromBoolean(enabled))
                ?.onSuccess {
                    appLogger.i("setContinuousRecording: success")
                    emitToast("Continuous Recording ${if (enabled) "enabled" else "disabled"}")
                }?.onFailure { code, msg ->
                    appLogger.e("setContinuousRecording failed: code=$code, msg=$msg")
                    emitToast("Failed to set continuous recording (code: $code): $msg")
                } ?: run {
                appLogger.e("setContinuousRecording: result is null")
                emitToast("Failed to set continuous recording: operation returned null")
            }
        }
    }

    /**
     * 设置旋转180度（使用存储属性确保状态同步）
     * 自动根据物模型数据类型选择 bool/int payload
     * @param enabled 是否启用
     */
    fun setRotateImage180(enabled: Boolean) {
        launchWithLoading {
            val property = megaDevice.getProperty(_rotateImageIdentifier)
            // 根据物模型数据类型选择正确的 payload 格式（参考 setNightVisionState）
            val isBoolType = property?.td?.dataType?.type == TDDataType.TYPE_BOOL
            val payload = if (isBoolType) {
                ThingPayload.fromBoolean(enabled)
            } else {
                ThingPayload.fromInt(if (enabled) 1 else 0)
            }
            appLogger.i("setRotateImage180: enabled=$enabled, identifier=$_rotateImageIdentifier, isBoolType=$isBoolType, payload=${payload.asJson()}")
            // 使用 _rotateImage.set() 确保状态同步（ThingPropertyState 会在成功后更新 payload）
            _rotateImage.set(payload)
                ?.onSuccess {
                    appLogger.i("setRotateImage180: success")
                    emitToast("Rotate Image 180° ${if (enabled) "enabled" else "disabled"}")
                }?.onFailure { code, msg ->
                    appLogger.e("setRotateImage180 failed: code=$code, msg=$msg")
                    emitToast("Failed to set rotate image (code: $code): $msg")
                } ?: run {
                appLogger.e("setRotateImage180: result is null")
                emitToast("Failed to set rotate image: operation returned null")
            }
        }
    }

    /**
     * 设置隐私模式（特殊功能，使用 MegaDevice）
     */
    fun setPrivacyModeEnable(value: Boolean) {
        launchWithLoading {
            appLogger.i("setPrivacyModeEnable: value=$value, deviceSn=${megaDevice.deviceSn}")
            val property = megaDevice.getProperty("privacy_mode_enable")
            if (property == null) {
                appLogger.e("setPrivacyModeEnable: property not found, deviceSn=${megaDevice.deviceSn}, hasProperty=${megaDevice.hasProperty("privacy_mode_enable")}")
                emitToast("Privacy mode not supported by this device")
                return@launchWithLoading
            }
            appLogger.i("setPrivacyModeEnable: property found, tdType=${property.td?.dataType?.type}, deviceSn=${megaDevice.deviceSn}")
            _privacyMode.set(ThingPayload.fromBoolean(value))
                ?.onSuccess {
                    appLogger.i("setPrivacyModeEnable: success, value=$value, deviceSn=${megaDevice.deviceSn}")
                    emitToast("Privacy mode ${if (value) "enabled" else "disabled"}")
                }?.onFailure { code, msg ->
                    appLogger.e("setPrivacyModeEnable failed: code=$code, msg=$msg, deviceSn=${megaDevice.deviceSn}")
                    emitToast("Failed to set privacy mode (code: $code): $msg")
                } ?: run {
                appLogger.e("setPrivacyModeEnable: result is null, deviceSn=${megaDevice.deviceSn}")
                emitToast("Failed to set privacy mode: operation returned null")
            }
        }
    }

    /**
     * 设置灯光开关
     * T8124 等设备通过 P2P Service 控制，T8214/T8171 等设备降级使用物模型属性写入
     */
    fun setLightSwitch(enabled: Boolean) {
        launchWithLoading {
            appLogger.i("VideoSettingsVM/setLightSwitch: start, target=$enabled, currentState=$lightSwitchState")
            // 物模型 spotlight_switch 是只读属性，必须通过 P2P SpotlightSettingService 控制
            val service = megaDevice.iotDevice.getService<SpotlightSettingService>(ServiceConstants.SPOTLIGHT_SETTING)
            if (service != null) {
                appLogger.i("VideoSettingsVM/setLightSwitch: calling P2P setLightSwitch($enabled), deviceSn=${megaDevice.iotDevice.deviceSn}")
                service.setLightSwitch(
                    switch = enabled,
                    onSuccess = {
                        _lightSwitchState.value = enabled
                        appLogger.i("VideoSettingsVM/setLightSwitch: SUCCESS via P2P setLightSwitch, state=$enabled")
                        emitToast("Spotlight ${if (enabled) "turned on" else "turned off"}")
                    },
                    onFailure = { code, msg ->
                        appLogger.e("VideoSettingsVM/setLightSwitch: FAIL via P2P setLightSwitch code=$code msg=$msg")
                        emitToast("Failed to set spotlight (code: $code)")
                    }
                )
                return@launchWithLoading
            }
            // 降级：P2P Service 不存在，使用物模型属性写入（与 Live 页面一致）
            appLogger.i("VideoSettingsVM/setLightSwitch: P2P Service not found, fallback to property write")
            val property = megaDevice.getProperty(ThingIdentifier.SPOTLIGHT_SWITCH)
            if (property == null) {
                appLogger.e("VideoSettingsVM/setLightSwitch: SPOTLIGHT_SWITCH property not found")
                emitToast("Spotlight not supported by this device")
                return@launchWithLoading
            }
            property.set(ThingPayload.fromBoolean(enabled))
                .onSuccess {
                    _lightSwitchState.value = enabled
                    appLogger.i("VideoSettingsVM/setLightSwitch: SUCCESS via property write, state=$enabled")
                    emitToast("Spotlight ${if (enabled) "turned on" else "turned off"}")
                }
                .onFailure { code, _ ->
                    appLogger.e("VideoSettingsVM/setLightSwitch: FAIL via property write, code=$code")
                    emitToast("Failed to set spotlight: $code")
                }
        }
    }

    /**
     * 设置灯光运动检测开关（特殊功能，使用 MegaDevice）
     */
    fun setLightMotionDetection(enabled: Boolean) {
        launchWithLoading {
            appLogger.i("setLightMotionDetection: enabled=$enabled")
            val property = megaDevice.getProperty("light_ctrl_pir_switch")
            if (property == null) {
                appLogger.e("setLightMotionDetection: property not found")
                emitToast("Light motion detection not supported by this device")
                return@launchWithLoading
            }
            megaDevice.propertyState("light_ctrl_pir_switch")
                .set(ThingPayload.fromBoolean(enabled))
                ?.onSuccess {
                    appLogger.i("setLightMotionDetection: success")
                    emitToast("Light motion detection ${if (enabled) "enabled" else "disabled"}")
                }?.onFailure { code, msg ->
                    appLogger.e("setLightMotionDetection failed: code=$code, msg=$msg")
                    emitToast("Failed to set light motion detection (code: $code): $msg")
                } ?: run {
                appLogger.e("setLightMotionDetection: result is null")
                emitToast("Failed to set light motion detection: operation returned null")
            }
        }
    }

    /**
     * 设置灯光报警开关（特殊功能，使用 MegaDevice）
     */
    fun setLightAlarm(enabled: Boolean) {
        launchWithLoading {
            appLogger.i("setLightAlarm: enabled=$enabled")
            val property = megaDevice.getProperty("light_alarm_switch")
            if (property == null) {
                appLogger.e("setLightAlarm: property not found")
                emitToast("Light alarm not supported by this device")
                return@launchWithLoading
            }
            megaDevice.propertyState("light_alarm_switch")
                .set(ThingPayload.fromBoolean(enabled))
                ?.onSuccess {
                    appLogger.i("setLightAlarm: success")
                    emitToast("Light alarm ${if (enabled) "enabled" else "disabled"}")
                }?.onFailure { code, msg ->
                    appLogger.e("setLightAlarm failed: code=$code, msg=$msg")
                    emitToast("Failed to set light alarm (code: $code): $msg")
                } ?: run {
                appLogger.e("setLightAlarm: result is null")
                emitToast("Failed to set light alarm: operation returned null")
            }
        }
    }

    // ==================== 兼容属性：用于 UI 绑定 ====================
    // 注意：这些属性使用存储的 propertyState，确保状态同步

    /**
     * 水印状态（兼容属性）：返回枚举值 0=Off, 1=Timestamp, 2=Timestamp and logo, 3=Logo
     */
    val watermarkState: Int
        get() = videoSettings?.watermark ?: 0

    /**
     * 直播质量 - 存储属性，确保状态同步
     */
    private val _streamingQuality = megaDevice.propertyState(ThingIdentifier.LIVE_STREAM_RESOLUTION)
    val streamingQualityState: String by _streamingQuality.asStringState()

    /**
     * 录制质量 - 存储属性，确保状态同步
     */
    private val _recordingQuality = megaDevice.propertyState(ThingIdentifier.RECORD_RESOLUTION)
    val recordingQualityState: String by _recordingQuality.asStringState()

    /**
     * 夜视模式标识符：优先使用 nightvision_type_new（新枚举），回退到 nightvision_type（旧枚举）
     */
    val nightVisionIdentifier: String =
        if (megaDevice.hasProperty("nightvision_type_new")) "nightvision_type_new"
        else ThingIdentifier.NIGHTVISION_TYPE

    /**
     * 夜视模式 - 存储属性，确保状态同步
     *
     * 注意：部分设备的 NIGHTVISION_TYPE 为布尔类型（true=On/false=Off），asIntState() 对布尔原生值
     * 经 getString 路径读取会得到 0，改用 asBooleanState() 再转 Int 以兼容布尔和整型两种设备。
     */
    private val _nightVision = megaDevice.propertyState(nightVisionIdentifier)
    val nightVisionMode: Int by derivedStateOf {
        val isBool = megaDevice.getProperty(nightVisionIdentifier)
            ?.td?.dataType?.type == TDDataType.TYPE_BOOL
        if (isBool) {
            if (_nightVision.asBooleanState().value) 1 else 0
        } else {
            _nightVision.asIntState().value
        }
    }

    /**
     * HDR - 存储属性，确保状态同步
     */
    private val _hdr = megaDevice.propertyState(ThingIdentifier.VIDEO_QUALITY_HDR_SWITCH)
    val hdrState: Boolean by _hdr.asBooleanState()

    /**
     * 流显示状态 - 存储属性，确保状态同步
     */
    private val _streamingDisplay = megaDevice.propertyState(ThingIdentifier.VIDEO_QUALITY_STREAMING_SMART_DISPLAYS)
    val streamingDisplayState: Int by _streamingDisplay.asIntState()

    /**
     * 灯光开关 - 优先从 P2P Service 读取，降级从物模型属性读取
     * 初始化从 P2P getLightSwitch() 读取，setLightSwitch 成功后乐观更新
     */
    private val _lightSwitchState = mutableStateOf(
        run {
            val p2pService = megaDevice.iotDevice.getService<SpotlightSettingService>(ServiceConstants.SPOTLIGHT_SETTING)
            val p2pValue = p2pService?.getLightSwitch()
            val thingValue = megaDevice.getProperty(ThingIdentifier.SPOTLIGHT_SWITCH)?.get()?.asBoolean()
            val finalValue = p2pValue ?: thingValue ?: false
            appLogger.i("VideoSettingsVM/init: lightSwitchState source tracking, p2pServiceFound=${p2pService != null}, p2pValue=$p2pValue, thingModelProperty=$thingValue, finalState=$finalValue, deviceSn=${megaDevice.iotDevice.deviceSn}")
            finalValue
        }
    )
    val lightSwitchState: Boolean by _lightSwitchState

    init {
      appLogger.i("VideoSettingsVM/init: lightSwitchState=${lightSwitchState}, deviceSn=${megaDevice.iotDevice.deviceSn}")
    }

    /**
     * 隐私模式 - 存储属性，确保状态同步
     */
    private val _privacyMode = megaDevice.propertyState("privacy_mode_enable")
    val privacyModeEnable: Int by _privacyMode.asIntState()

    /**
     * 旋转180度 - 存储属性，确保状态同步
     * 优先使用新标识符 rotate_image_180_on_off，回退到旧标识符 video_flip_180
     *
     * 注意：asIntState() 对布尔类型属性存在兼容问题（Boolean 原生值经 getString 路径读取会得到 0），
     * 改用 asBooleanState() 再转 Int，确保布尔类型和整型设备均能正确读取。
     */
    private val _rotateImageIdentifier: String =
        if (megaDevice.hasProperty("rotate_image_180_on_off")) "rotate_image_180_on_off"
        else "video_flip_180"
    private val _rotateImage = megaDevice.propertyState(_rotateImageIdentifier)
    val rotateImageState: Int by derivedStateOf { if (_rotateImage.asBooleanState().value) 1 else 0 }

    val isRotateImageSupported: Boolean
        get() {
            val supported = deviceManager.video(deviceSn).isRotateImageSupported()
            appLogger.i("VideoSettingsVM/isRotateImageSupported: supported=$supported, identifier=$_rotateImageIdentifier, deviceSn=$deviceSn")
            return supported
        }

    // ==================== 生命周期 ====================

    /**
     * 清理资源
     */
    fun onCleared() {
        videoSubscription?.cancel()
        appLogger.i("VideoSettingsViewModel cleared")
    }
}
