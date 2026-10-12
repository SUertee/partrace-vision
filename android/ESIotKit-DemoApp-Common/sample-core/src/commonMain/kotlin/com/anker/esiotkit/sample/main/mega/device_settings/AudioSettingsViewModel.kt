package com.anker.esiotkit.sample.main.mega.device_settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.domain_thing.api.domain.AudioSettings
import com.anker.esiotkit.domain_thing.api.model.ThingCancellable
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.net_api.response.thing.TDDataType
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.sample.support.createPayloadByPropertyType
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.dataLogger

class AudioSettingsViewModel(
  megaDevice: MegaDevice,
  private val deviceManager: ESIotDeviceManager,
  parent: BaseScreenModel
) : SubSettingsViewModel(megaDevice, parent) {

  private val deviceSn: String = megaDevice.deviceSn

  // New API: Audio settings from ESIotDeviceManager
  var audioSettings by mutableStateOf<AudioSettings?>(null)
    private set

  private var audioSubscription: ThingCancellable? = null

  // Backward compatible properties for UI binding
  private val _speakerState = megaDevice.propertyState(ThingIdentifier.SPEAKER_ON_OFF)
  val speakerState by _speakerState.asBooleanState()

  private val _speakerVolume = megaDevice.propertyState(ThingIdentifier.SPEAKER_VOLUME)
  val speakerVolume by _speakerVolume.asIntState()

  // Sound Detection - Special features using MegaDevice
  private val _soundDetectionSwitch = megaDevice.propertyState("sound_detection_status")
  val soundDetectionSwitch by _soundDetectionSwitch.asBooleanState()

  private val _soundDetectionType = megaDevice.propertyState("sound_detection_type")
  val soundDetectionType by _soundDetectionType.asStringState()

  private val _soundSensitivity = megaDevice.propertyState("sound_detection_sensitivity")
  val soundSensitivity by _soundSensitivity.asIntState()

  // Round-look (巡航联动) — 声音检测开关开启后，检测到声音时设备进行360°巡航
  // 属性名: sound_detection_round_look_switch (Mega iOS/RN/SDK 三端一致)
  private val _roundLookSwitch = megaDevice.propertyState("sound_detection_round_look_switch")
  val roundLookSwitch by _roundLookSwitch.asBooleanState()

  init {
    // Subscribe to audio settings changes using new API
    audioSubscription = deviceManager.audio(deviceSn).observe { settings ->
      audioSettings = settings
    }
  }

  // ==================== New API Methods (Type-safe) ====================

  /**
   * Set speaker volume using new API
   * @param volume Volume level (0-100)
   */
  fun setSpeakerVolumeNew(volume: Int) {
    launchWithLoading {
      deviceManager.audio(deviceSn).setSpeakerVolume(volume)
        .onSuccess {
          emitToast("Speaker volume set to $volume")
        }
        .onError { code, message ->
          emitToast("Failed to set speaker volume: $message")
        }
    }
  }

  /**
   * Set microphone volume using new API
   * @param volume Volume level (0-100)
   */
  fun setMicrophoneVolumeNew(volume: Int) {
    launchWithLoading {
      deviceManager.audio(deviceSn).setMicrophoneVolume(volume)
        .onSuccess {
          emitToast("Microphone volume set to $volume")
        }
        .onError { code, message ->
          emitToast("Failed to set microphone volume: $message")
        }
    }
  }

  /**
   * Set microphone enabled state using new API
   */
  fun setMicrophoneEnabledNew(enabled: Boolean) {
    launchWithLoading {
      deviceManager.audio(deviceSn).setMicrophoneEnabled(enabled)
        .onSuccess {
          emitToast("Microphone ${if (enabled) "enabled" else "disabled"}")
        }
        .onError { code, message ->
          emitToast("Failed to set microphone: $message")
        }
    }
  }

  /**
   * Set noise filter using new API
   */
  fun setNoiseFilterNew(enabled: Boolean) {
    launchWithLoading {
      deviceManager.audio(deviceSn).setNoiseFilter(enabled)
        .onSuccess {
          emitToast("Noise filter ${if (enabled) "enabled" else "disabled"}")
        }
        .onError { code, message ->
          emitToast("Failed to set noise filter: $message")
        }
    }
  }

  /**
   * Set echo cancellation using new API
   */
  fun setEchoCancelNew(enabled: Boolean) {
    launchWithLoading {
      deviceManager.audio(deviceSn).setEchoCancel(enabled)
        .onSuccess {
          emitToast("Echo cancellation ${if (enabled) "enabled" else "disabled"}")
        }
        .onError { code, message ->
          emitToast("Failed to set echo cancellation: $message")
        }
    }
  }

  /**
   * Set audio recording using new API
   */
  fun setAudioRecordingNew(enabled: Boolean) {
    launchWithLoading {
      deviceManager.audio(deviceSn).setAudioRecording(enabled)
        .onSuccess {
          emitToast("Audio recording ${if (enabled) "enabled" else "disabled"}")
        }
        .onError { code, message ->
          emitToast("Failed to set audio recording: $message")
        }
    }
  }

  // ==================== Old API Methods (Deprecated) ====================

  @Deprecated(
    "Use setSpeakerVolumeNew() instead",
    ReplaceWith("setSpeakerVolumeNew(value)")
  )
  fun setSpeakerState(value: Boolean) {
    launchWithLoading {
      if (InfoCache.useNewApi) {
        appLogger.i("setSpeakerState [NEW API]: $value")
        deviceManager.audio(deviceSn).setMicrophoneEnabled(value)
          .onSuccess { emitToast("Set succeed") }
          .onError { _, msg -> emitToast("Failed: $msg") }
      } else {
        appLogger.i("setSpeakerState [OLD API]: $value")
        _speakerState.set(ThingPayload.fromBoolean(value))?.onSuccess {
          emitToast("Set succeed")
        }.onNotSuccess()
      }
    }
  }

  @Deprecated(
    "Use setSpeakerVolumeNew() instead",
    ReplaceWith("setSpeakerVolumeNew(value)")
  )
  fun setSpeakerVolume(value: Int) {
    launchWithLoading {
      if (InfoCache.useNewApi) {
        appLogger.i("setSpeakerVolume [NEW API]: $value")
        deviceManager.audio(deviceSn).setSpeakerVolume(value)
          .onSuccess { emitToast("Set succeed") }
          .onError { _, msg -> emitToast("Failed: $msg") }
      } else {
        dataLogger.i("speakerVolume [OLD API]: $value")
        _speakerVolume.set(ThingPayload.fromInt(value))?.onSuccess {
          emitToast("Set succeed")
        }.onNotSuccess()
      }
    }
  }

  // ==================== Sound Detection (Special Features - using MegaDevice) ====================

  fun setSoundDetectionSwitch(value: Boolean) {
    launchWithLoading {
      appLogger.i("setSoundDetectionSwitch: value=$value")
      val property = megaDevice.getProperty("sound_detection_status")
      if (property == null) {
        appLogger.e("setSoundDetectionSwitch: property not found")
        emitToast("Sound detection not supported by this device")
        return@launchWithLoading
      }
      _soundDetectionSwitch.set(ThingPayload.fromBoolean(value))
        ?.onSuccess {
          appLogger.i("setSoundDetectionSwitch: success")
          emitToast("Sound detection ${if (value) "enabled" else "disabled"}")
        }?.onFailure { code, msg ->
          appLogger.e("setSoundDetectionSwitch failed: code=$code, msg=$msg")
          emitToast("Failed to set sound detection: $msg")
        }
    }
  }

  fun setSoundDetectionType(value: String) {
    launchWithLoading {
      appLogger.i("setSoundDetectionType: value=$value, deviceSn=$deviceSn")
      val typeInt = value.toIntOrNull() ?: run {
        appLogger.e("setSoundDetectionType: invalid value, value=$value, deviceSn=$deviceSn")
        emitToast("Invalid sound detection type value")
        return@launchWithLoading
      }
      val result = deviceManager.soundDetection(deviceSn).setDetectionType(typeInt)
      if (result.isSuccess) {
        appLogger.i("setSoundDetectionType: success, typeValue=$value, typeInt=$typeInt, deviceSn=$deviceSn")
        emitToast("Sound detection type updated")
      } else {
        appLogger.e("setSoundDetectionType failed: result=$result, typeValue=$value, deviceSn=$deviceSn")
        emitToast("Failed to set sound detection type")
      }
    }
  }

  fun setSoundSensitivity(value: Int) {
    launchWithLoading {
      appLogger.i("setSoundSensitivity: value=$value")
      val property = megaDevice.getProperty("sound_detection_sensitivity")
      if (property == null) {
        appLogger.e("setSoundSensitivity: property not found")
        emitToast("Sound sensitivity not supported by this device")
        return@launchWithLoading
      }
      _soundSensitivity.set(ThingPayload.fromInt(value))
        ?.onSuccess {
          appLogger.i("setSoundSensitivity: success")
          emitToast("Sound sensitivity set to $value")
        }?.onFailure { code, msg ->
          appLogger.e("setSoundSensitivity failed: code=$code, msg=$msg")
          emitToast("Failed to set sound sensitivity: $msg")
        }
    }
  }

  fun setRoundLook(value: Boolean) {
    launchWithLoading {
      appLogger.i("setRoundLook: value=$value, deviceSn=$deviceSn")
      val result = deviceManager.soundDetection(deviceSn).setRoundLookEnabled(value)
      result.onSuccess {
        appLogger.i("setRoundLook: success, value=$value, deviceSn=$deviceSn")
        emitToast("Round-look ${if (value) "enabled" else "disabled"}")
      }.onError { code, msg ->
        appLogger.e("setRoundLook failed: code=$code, msg=$msg, value=$value, deviceSn=$deviceSn")
        emitToast("Failed to set round-look: $msg")
      }
    }
  }

  fun onCleared() {
    audioSubscription?.cancel()
  }
}
