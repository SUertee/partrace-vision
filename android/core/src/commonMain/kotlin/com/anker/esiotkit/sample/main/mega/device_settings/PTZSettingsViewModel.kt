package com.anker.esiotkit.sample.main.mega.device_settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.domain_thing.api.model.ThingResult
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.main.mega.device_settings.ptz_settings.PresetPositionManager
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PTZSettingsViewModel(
  megaDevice: MegaDevice,
  parent: BaseScreenModel,
  private val deviceManager: ESIotDeviceManager
) : SubSettingsViewModel(megaDevice, parent) {

  private val liveStreamAPI = deviceManager.liveStream(megaDevice.deviceSn)

  private val _ptzSpeed = megaDevice.propertyState(ThingIdentifier.PTZ_TURN_SPEED)
  val ptzSpeed by _ptzSpeed.asIntState()

  private val _ptzSpeedOverride = mutableStateOf<Int?>(null)

  private val _ptzSpeedFromSdk = mutableStateOf(3)
  val displayPTZSpeed: Int
    get() = _ptzSpeedOverride.value ?: _ptzSpeedFromSdk.value

  /**
   * Bug #10678: 页面进入时通过 SDK API 读取云台转速（参数缓存 6015 优先，对齐 mega
   * IcPanTiltActivity.java:624 upDateView 的读取时机）。替代直接拼装 iotDevice 参数缓存
   * + ThingModel 属性的混合读取 —— 读取策略由 SDK 统一维护。
   */
  fun refreshPTZSpeed() {
    launchWithLoading {
      appLogger.i("PTZSettingsViewModel/refreshPTZSpeed/start: deviceSn=${megaDevice.deviceSn}")
      when (val result = liveStreamAPI.getPTZSpeed()) {
        is ThingResult.Success -> {
          _ptzSpeedFromSdk.value = result.data
          appLogger.i("PTZSettingsViewModel/refreshPTZSpeed/success: speed=${result.data}, deviceSn=${megaDevice.deviceSn}")
        }
        is ThingResult.Error -> {
          _ptzSpeedFromSdk.value = 3
          appLogger.e("PTZSettingsViewModel/refreshPTZSpeed/failure: code=${result.code}, msg=${result.message}, deviceSn=${megaDevice.deviceSn}")
        }
      }
    }
  }

  private val _autoCruise = megaDevice.propertyState(ThingIdentifier.VIDEO_AUTO_CRUISE_SWITCH)
  val autoCruise by _autoCruise.asBooleanState()

  private val _cruiseMode = megaDevice.propertyState("cruise_mode")
  val cruiseMode by _cruiseMode.asStringState()

  private val _aiTrackingState = mutableStateOf(
    megaDevice.getProperty(ThingIdentifier.AI_TRACKING_STATUS)?.get()?.asBoolean() ?: false
  )
  val aiTracking: Boolean get() = _aiTrackingState.value

  fun setPTZSpeed(value: Int) {
    launchWithLoading {
      appLogger.i("setPTZSpeed: value=$value")
      _ptzSpeedOverride.value = value
      val result = liveStreamAPI.setPTZSpeed(value)
      when (result) {
        is ThingResult.Success -> {
          appLogger.i("setPTZSpeed: success")
          emitToast("PTZ speed set to $value")
        }
        is ThingResult.Error -> {
          _ptzSpeedOverride.value = null
          appLogger.e("setPTZSpeed failed: code=${result.code}, msg=${result.message}")
          emitToast("Failed to set PTZ speed: ${result.message}")
        }
      }
    }
  }

  fun setAutoCruise(value: Boolean) {
    launchWithLoading {
      appLogger.i("setAutoCruise: value=$value")
      val property = megaDevice.getProperty(ThingIdentifier.VIDEO_AUTO_CRUISE_SWITCH)
      if (property == null) {
        appLogger.e("setAutoCruise: property not found")
        emitToast("Auto-cruise not supported by this device")
        return@launchWithLoading
      }
      _autoCruise.set(ThingPayload.fromBoolean(value))
        ?.onSuccess {
          appLogger.i("setAutoCruise: success")
          emitToast("Auto-cruise ${if (value) "enabled" else "disabled"}")
        }?.onFailure { code, msg ->
          appLogger.e("setAutoCruise failed: code=$code, msg=$msg")
          emitToast("Failed to set auto-cruise: $msg")
        }
    }
  }

  fun setCruiseMode(value: String) {
    launchWithLoading {
      appLogger.i("setCruiseMode: value=$value")
      val property = megaDevice.getProperty("cruise_mode")
      if (property == null) {
        appLogger.e("setCruiseMode: property not found")
        emitToast("Cruise mode not supported by this device")
        return@launchWithLoading
      }
      _cruiseMode.set(ThingPayload.fromString(value))
        ?.onSuccess {
          appLogger.i("setCruiseMode: success")
          emitToast("Cruise mode set to $value")
        }?.onFailure { code, msg ->
          appLogger.e("setCruiseMode failed: code=$code, msg=$msg")
          emitToast("Failed to set cruise mode: $msg")
        }
    }
  }

  fun toggleAITracking(value: Boolean) {
    launchWithLoading {
      appLogger.i("toggleAITracking: value=$value")
      val result = liveStreamAPI.toggleAITracking(value)
      when (result) {
        is ThingResult.Success -> {
          _aiTrackingState.value = value
          appLogger.i("toggleAITracking: success")
          emitToast("AI Tracking ${if (value) "enabled" else "disabled"}")
        }
        is ThingResult.Error -> {
          appLogger.e("toggleAITracking failed: code=${result.code}, msg=${result.message}")
          emitToast("Failed to toggle AI tracking: ${result.message}")
        }
      }
    }
  }

  fun controlPTZ(direction: String) {
    launchWithLoading {
      appLogger.i("controlPTZ: direction=$direction")
      val ptzDirection = parseDirection(direction) ?: return@launchWithLoading
      val result = liveStreamAPI.controlPTZ(ptzDirection)
      when (result) {
        is ThingResult.Success -> appLogger.d("controlPTZ: success")
        is ThingResult.Error -> {
          appLogger.e("controlPTZ failed: code=${result.code}, msg=${result.message}")
          emitToast("PTZ control failed: ${result.message}")
        }
      }
    }
  }

  fun startPTZMovement(direction: String) {
    launchWithLoading {
      appLogger.i("startPTZMovement: direction=$direction")
      val ptzDirection = parseDirection(direction) ?: return@launchWithLoading
      val result = liveStreamAPI.startPTZMovement(ptzDirection)
      when (result) {
        is ThingResult.Success -> appLogger.d("startPTZMovement: success")
        is ThingResult.Error -> {
          appLogger.e("startPTZMovement failed: code=${result.code}, msg=${result.message}")
          emitToast("PTZ movement failed: ${result.message}")
        }
      }
    }
  }

  fun stopPTZMovement(direction: String) {
    launchWithLoading {
      appLogger.i("stopPTZMovement: direction=$direction")
      val ptzDirection = parseDirection(direction) ?: return@launchWithLoading
      val result = liveStreamAPI.stopPTZMovement(ptzDirection)
      when (result) {
        is ThingResult.Success -> appLogger.d("stopPTZMovement: success")
        is ThingResult.Error -> {
          appLogger.e("stopPTZMovement failed: code=${result.code}, msg=${result.message}")
        }
      }
    }
  }

  fun calibratePTZ() {
    launchWithLoading {
      appLogger.i("calibratePTZ: starting")
      val result = liveStreamAPI.calibrate()
      when (result) {
        is ThingResult.Success -> {
          emitToast("PTZ calibration started")
          appLogger.i("calibratePTZ: success")
        }
        is ThingResult.Error -> {
          appLogger.e("calibratePTZ failed: code=${result.code}, msg=${result.message}")
          emitToast("Calibration failed: ${result.message}")
        }
      }
    }
  }

  private fun parseDirection(direction: String): com.anker.esiotkit.domain_thing.api.domain.PTZDirection? =
    when (direction.uppercase()) {
      "LEFT" -> com.anker.esiotkit.domain_thing.api.domain.PTZDirection.LEFT
      "RIGHT" -> com.anker.esiotkit.domain_thing.api.domain.PTZDirection.RIGHT
      "UP" -> com.anker.esiotkit.domain_thing.api.domain.PTZDirection.UP
      "DOWN" -> com.anker.esiotkit.domain_thing.api.domain.PTZDirection.DOWN
      else -> {
        appLogger.w("parseDirection: invalid direction=$direction")
        null
      }
    }

  fun savePreset(name: String, hAngle: Int, vAngle: Int) {
    savePresetWithCallback(name, null)
  }

  /**
   * Save preset with callback for success notification
   * @param presetIndex 1-based preset index (1-4)
   * @param onSuccess callback invoked when preset is saved successfully
   */
  fun savePresetWithCallback(presetIndex: String, onSuccess: (() -> Unit)?) {
    launchWithLoading {
      appLogger.i("savePreset: index=$presetIndex, deviceSn=${megaDevice.deviceSn}")
      val index = presetIndex.toIntOrNull() ?: 1

      // 检查 action 是否存在
      val action = megaDevice.getAction(ThingIdentifier.SET_PRESET_POSITIONS)
      if (action == null) {
        appLogger.e("savePreset: action not found, deviceSn=${megaDevice.deviceSn}")
        emitToast("Preset positions not supported by this device")
        return@launchWithLoading
      }
      appLogger.i("savePreset: action found, deviceSn=${megaDevice.deviceSn}")

      val result = liveStreamAPI.savePreset(index)
      when (result) {
        is ThingResult.Success -> {
          emitToast("Preset saved successfully")
          appLogger.i("savePreset: success, deviceSn=${megaDevice.deviceSn}")
          onSuccess?.invoke()
        }
        is ThingResult.Error -> {
          appLogger.e("savePreset failed: code=${result.code}, msg=${result.message}, deviceSn=${megaDevice.deviceSn}")
          emitToast("Failed to save preset: ${result.message}")
        }
      }
    }
  }

  fun deletePreset(presetId: String) {
    launchWithLoading {
      appLogger.i("deletePreset: id=$presetId")
      val index = presetId.toIntOrNull() ?: return@launchWithLoading

      val result = liveStreamAPI.deletePreset(index)
      when (result) {
        is ThingResult.Success -> {
          emitToast("Preset deleted")
          appLogger.i("deletePreset: success")
        }
        is ThingResult.Error -> {
          appLogger.e("deletePreset failed: code=${result.code}, msg=${result.message}")
          emitToast("Failed to delete preset: ${result.message}")
        }
      }
    }
  }

  fun gotoPreset(presetId: String) {
    gotoPresetWithCallback(presetId, null)
  }

  /**
   * Jump to preset position with callback
   * @param presetId 1-based preset index (1-4)
   * @param onSuccess callback invoked when camera starts moving to preset
   */
  fun gotoPresetWithCallback(presetId: String, onSuccess: (() -> Unit)?) {
    launchWithLoading {
      appLogger.i("PTZSettingsViewModel/gotoPreset/start: id=$presetId, deviceSn=${megaDevice.deviceSn}")
      val index = presetId.toIntOrNull() ?: return@launchWithLoading

      val result = liveStreamAPI.jumpToPreset(index)
      when (result) {
        is ThingResult.Success -> {
          emitToast("Moving to preset position")
          appLogger.i("PTZSettingsViewModel/gotoPreset/success: id=$presetId, deviceSn=${megaDevice.deviceSn}")
          onSuccess?.invoke()
        }
        is ThingResult.Error -> {
          appLogger.e("PTZSettingsViewModel/gotoPreset/failure: id=$presetId, code=${result.code}, msg=${result.message}, deviceSn=${megaDevice.deviceSn}")
          emitToast("Failed to goto preset: ${result.message}")
        }
      }
    }
  }

  /**
   * Delete preset with callback
   * @param presetId 1-based preset index (1-4)
   * @param onSuccess callback invoked when preset is deleted successfully
   */
  fun deletePresetWithCallback(presetId: String, onSuccess: (() -> Unit)?) {
    launchWithLoading {
      appLogger.i("deletePreset: id=$presetId")
      val index = presetId.toIntOrNull() ?: return@launchWithLoading

      val result = liveStreamAPI.deletePreset(index)
      when (result) {
        is ThingResult.Success -> {
          emitToast("Preset deleted")
          appLogger.i("deletePreset: success")
          onSuccess?.invoke()
        }
        is ThingResult.Error -> {
          appLogger.e("deletePreset failed: code=${result.code}, msg=${result.message}")
          emitToast("Failed to delete preset: ${result.message}")
        }
      }
    }
  }

  /**
   * Query preset positions from device
   * Updates PresetPositionManager with the device data
   * @param onComplete callback with list of set preset indices (1-based)
   */
  fun queryPresetPositions(onComplete: ((List<Int>) -> Unit)? = null) {
    launchWithLoading {
      appLogger.i("queryPresetPositions: starting query")
      val action = megaDevice.getAction(ThingIdentifier.QUERY_PRESET_POSITIONS)
      if (action == null) {
        appLogger.e("queryPresetPositions: action not found")
        onComplete?.invoke(emptyList())
        return@launchWithLoading
      }
      action.invoke(ThingPayload.from(mapOf("payload-value" to emptyMap<String, Any>())))?.await()
        ?.onSuccess { payload ->
          val jsonString = payload.asJson()
          appLogger.i("queryPresetPositions: response=$jsonString")
          val setPresets = parsePresetPositions(jsonString)
          appLogger.i("queryPresetPositions: parsed presets=$setPresets")
          val deviceSn = megaDevice.deviceSn
          PresetPositionManager.initDevice(deviceSn)
          for (i in 0..3) {
            PresetPositionManager.setPresetStatus(deviceSn, i, false)
          }
          setPresets.forEach { index ->
            PresetPositionManager.setPresetStatus(deviceSn, index, true)
          }
          onComplete?.invoke(setPresets)
        }?.onFailure { code, msg ->
          appLogger.e("queryPresetPositions failed: code=$code, msg=$msg")
          onComplete?.invoke(emptyList())
        }
    }
  }

  /**
   * Parse preset positions from JSON response
   * 从设备响应中提取已设置的预置位索引列表（0-based，与设备协议一致）
   *
   * 设备实际返回格式（T8170 日志确认）：
   *   {"p2p":{"mapOf":{"data":[{"index":0,"enable":0,...},{"index":1,"enable":1,...},...]}}}
   * index 字段为 0-based，enable=1 表示已设置。
   */
  private fun parsePresetPositions(jsonString: String): List<Int> {
    appLogger.i("parsePresetPositions: input=$jsonString")

    if (jsonString.isBlank() || jsonString == "null" || jsonString == "{}") {
      appLogger.i("parsePresetPositions: empty response")
      return emptyList()
    }

    return try {
      val json = Json { ignoreUnknownKeys = true }
      val root = json.parseToJsonElement(jsonString)

      // 递归查找任意深度的 "data" 数组（设备格式：嵌套在 p2p.mapOf.data）
      fun findDataArray(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonArray? {
        if (element !is kotlinx.serialization.json.JsonObject) return null
        element.jsonObject["data"]?.let { v ->
          if (v is kotlinx.serialization.json.JsonArray) return v
        }
        for (child in element.jsonObject.values) {
          findDataArray(child)?.let { return it }
        }
        return null
      }

      // 优先命中设备真实格式，返回 0-based 索引
      val dataArray = findDataArray(root)
      if (dataArray != null) {
        val result = dataArray.mapNotNull { item ->
          val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
          val index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
          val enable = obj["enable"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
          if (enable == 1) index else null
        }
        appLogger.i("parsePresetPositions: parsed device format (0-based): $result")
        return result
      }

      // Fallback：兼容其他格式（直接索引数组）
      when {
        root is kotlinx.serialization.json.JsonArray -> {
          val result = root.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
          appLogger.i("parsePresetPositions: fallback parsed as root array: $result")
          result
        }
        else -> {
          appLogger.w("parsePresetPositions: no matching format found")
          emptyList()
        }
      }
    } catch (e: Exception) {
      appLogger.e("parsePresetPositions: failed to parse: $jsonString, error: ${e.message}")
      emptyList()
    }
  }
}
