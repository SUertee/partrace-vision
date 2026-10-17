package com.anker.esiotkit.sample.main.mega.device_settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anker.esiotkit.core.constants.ServiceConstants
import com.anker.esiotkit.core.iotmodel.default_service.NotificationDoorbellService
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.domain_thing.api.domain.AlertToneInfo
import com.anker.esiotkit.domain_thing.api.domain.NotificationMode
import com.anker.esiotkit.domain_thing.api.domain.NotificationModeOption
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.dataLogger
import com.anker.esiotkit.support.json.kJson

/**
 * 通知设置 ViewModel - 混合模式
 *
 * - 标准通知设置：使用 ESIotDeviceManager.notification() API
 * - 特殊功能（室内铃声、自动应答）：保留 MegaDevice 访问
 */
class NotificationSettingsViewModel(
  megaDevice: MegaDevice,
  private val deviceManager: ESIotDeviceManager,
  parent: BaseScreenModel
) : SubSettingsViewModel(megaDevice, parent) {

  private val deviceSn: String = megaDevice.deviceSn

  private val notificationApi = deviceManager.notification(deviceSn)

  var currentNotificationMode by mutableStateOf(notificationApi.getCurrentNotificationMode())
    private set

  private val modeCancellable = notificationApi.observeNotificationMode { mode ->
    currentNotificationMode = mode
  }

  init {
    doOnParentDispose { modeCancellable.cancel() }
  }

  fun notificationModeOptions(): List<NotificationModeOption> = notificationApi.getNotificationModeOptions()

  private val _switchingNotification = megaDevice.propertyState(ThingIdentifier.SWITCHING_NOTIFICATION)
  val switchingNotification by _switchingNotification.asStringState()

  private val _notificationJump = megaDevice.propertyState(ThingIdentifier.NOTIFICATION_JUMP_TO)
  val notificationJump by _notificationJump.asIntState()

  private val _doorbellRing = megaDevice.propertyState(ThingIdentifier.NOTIFICATION_RING_ONOFF)
  val doorbellRing by _doorbellRing.asBooleanState()

  private val _motionDetect = megaDevice.propertyState(ThingIdentifier.NOTIFICATION_MOTION_ONOFF)
  val motionDetect by _motionDetect.asBooleanState()

  var motionAlerts by mutableIntStateOf(notificationApi.getMotionAlertLevel())
    private set
  var ringAlerts by mutableIntStateOf(notificationApi.getRingAlertLevel())
    private set

  // Indoor Chime
  private val _doorbellChime = megaDevice.propertyState("doorbell_chime_onoff")
  val doorbellChime by _doorbellChime.asBooleanState()

  private val _minibaseChime = megaDevice.propertyState("minibase_chime_onoff")
  val minibaseChime by _minibaseChime.asBooleanState()

  private val _homebaseChime = megaDevice.propertyState("homebase_chime_onoff")
  val homebaseChime by _homebaseChime.asBooleanState()

  private val _chimeVolume = megaDevice.propertyState("chime_volume")
  val chimeVolume by _chimeVolume.asIntState()

  // Response Settings
  private val _autoResponseEnable = megaDevice.propertyState("auto_response_enable")
  val autoResponseEnable by _autoResponseEnable.asBooleanState()

  private val _autoResponseDelay = megaDevice.propertyState("auto_response_delay")
  val autoResponseDelay by _autoResponseDelay.asIntState()

  private val _voiceResponseList = megaDevice.propertyState("voice_response_list")
  val voiceResponseList by _voiceResponseList.asStringState()

  // ==================== 新 API 方法 ====================

  fun setNotificationMode(mode: NotificationMode) {
    appLogger.i("NotificationSettings/setNotificationMode: deviceSn=$deviceSn, mode=$mode(${mode.deviceValue})")
    val previous = currentNotificationMode
    currentNotificationMode = mode
    launchWithLoading {
      notificationApi.setNotificationMode(mode)
        .onSuccess {
          dataLogger.i("NotificationSettings/setNotificationMode/success: deviceSn=$deviceSn, mode=$mode(${mode.deviceValue})")
          emitToast("Notification type set")
        }.onError { code, message ->
          appLogger.e("setNotificationMode failed: deviceSn=$deviceSn, mode=$mode, code=$code, message=$message")
          currentNotificationMode = previous
          emitToast("Failed: $message")
        }
    }
  }

  fun setNotificationJump(value: Int) {
    appLogger.i("NotificationSettings/setNotificationJump: deviceSn=$deviceSn, value=$value")
    dataLogger.i("NotificationSettings/setNotificationJump/current: _notificationJump.payload=${_notificationJump.payload.value.asString()}")
    launchWithLoading {
      notificationApi.setNotificationJump(value)
        .onSuccess {
          _notificationJump.payload.value = ThingPayload.fromInt(value)
          dataLogger.i("NotificationSettings/setNotificationJump/success: deviceSn=$deviceSn, value=$value")
          dataLogger.i("NotificationSettings/setNotificationJump/afterSet: _notificationJump.payload=${_notificationJump.payload.value.asString()}")
          emitToast("Notification jump set")
        }.onError { code, message ->
          emitToast("Failed: $message")
          appLogger.e("setNotificationJump failed: deviceSn=$deviceSn, value=$value, code=$code, message=$message")
        }
    }
  }

  fun isNotificationJumpSupported(): Boolean {
    if (megaDevice.hasProperty(ThingIdentifier.NOTIFICATION_JUMP_TO)) return true
    return megaDevice.iotDevice.getService<NotificationDoorbellService>(
      ServiceConstants.NOTIFICATION_STATUS_DOORBELL
    ) != null
  }

  fun setDoorbellRing(value: Boolean) {
    appLogger.i("NotificationSettings/setDoorbellRing: deviceSn=$deviceSn, value=$value")
    dataLogger.i("NotificationSettings/setDoorbellRing/current: _doorbellRing.payload=${_doorbellRing.payload.value.asString()}")
    launchWithLoading {
      notificationApi.setDoorbellRingNotification(value)
        .onSuccess {
          _doorbellRing.payload.value = ThingPayload.fromBoolean(value)
          dataLogger.i("NotificationSettings/setDoorbellRing/success: deviceSn=$deviceSn, value=$value")
          dataLogger.i("NotificationSettings/setDoorbellRing/afterSet: _doorbellRing.payload=${_doorbellRing.payload.value.asString()}")
          emitToast("Doorbell notification ${if (value) "enabled" else "disabled"}")
        }.onError { code, message ->
          emitToast("Failed: $message")
          appLogger.e("setDoorbellRing failed: deviceSn=$deviceSn, value=$value, code=$code, message=$message")
        }
    }
  }

  fun setMotionDetect(value: Boolean) {
    appLogger.i("NotificationSettings/setMotionDetect: deviceSn=$deviceSn, value=$value")
    dataLogger.i("NotificationSettings/setMotionDetect/current: _motionDetect.payload=${_motionDetect.payload.value.asString()}")
    launchWithLoading {
      notificationApi.setMotionDetectNotification(value)
        .onSuccess {
          _motionDetect.payload.value = ThingPayload.fromBoolean(value)
          dataLogger.i("NotificationSettings/setMotionDetect/success: deviceSn=$deviceSn, value=$value")
          dataLogger.i("NotificationSettings/setMotionDetect/afterSet: _motionDetect.payload=${_motionDetect.payload.value.asString()}")
          emitToast("Motion notification ${if (value) "enabled" else "disabled"}")
        }.onError { code, message ->
          emitToast("Failed: $message")
          appLogger.e("setMotionDetect failed: deviceSn=$deviceSn, value=$value, code=$code, message=$message")
        }
    }
  }

  fun selectMotionAlert(value: Int) {
    appLogger.i("[ALTONE] VM setMotionAlerts ENTER: value=$value")
    launchWithLoading {
      notificationApi.setMotionAlertLevel(value)
        .onSuccess {
          motionAlerts = value
          val toneName = notificationApi.getMotionAlertTones()
            .firstOrNull { it.index == value }?.name ?: "?"
          appLogger.i("[ALTONE] VM setMotionAlerts SUCCESS: motionAlerts=$motionAlerts, name=$toneName")
          emitToast("Motion Alert set to $toneName ($value)")
        }.onError { code, message ->
          appLogger.e("[ALTONE] VM setMotionAlerts FAILED: value=$value, code=$code, message=$message")
          emitToast("Failed: $message")
        }
    }
  }

  fun selectRingAlert(value: Int) {
    appLogger.i("[ALTONE] VM setRingAlert ENTER: value=$value")
    launchWithLoading {
      notificationApi.setRingAlertLevel(value)
        .onSuccess {
          ringAlerts = value
          val toneName = notificationApi.getRingAlertTones()
            .firstOrNull { it.index == value }?.name ?: "?"
          appLogger.i("[ALTONE] VM setRingAlert SUCCESS: ringAlerts=$ringAlerts, name=$toneName")
          emitToast("Ring Alert set to $toneName ($value)")
        }.onError { code, message ->
          appLogger.e("[ALTONE] VM setRingAlert FAILED: value=$value, code=$code, message=$message")
          emitToast("Failed: $message")
        }
    }
  }

  // ==================== 保留 MegaDevice 方法（特殊功能）====================

  fun setSwitchingNotification(value: String) {
    appLogger.i("NotificationSettings/setSwitchingNotification: deviceSn=$deviceSn, value=$value")
    dataLogger.i("NotificationSettings/setSwitchingNotification/current: _switchingNotification.payload=${_switchingNotification.payload.value.asString()}")
    launchWithLoading {
      _switchingNotification.set(ThingPayload.fromString(value))?.onSuccess {
        dataLogger.i("NotificationSettings/setSwitchingNotification/success: deviceSn=$deviceSn, value=$value")
        dataLogger.i("NotificationSettings/setSwitchingNotification/afterSet: _switchingNotification.payload=${_switchingNotification.payload.value.asString()}")
        emitToast("Set succeed")
      }.onNotSuccess()
    }
  }

  fun setDoorbellChime(value: Boolean) {
    launchWithLoading {
      appLogger.i("setDoorbellChime: value=$value")
      val property = megaDevice.getProperty("doorbell_chime_onoff")
      if (property == null) {
        appLogger.e("setDoorbellChime: property not found")
        emitToast("Doorbell chime not supported by this device")
        return@launchWithLoading
      }
      _doorbellChime.set(ThingPayload.fromBoolean(value))
        ?.onSuccess {
          appLogger.i("setDoorbellChime: success")
          emitToast("Doorbell chime ${if (value) "enabled" else "disabled"}")
        }?.onFailure { code, msg ->
          appLogger.e("setDoorbellChime failed: code=$code, msg=$msg")
          emitToast("Failed to set doorbell chime: $msg")
        }
    }
  }

  fun setMinibaseChime(value: Boolean) {
    launchWithLoading {
      appLogger.i("setMinibaseChime: value=$value")
      val property = megaDevice.getProperty("minibase_chime_onoff")
      if (property == null) {
        appLogger.e("setMinibaseChime: property not found")
        emitToast("Minibase chime not supported by this device")
        return@launchWithLoading
      }
      _minibaseChime.set(ThingPayload.fromBoolean(value))
        ?.onSuccess {
          appLogger.i("setMinibaseChime: success")
          emitToast("Minibase chime ${if (value) "enabled" else "disabled"}")
        }?.onFailure { code, msg ->
          appLogger.e("setMinibaseChime failed: code=$code, msg=$msg")
          emitToast("Failed to set minibase chime: $msg")
        }
    }
  }

  fun setHomebaseChime(value: Boolean) {
    launchWithLoading {
      appLogger.i("setHomebaseChime: value=$value")
      val property = megaDevice.getProperty("homebase_chime_onoff")
      if (property == null) {
        appLogger.e("setHomebaseChime: property not found")
        emitToast("Homebase chime not supported by this device")
        return@launchWithLoading
      }
      _homebaseChime.set(ThingPayload.fromBoolean(value))
        ?.onSuccess {
          appLogger.i("setHomebaseChime: success")
          emitToast("Homebase chime ${if (value) "enabled" else "disabled"}")
        }?.onFailure { code, msg ->
          appLogger.e("setHomebaseChime failed: code=$code, msg=$msg")
          emitToast("Failed to set homebase chime: $msg")
        }
    }
  }

  fun setChimeVolume(value: Int) {
    launchWithLoading {
      appLogger.i("setChimeVolume: value=$value")
      val property = megaDevice.getProperty("chime_volume")
      if (property == null) {
        appLogger.e("setChimeVolume: property not found")
        emitToast("Chime volume not supported by this device")
        return@launchWithLoading
      }
      _chimeVolume.set(ThingPayload.fromInt(value))
        ?.onSuccess {
          appLogger.i("setChimeVolume: success")
          emitToast("Chime volume set to $value")
        }?.onFailure { code, msg ->
          appLogger.e("setChimeVolume failed: code=$code, msg=$msg")
          emitToast("Failed to set chime volume: $msg")
        }
    }
  }

  fun setAutoResponseEnable(value: Boolean) {
    launchWithLoading {
      appLogger.i("setAutoResponseEnable: value=$value")
      val property = megaDevice.getProperty("auto_response_enable")
      if (property == null) {
        appLogger.e("setAutoResponseEnable: property not found")
        emitToast("Auto-response not supported by this device")
        return@launchWithLoading
      }
      _autoResponseEnable.set(ThingPayload.fromBoolean(value))
        ?.onSuccess {
          appLogger.i("setAutoResponseEnable: success")
          emitToast("Auto-response ${if (value) "enabled" else "disabled"}")
        }?.onFailure { code, msg ->
          appLogger.e("setAutoResponseEnable failed: code=$code, msg=$msg")
          emitToast("Failed to set auto-response: $msg")
        }
    }
  }

  fun setAutoResponseDelay(value: Int) {
    launchWithLoading {
      appLogger.i("setAutoResponseDelay: value=$value")
      val property = megaDevice.getProperty("auto_response_delay")
      if (property == null) {
        appLogger.e("setAutoResponseDelay: property not found")
        emitToast("Auto-response delay not supported by this device")
        return@launchWithLoading
      }
      _autoResponseDelay.set(ThingPayload.fromInt(value))
        ?.onSuccess {
          appLogger.i("setAutoResponseDelay: success")
          emitToast("Response delay set to $value seconds")
        }?.onFailure { code, msg ->
          appLogger.e("setAutoResponseDelay failed: code=$code, msg=$msg")
          emitToast("Failed to set response delay: $msg")
        }
    }
  }

  fun updateVoiceResponses(responses: List<com.anker.esiotkit.sample.main.mega.device_settings.notification.VoiceResponse>) {
    launchWithLoading {
      appLogger.i("updateVoiceResponses: count=${responses.size}")
      val property = megaDevice.getProperty("voice_response_list")
      if (property == null) {
        appLogger.e("updateVoiceResponses: property not found")
        emitToast("Voice responses not supported by this device")
        return@launchWithLoading
      }
      val json = kJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(
          com.anker.esiotkit.sample.main.mega.device_settings.notification.VoiceResponse.serializer()
        ),
        responses
      )
      _voiceResponseList.set(ThingPayload.fromString(json))
        ?.onSuccess {
          emitToast("Voice responses updated")
          appLogger.i("updateVoiceResponses: success")
        }?.onFailure { code, msg ->
          appLogger.e("updateVoiceResponses failed: code=$code, msg=$msg")
          emitToast("Failed to update voice responses: $msg")
        }
    }
  }
}
