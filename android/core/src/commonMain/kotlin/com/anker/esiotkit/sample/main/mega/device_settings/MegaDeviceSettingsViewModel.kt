package com.anker.esiotkit.sample.main.mega.device_settings

import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.RawThingResponse
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.support.ThingPropertyState
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger

class MegaDeviceSettingsViewModel constructor(
  val megaDevice: MegaDevice,
  internal val deviceManager: ESIotDeviceManager
) : BaseScreenModel() {
  val thingDescription = megaDevice.getThingDescription()
  val properties = thingDescription?.propMap.orEmpty()
  val actions = thingDescription?.actionMap.orEmpty()
  val events = thingDescription?.eventMap.orEmpty()

  // 使用 screenModelScope 确保 flow 订阅会在 ViewModel 销毁时自动取消
  private fun MegaDevice.propertyState(identifier: String) = ThingPropertyState(this, identifier, screenModelScope)

  // Main screen state
  private val _cameraOnOffState = megaDevice.propertyState(ThingIdentifier.CAMERA_ON_OFF)
  val cameraOnOffState by _cameraOnOffState.asBooleanState()

  // Power Manager (used by PowerManagerScreen)
  private val _powerManagerMode = megaDevice.propertyState(ThingIdentifier.POWER_MANAGER_MODE)
  private val _videoClipLength = megaDevice.propertyState(ThingIdentifier.VIDEO_CLIP_LENGTH)
  private val _triggerIntervalTime = megaDevice.propertyState(ThingIdentifier.TRIGGER_INTERVAL_TIME)
  private val _endEarly = megaDevice.propertyState(ThingIdentifier.MOTION_STOP_END_EARLY)
  private val _powerChargeMode = megaDevice.propertyState(ThingIdentifier.POWER_CHARGE_MODE)

  // Sub-ViewModels
  val videoSettings by lazy { VideoSettingsViewModel(megaDevice, deviceManager, this) }
  val audioSettings by lazy { AudioSettingsViewModel(megaDevice, deviceManager, this) }
  val notificationSettings by lazy { NotificationSettingsViewModel(megaDevice, deviceManager, this) }
  val ptzSettings by lazy { PTZSettingsViewModel(megaDevice, this, deviceManager) }
  val generalSettings by lazy { DeviceGeneralViewModel(megaDevice, deviceManager, this) }

  // Cross-cutting accessors for screens that stay with main ViewModel
  val deviceName get() = generalSettings.deviceName

  init {
    logDeviceProperties()
    generalSettings.initialize(screenModelScope)
  }

  private fun logDeviceProperties() {
    appLogger.i("========== Device Settings Properties ==========")
    appLogger.i("Device: ${megaDevice.deviceSn} (${megaDevice.devicePn})")
    appLogger.i("Total properties: ${properties.size}")
    appLogger.i("===============================================")
  }

  fun setCameraOnOffState(value: Boolean) {
    launchWithLoading {
      _cameraOnOffState.set(ThingPayload.fromString(if (value) "1" else "0"))?.onSuccess {
        emitToast("Set succeed")
      }.onNotSuccess()
    }
  }

  fun setPowerManagerMode(value: String) {
    launchWithLoading {
      val payload = value.toIntOrNull()?.let { ThingPayload.from(it) } ?: ThingPayload.from(value)
      _powerManagerMode.set(payload)?.onSuccess {
        emitToast("Set succeed")
      }.onNotSuccess()
    }
  }

  fun setVideoClipLength(value: Int) {
    launchWithLoading {
      _videoClipLength.set(ThingPayload.from(value))?.onSuccess {
        emitToast("Set succeed")
      }.onNotSuccess()
    }
  }

  fun setTriggerIntervalTime(value: Int) {
    launchWithLoading {
      _triggerIntervalTime.set(ThingPayload.from(value))?.onSuccess {
        emitToast("Set succeed")
      }.onNotSuccess()
    }
  }

  fun setEndEarly(value: Boolean) {
    launchWithLoading {
      _endEarly.set(ThingPayload.from(value))?.onSuccess {
        emitToast("Set succeed")
      }.onNotSuccess()
    }
  }

  fun setPowerChargeMode(value: Int) {
    launchWithLoading {
      _powerChargeMode.set(ThingPayload.from(value))?.onSuccess {
        emitToast("Set succeed")
      }.onNotSuccess()
    }
  }

  fun restartDevice() {
    launchWithLoading {
      megaDevice.getAction(ThingIdentifier.RESTART_DEVICE)?.invoke(ThingPayload.create())?.await()
        ?.onSuccess {
          emitToast(" The device is restarting... \nPlease wait for a few minutes.")
          emitToast("Set succeed")
        }.onNotSuccess()
    }
  }

  fun removeDevice(onSuccess: () -> Unit) {
    launchWithLoading {
      megaDevice.getAction(ThingIdentifier.UNBIND_DEVICE)?.invoke(ThingPayload.create())?.await()?.onSuccess {
        onSuccess()
        emitToast("Set succeed")
      }.onNotSuccess()
    }
  }

  private fun RawThingResponse?.onNotSuccess() = apply {
    this?.onFailure { code, payload ->
      emitToast("Set failure $code, $payload")
      appLogger.e("Failed to set invoke or set: $code, $payload")
    } ?: kotlin.run {
      emitToast("Cannot find the Thing")
    }
  }

  // ========== New API Methods (using ESIotDeviceManager) ==========

  /**
   * 重启设备（使用新 API）
   */
  fun restartDeviceNew() {
    launchWithLoading {
      deviceManager.rebootDevice(megaDevice.deviceSn)
        .onSuccess {
          emitToast("The device is restarting...\nPlease wait for a few minutes.")
        }
        .onError { code, message ->
          emitToast("Failed to restart: $message")
          appLogger.e("restartDeviceNew failed: code=$code, message=$message")
        }
    }
  }

  /**
   * 移除设备（使用新 API）
   */
  fun removeDeviceNew(onSuccess: () -> Unit) {
    launchWithLoading {
      deviceManager.unbindDevice(megaDevice.deviceSn)
        .onSuccess {
          onSuccess()
          emitToast("Device removed successfully")
        }
        .onError { code, message ->
          emitToast("Failed to remove: $message")
          appLogger.e("removeDeviceNew failed: code=$code, message=$message")
        }
    }
  }
}
