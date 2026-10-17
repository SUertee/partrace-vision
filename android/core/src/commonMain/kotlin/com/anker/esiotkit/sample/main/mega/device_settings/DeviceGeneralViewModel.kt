package com.anker.esiotkit.sample.main.mega.device_settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.support.json.kJson
import com.anker.esiotkit.core.constants.ESResult
import com.anker.esiotkit.core.presentation.pairing.PairingDescriptionManager
import com.anker.esiotkit.core.iotmodel.default_service.SystemTimeFormat
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.interaction.ThingPayload
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.sample.main.mega.device_settings.mode.DeviceLocalStorageInfo
import com.anker.esiotkit.sample.main.mega.device_settings.mode.DeviceTimeZoneInfo
import com.anker.esiotkit.sample.support.DeviceFamilyCategoryUtil
import com.anker.esiotkit.sample.view.emitToast
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetIn
import com.anker.esiotkit.support.IoDispatcher
import com.anker.esiotkit.support.json.jsonToObject
import com.anker.esiotkit.support.ktx.getInt
import com.anker.esiotkit.support.ktx.getPayload
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.dataLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeviceGeneralViewModel(
  megaDevice: MegaDevice,
  private val deviceManager: ESIotDeviceManager,
  parent: BaseScreenModel
) : SubSettingsViewModel(megaDevice, parent) {

  private val deviceSn = megaDevice.deviceSn

  private val _deviceName = megaDevice.propertyState(ThingIdentifier.DEVICE_NAME)
  val deviceName by _deviceName.asStringState()

  // 响应式的显示名称，与设备卡片逻辑一致
  var displayDeviceName by mutableStateOf(
    if (megaDevice.iotDevice.deviceName == megaDevice.deviceSn) {
      megaDevice.deviceSn
    } else {
      megaDevice.iotDevice.deviceName.ifEmpty { megaDevice.deviceSn }
    }
  )

  private val _ledState = megaDevice.propertyState(ThingIdentifier.LED_ON_OFF)
  val ledState by _ledState.asBooleanState()

  private val _deviceWifiName = megaDevice.propertyState(ThingIdentifier.DEVICE_WIFI_NAME)
  val deviceWifiName by _deviceWifiName.asStringState()

  private val _timeFormat = megaDevice.propertyState(ThingIdentifier.TIME_FORMAT_SET)
  val timeFormat by _timeFormat.asStringState()

  // 类型化时制显示状态：读写收敛到 DeviceInfoAPI（getCurrentTimeFormat/setTimeFormat），成功后悲观提交
  var timeFormatValue by mutableStateOf(SystemTimeFormat.TIME_SYSTEM_12)
    private set

  private val _timezone = megaDevice.propertyState(ThingIdentifier.TIMEZONE_SET)
  val timezone by _timezone.asStringState()

  val timeZoneList = mutableStateListOf<String>()

  var storageInfo by mutableStateOf<DeviceLocalStorageInfo?>(null)

  // About Device - 直接从 iotDevice 获取设备信息（更可靠）
  var deviceModelNumber by mutableStateOf(
    megaDevice.iotDevice.deviceModel.ifEmpty { megaDevice.devicePn }
  )

  // Serial Number - 直接使用 deviceSn
  val deviceSN: String = megaDevice.deviceSn

  // IP Address - 优先从物模型属性读取，为空时回退到设备网络信息
  private val _ipAddressProp = megaDevice.propertyState(ThingIdentifier.IP_ADDRESS)
  val ipAddress: String
    get() {
      val propValue = _ipAddressProp.payload.value.asString()
      return if (propValue.isNotEmpty()) propValue
      else megaDevice.iotDevice.ipAddress.ifEmpty { megaDevice.iotDevice.localIp }
    }

  // MAC Address - 使用 wifiMac
  val macAddress: String = megaDevice.iotDevice.wifiMac

  // System Version - 使用 mainSwVersion
  val systemVersion: String = megaDevice.iotDevice.mainSwVersion

  // Hardware Version - 对齐 mega QueryDeviceData.main_hw_version
  val hardwareVersion: String = megaDevice.iotDevice.mainHwVersion.ifEmpty { "Unknown" }

  // Reset Device 能力判断
  val isRestartSupported: Boolean = deviceManager.isFeatureSupported(
    com.anker.esiotkit.domain_thing.api.domain.Feature.FACTORY_RESET, deviceSn
  )

  private val _deviceWifiSignalStrengthLevel =
    megaDevice.propertyState(ThingIdentifier.DEVICE_WIFI_SIGNAL_STRENGTH_LEVEL)
  val deviceWifiSignalStrengthLevel by _deviceWifiSignalStrengthLevel.asIntState()

  private val _deviceWifiSignalStrength =
    megaDevice.propertyState(ThingIdentifier.DEVICE_WIFI_SIGNAL_STRENGTH)
  val deviceWifiSignalStrength by _deviceWifiSignalStrength.asStringState()

  var pairingType by mutableStateOf("")

  fun initialize(scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch {
      timeFormatValue = deviceManager.info(deviceSn).getCurrentTimeFormat()
      dataLogger.i("DeviceGeneralViewModel/init/timeFormat: value=${timeFormatValue.value}, deviceSn=$deviceSn")
      loadTimeZoneList()
      getStorageInfo()
      kotlin.runCatching {
        DeviceFamilyCategoryUtil.getAll().let {
          DeviceFamilyCategoryUtil.query(it, deviceModelNumber)
        }.firstOrNull {
          it.deviceList.isNotEmpty()
        }?.deviceList
          ?.firstOrNull()
          ?.let {
            deviceModelNumber = it.deviceTypeName
          }
      }
      appLogger.i("deviceWifiSignalStrengthLevel:${deviceWifiSignalStrengthLevel}")
    }
  }

  fun getPairingType() {
    launchWithLoading(context = IoDispatcher) {
      try {
        val mainTypes =
          PairingDescriptionManager.getPairingDescription(megaDevice.devicePn).await()?.mainTypes
        val typeConfig = mainTypes?.find { !it.type.startsWith("+") }
        pairingType = typeConfig?.type ?: ""
      } catch (e: Exception) {
        emitToast("error: $e")
        appLogger.i("error: $e")
      }
    }
  }

  fun getWifiList() {
    launchWithLoading {
      megaDevice.getAction(ThingIdentifier.GET_WIFI_LIST)?.invoke(ThingPayload.create())?.await()
        ?.onSuccess { payload ->
          val jsonString = payload.asJson()
          val res = kJson.decodeFromString<JsonObject>(jsonString)
        }.onNotSuccess()
    }
  }

  private fun loadTimeZoneList() {
    launchWithLoading {
      if (InfoCache.useNewApi) {
        dataLogger.i("DeviceGeneralViewModel/loadTimeZoneList/start [NEW API]")
        // fetchTimezoneListData 的降级封装在 SDK 层：action 缺失/P2P 失败/为空 → 本地时区 JSON，source 标记数据来源
        val data = deviceManager.info(deviceSn).fetchTimezoneListData()
        dataLogger.i("DeviceGeneralViewModel/loadTimeZoneList/success [NEW API]: count=${data.list.size}, source=${data.source}")
        timeZoneList.clear()
        timeZoneList.addAll(data.list.map { timeZoneInfo ->
          val gmtOffset = computeGmtOffset(timeZoneInfo.timeId)
          "($gmtOffset) ${timeZoneInfo.timeId}"
        })
      } else {
        dataLogger.i("DeviceGeneralViewModel/loadTimeZoneList/start [OLD API]")
        if (!megaDevice.hasAction(ThingIdentifier.GET_TIMEZONE_LIST)) {
          dataLogger.i("DeviceGeneralViewModel/loadTimeZoneList/return")
          return@launchWithLoading
        }
        megaDevice.getAction(ThingIdentifier.GET_TIMEZONE_LIST)?.invoke(ThingPayload.create())?.await()
          ?.onSuccess { payload ->
            val jsonString = payload.asJson()
            val res = kJson.decodeFromString<JsonObject>(jsonString)
            val timeListString = res["timezone_list"]?.jsonPrimitive?.content
            val timeList = timeListString?.let {
              kJson.decodeFromString<List<DeviceTimeZoneInfo>>(it)
            }
            timeList?.let {
              timeZoneList.clear()
              timeZoneList.addAll(it.map { timeZoneInfo ->
                val gmtOffset = computeGmtOffset(timeZoneInfo.timeId)
                "($gmtOffset) ${timeZoneInfo.timeId}"
              })
            }
          }.onNotSuccess()
      }
    }
  }

  private fun computeGmtOffset(timeId: String): String {
    return try {
      val tz = TimeZone.of(timeId)
      val offset = Clock.System.now().offsetIn(tz).totalSeconds
      val sign = if (offset >= 0) "+" else ""
      val hour = offset / 3600
      val minute = (offset % 3600) / 60
      "GMT$sign${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    } catch (_: Exception) {
      "GMT+00:00"
    }
  }

  private fun getStorageInfo() {
    megaDevice.getAction(ThingIdentifier.GET_STORAGE_INFO)?.invoke(ThingPayload.create())
      ?.onSuccess { payload ->
        val jsonString = payload.asJson()
        val res = kJson.decodeFromString<DeviceLocalStorageInfo>(jsonString)
        storageInfo = res
      }
  }

  fun setDeviceName(value: String) {
    launchWithLoading {
      if (InfoCache.useNewApi) {
        appLogger.i("setDeviceName [NEW API]: $value")
        deviceManager.info(deviceSn).setName(value)
          .onSuccess { emitToast("Set succeed") }
          .onError { _, msg -> emitToast("Failed: $msg") }
      } else {
        appLogger.i("setDeviceName [OLD API]: $value")
        _deviceName.set(ThingPayload.fromString(value))?.onSuccess {
          emitToast("Set succeed")
        }.onNotSuccess()
      }
    }
  }

  fun setLedState(value: Boolean) {
    launchWithLoading {
      if (InfoCache.useNewApi) {
        appLogger.i("setLedState [NEW API]: $value")
        deviceManager.info(deviceSn).setLedStatus(value)
          .onSuccess { emitToast("Set succeed") }
          .onError { _, msg -> emitToast("Failed: $msg") }
      } else {
        appLogger.i("setLedState [OLD API]: $value")
        _ledState.set(ThingPayload.fromBoolean(value))?.onSuccess {
          emitToast("Set succeed")
        }.onNotSuccess()
      }
    }
  }

  // 挂起版时制设置：等待设备 ACK 后返回结果，供 UI 挂 loading（对齐 setTimezoneAwait 模式）
  suspend fun setTimeFormatAwait(value: String): Boolean {
    dataLogger.i("DeviceGeneralViewModel/setTimeFormatAwait/start: value=$value, deviceSn=$deviceSn")
    return if (InfoCache.useNewApi) {
      val result = deviceManager.info(deviceSn).setTimeFormat(value)
      if (result.isSuccess) {
        timeFormatValue = if (value == "1") SystemTimeFormat.TIME_SYSTEM_24 else SystemTimeFormat.TIME_SYSTEM_12
        dataLogger.i("DeviceGeneralViewModel/setTimeFormatAwait/commit: value=$value, deviceSn=$deviceSn")
        emitToast("Set succeed")
        true
      } else {
        result.onError { _, msg -> emitToast("Failed: $msg") }
        false
      }
    } else {
      var success = false
      val response = _timeFormat.set(ThingPayload.fromString(value))
      response?.onSuccess { success = true }
      if (success) {
        timeFormatValue = if (value == "1") SystemTimeFormat.TIME_SYSTEM_24 else SystemTimeFormat.TIME_SYSTEM_12
        dataLogger.i("DeviceGeneralViewModel/setTimeFormatAwait/commit [OLD API]: value=$value, deviceSn=$deviceSn")
        emitToast("Set succeed")
      } else {
        response.onNotSuccess()
      }
      success
    }
  }

  fun setTimeZone(value: String) {
    launchWithLoading {
      if (InfoCache.useNewApi) {
        appLogger.i("setTimeZone [NEW API]: $value")
        deviceManager.info(deviceSn).setTimezone(value)
          .onSuccess { emitToast("Set succeed") }
          .onError { _, msg -> emitToast("Failed: $msg") }
      } else {
        appLogger.i("setTimeZone [OLD API]: $value")
        _timezone.set(ThingPayload.fromString(value))?.onSuccess {
          emitToast("Set succeed")
        }.onNotSuccess()
      }
    }
  }

  fun formatStorage() {
    launchWithLoading {
      emitToast("The device storage is formatting... \nPlease wait for a few minutes.")
      val response =
        megaDevice.getAction(ThingIdentifier.FORMAT_STORAGE)?.invoke(ThingPayload.create())?.await()
          .onNotSuccess()
      if (response?.result == ESResult.Success) {
        val action = megaDevice.getAction(ThingIdentifier.GET_STORAGE_INFO)
        if (action == null) {
          return@launchWithLoading
        }
        withTimeoutOrNull(60_000) {
          var status = -1
          var totalSpace = -1
          while (status != 0 || totalSpace <= 0) {
            delay(3000)
            action.invoke().await().onSuccess {
              status = it.asJson().jsonToObject().getPayload("status").getInt("status")
              totalSpace = it.asJson().jsonToObject().getInt("totalSpace")
              dataLogger.i("DeviceGeneralViewModel/formatStorage/status:$status, totalSpace:$totalSpace")
              dataLogger.i("DeviceGeneralViewModel/formatStorage/json:${it.asJson()}")
              if (status == 0 && totalSpace > 0) {
                val jsonString = it.asJson()
                val res = kJson.decodeFromString<DeviceLocalStorageInfo>(jsonString)
                storageInfo = res
                emitToast("Format succeed")
              } else {
                dataLogger.i("DeviceGeneralViewModel/formatStorage/status:$status, waiting for format to complete")
              }
            }
          }
        }
      }
    }
  }

  fun clearStorage() {
    launchWithLoading {
      megaDevice.getAction(ThingIdentifier.CLEAR_STORAGE)?.invoke(ThingPayload.create())?.await()?.onSuccess {
        emitToast("The device storage is clearing... \nPlease wait for a few minutes.")
      }.onNotSuccess()
    }
  }

  // ========== New API Methods (using ESIotDeviceManager) ==========

  /**
   * 设置设备名称（使用新 API）
   */
  fun setDeviceNameNew(name: String) {
    launchWithLoading {
      deviceManager.info(deviceSn).setName(name)
        .onSuccess {
          displayDeviceName = name
          emitToast("Device name set")
        }
        .onError { code, message ->
          emitToast("Failed: $message")
          appLogger.e("setDeviceNameNew failed: code=$code, message=$message")
        }
    }
  }

  /**
   * 设置 LED 状态（使用新 API）
   */
  fun setLedStatusNew(enabled: Boolean) {
    launchWithLoading {
      deviceManager.info(deviceSn).setLedStatus(enabled)
        .onSuccess {
          emitToast("LED status set")
        }
        .onError { code, message ->
          emitToast("Failed: $message")
          appLogger.e("setLedStatusNew failed: code=$code, message=$message")
        }
    }
  }

  /**
   * 设置时间格式（使用新 API）
   */
  fun setTimeFormatNew(format: String) {
    launchWithLoading {
      deviceManager.info(deviceSn).setTimeFormat(format)
        .onSuccess {
          emitToast("Time format set")
        }
        .onError { code, message ->
          emitToast("Failed: $message")
          appLogger.e("setTimeFormatNew failed: code=$code, message=$message")
        }
    }
  }

  /**
   * 设置时区（使用新 API）
   */
  fun setTimezoneNew(timezone: String) {
    launchWithLoading {
      deviceManager.info(deviceSn).setTimezone(timezone)
        .onSuccess {
          emitToast("Timezone set")
        }
        .onError { code, message ->
          emitToast("Failed: $message")
          appLogger.e("setTimezoneNew failed: code=$code, message=$message")
        }
    }
  }

  suspend fun setTimezoneAwait(timezone: String) {
    appLogger.i("TZ|setTimezoneAwait/start: $timezone, sn=$deviceSn")
    val result = deviceManager.info(deviceSn).setTimezone(timezone)
    if (result.isSuccess) {
      appLogger.i("TZ|setTimezoneAwait/success: $timezone")
      _timezone.set(ThingPayload.fromString(timezone))
    } else {
      appLogger.e("TZ|setTimezoneAwait/failure: $result")
    }
  }

  /**
   * 同步设备时间（使用新 API）
   */
  fun syncTimeNew() {
    launchWithLoading {
      deviceManager.info(deviceSn).syncTime()
        .onSuccess {
          emitToast("Time synced")
        }
        .onError { code, message ->
          emitToast("Failed: $message")
          appLogger.e("syncTimeNew failed: code=$code, message=$message")
        }
    }
  }

  /**
   * 格式化存储（使用新 API）
   */
  fun formatStorageNew() {
    launchWithLoading {
      emitToast("The device storage is formatting... \nPlease wait for a few minutes.")
      deviceManager.storage(deviceSn).formatSdCard()
        .onSuccess {
          emitToast("Format succeed")
          // Refresh storage info
          getStorageInfo()
        }
        .onError { code, message ->
          emitToast("Failed: $message")
          appLogger.e("formatStorageNew failed: code=$code, message=$message")
        }
    }
  }

  /**
   * 清除存储（使用新 API）
   */
  fun clearStorageNew() {
    launchWithLoading {
      emitToast("The device storage is clearing... \nPlease wait for a few minutes.")
      deviceManager.storage(deviceSn).clearStorage()
        .onSuccess {
          emitToast("Clear succeed")
          // Refresh storage info
          getStorageInfo()
        }
        .onError { code, message ->
          emitToast("Failed: $message")
          appLogger.e("clearStorageNew failed: code=$code, message=$message")
        }
    }
  }

  private var isFactoryResetInProgress = false

  fun factoryReset() {
    if (isFactoryResetInProgress) {
      appLogger.w("DeviceGeneralViewModel/factoryReset/skipped: reason=in_flight, deviceSn=$deviceSn")
      return
    }
    launchWithLoading {
      isFactoryResetInProgress = true
      try {
        appLogger.i("DeviceGeneralViewModel/factoryReset/start: deviceSn=$deviceSn, isFactoryResetSupported=$isRestartSupported")
        val online = megaDevice.getProperty(ThingIdentifier.DEVICE_IS_ONLINE)?.get()?.asBoolean()
        if (online == false) {
          appLogger.i("DeviceGeneralViewModel/factoryReset/fallback: reason=device_offline, deviceSn=$deviceSn")
          emitToast("Device is offline. Please check the device connection and try again.")
          return@launchWithLoading
        }
        deviceManager.factoryResetDevice(deviceSn)
          .onSuccess {
            appLogger.i("DeviceGeneralViewModel/factoryReset/success: deviceSn=$deviceSn")
            emitToast("The device is resetting to factory defaults.\nPlease wait for a few minutes.")
          }
          .onError { code, message ->
            appLogger.e("DeviceGeneralViewModel/factoryReset/failure: code=$code, msg=$message, deviceSn=$deviceSn")
            emitToast("Failed to reset: $message")
          }
      } finally {
        isFactoryResetInProgress = false
      }
    }
  }
}
