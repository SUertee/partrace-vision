package com.anker.esiotkit.sample.main.mega.add_device.ble_connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.core.presentation.pairing.PairingDescriptionManager
import com.anker.esiotkit.core.support.hasSn
import com.anker.esiotkit.core.support.isSn
import com.anker.esiotkit.core.support.snToPn
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.domain.add_device.pairing.CreatePairingServiceResult
import com.anker.esiotkit.domain.add_device.pairing.PairingServiceFactory
import com.anker.esiotkit.domain.add_device.pairing.PairingTypeResolver
import com.anker.esiotkit.domain.add_device.wifi.ESIotPairingManager
import com.anker.esiotkit.domain.add_device.wifi.model.WifiNetwork
import com.anker.esiotkit.net_api.response.thing.TypeConfig
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.support.isProtocolMatching
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.security_device.domain.repository.SecurityDeviceRepository
import com.anker.esiotkit.security_device.pair.service.SecurityDevicePairingService
import com.anker.esiotkit.support.di.esInject
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.dataLogger

/**
 * @version 2024/9/13
 */
class MegaBLEConnectViewModel(
  private val repository: SecurityDeviceRepository = esInject()
) : BaseScreenModel() {

  private var wifiName by mutableStateOf("")
  private var wifiPassword by mutableStateOf("")
  private var wifiList by mutableStateOf<List<WifiNetwork>>(emptyList())
  private var wifiListLoading by mutableStateOf(false)
  private var selectedWifi: WifiNetwork? = null
  private var sn: String = ""
  private var isScanned by mutableStateOf(false)
  private var transaction: SecurityDevicePairingService? = null
  private var connection by mutableStateOf(false)
  private var connectionBLESuccess by mutableStateOf(false)
  private var connectionBLEFail by mutableStateOf(false)
  private var reminderPage by mutableStateOf(0)
  private var mainTypes by mutableStateOf(emptyList<TypeConfig>())
  private var connectionWiFiSuccess by mutableStateOf(false)
  private var connectionWiFiFail by mutableStateOf(false)
  private var wifiFailMessage by mutableStateOf("")
  private var WiFiConnection by mutableStateOf(false)
  private var name by mutableStateOf(" ")
  private var addSuccess by mutableStateOf(false)
  private var scanCode: String = ""
  private var bleInputData: String = ""

  val uiState = screenModelScope.launchMolecule(RecompositionMode.Immediate) {
    MegaBLEConnectState(
      wifiName = wifiName,
      wifiPassword = wifiPassword,
      wifiList = wifiList,
      wifiListLoading = wifiListLoading,
      sn = sn,
      isScanned = isScanned,
      transaction = transaction,
      connection = connection,
      connectionBLESuccess = connectionBLESuccess,
      connectionBLEFail = connectionBLEFail,
      reminderPage = reminderPage,
      mainTypes = mainTypes,
      connectionWiFiSuccess = connectionWiFiSuccess,
      connectionWiFiFail = connectionWiFiFail,
      wifiFailMessage = wifiFailMessage,
      WiFiConnection = WiFiConnection,
      name = name,
      addSuccess = addSuccess
    )
  }

  fun resetStates() {
    appLogger.i("MegaBLEConnectViewModel/resetStates")
    wifiList = emptyList()
    wifiListLoading = false
    selectedWifi = null
    sn = ""
    isScanned = false
    transaction = null
    connection = false
    connectionBLESuccess = false
    connectionBLEFail = false
    connectionWiFiSuccess = false
    connectionWiFiFail = false
    WiFiConnection = false
    name = " "
    addSuccess = false
    reminderPage = 0
    mainTypes = emptyList()
    bleInputData = ""
  }

  fun choiceReminderPage(value: Int) {
    reminderPage = value
  }

  fun onWifiNameChange(newName: String) {
    wifiName = newName
  }

  fun onWifiPasswordChange(newPassword: String) {
    wifiPassword = newPassword
  }

  fun changeName(value: String) {
    name = value
  }

  fun isAddSuccess(value: Boolean) {
    addSuccess = value
  }

  fun onScanned(code: String, protocol: String, onCompletion: (Boolean) -> Unit) {
    appLogger.i("MegaBLEConnectViewModel/onScanned/code:$code protocol:$protocol")
    launchWithLoading {
      scanCode = code
      try {
        sn = when {
          isSn(code) -> code
          hasSn(code) -> code.substringBefore(",")
          else -> MegaDeviceManager.getDeviceSnBySetupCode(code).await()
        }
        if (sn.isEmpty()) {
          appLogger.w("MegaBLEConnectViewModel/onScanned/snEmpty code:$code")
          onCompletion(false)
          return@launchWithLoading
        }

        // BLE connectBle requires "SN,MAC4" format (PairDeviceByBLEActions.kt:576), aligns with Mega RN getDeviceMac()
        if (!hasSn(code) && !isSn(code)) {
          try {
            val p2pInfo = repository.getP2pConnectionInfo(sn)
            val mac = p2pInfo.data?.btMac?.takeIf { it.isNotEmpty() }
              ?: p2pInfo.data?.wifiMac?.takeIf { it.isNotEmpty() }
              ?: ""
            val macSuffix = mac.replace(":", "").takeLast(4)
            appLogger.i("MegaBLEConnectViewModel/onScanned/getP2pConnectionInfo: sn=$sn, btMac=${p2pInfo.data?.btMac}, wifiMac=${p2pInfo.data?.wifiMac}, macSuffix=$macSuffix")
            bleInputData = if (macSuffix.isNotEmpty()) "$sn,$macSuffix" else sn
          } catch (e: Exception) {
            appLogger.w("MegaBLEConnectViewModel/onScanned/getP2pConnectionInfo failed: ${e.message}")
            bleInputData = sn
          }
        } else {
          bleInputData = code
        }

        appLogger.i("MegaBLEConnectViewModel/onScanned/sn:$sn pn:${sn.snToPn()} bleInputData:$bleInputData")
        mainTypes = PairingDescriptionManager.getPairingDescription(sn.snToPn()).await()?.mainTypes.orEmpty()
        dataLogger.i("MegaBLEConnectViewModel/onScanned/mainTypes:$mainTypes")
        appLogger.i("MegaBLEConnectViewModel/onScanned/mainTypeAnalysis: types=${mainTypes.map { it.type }}, hasSubType=${mainTypes.any { it.type.startsWith("+") }}")
        val typeForMatch = if (mainTypes.isNotEmpty()) mainTypes.first() else null
        val isMatching = typeForMatch != null && isProtocolMatching(protocol, typeForMatch)
        appLogger.i("MegaBLEConnectViewModel/onScanned/protocolMatch:isMatching=$isMatching typeForMatch=$typeForMatch")
        onCompletion(isMatching)
      } catch (e: Exception) {
        appLogger.e("MegaBLEConnectViewModel/onScanned") { "error: ${e.message}\n${e.stackTraceToString()}" }
        emitToast("scanned error: $e")
      }
    }
  }

  fun protocolMatchSuccess(value: Boolean) {
    appLogger.i("MegaBLEConnectViewModel/protocolMatchSuccess:$value")
    isScanned = value
  }

  fun onWifiCredentialsSubmitted(name: String, password: String) {
    appLogger.i("MegaBLEConnectViewModel/onWifiCredentialsSubmitted/wifiName:$name")
    wifiName = name
    wifiPassword = password
    launchWithLoading {
      try {
        WiFiConnection = true
        val wifi = selectedWifi
          ?: wifiList.firstOrNull { it.ssid.equals(wifiName, ignoreCase = true) }
          ?: WifiNetwork(ssid = wifiName, rssi = -100, security = "WPA2PSK/AES")
        appLogger.i("MegaBLEConnectViewModel/onWifiCredentialsSubmitted/pair: ssid=${wifi.ssid}, wifiAuth=${wifi.wifiAuth}, encryptType=${wifi.encryptType}")
        val tx = transaction
        if (tx == null) {
          appLogger.e("MegaBLEConnectViewModel/onWifiCredentialsSubmitted/transaction is null")
          connectionWiFiFail = true
          WiFiConnection = false
          wifiFailMessage = "Pairing service not initialized. Please retry."
          return@launchWithLoading
        }
        ESIotPairingManager.pair(
          transaction = tx,
          wifi = wifi,
          password = wifiPassword,
          onSuccess = {
            connectionWiFiSuccess = true
            WiFiConnection = false
            appLogger.i("MegaBLEConnectViewModel/doPairing/onSuccess")
          },
          onFailure = { code, payload ->
            connectionWiFiFail = true
            WiFiConnection = false
            val failCode = code.trimCode()
            val payloadMsg = payload?.get("msg")?.toString() ?: ""
            appLogger.e("MegaBLEConnectViewModel/doPairing/onFailure code:$failCode msg:$payloadMsg payload:$payload")
            wifiFailMessage = when {
              failCode == 10 && payloadMsg.contains("timeout", ignoreCase = true) ->
                "Device activation timeout. Device may not be whitelisted. Please contact support to add device SN to the whitelist, then retry."
              failCode == 10 ->
                "Device activation failed (code=10). Device may not be whitelisted or not in pairing mode. Please: 1) Reset device to pairing mode 2) Contact support to verify device whitelist"
              failCode == 9 ->
                "WiFi connection failed (code=9). Please check: 1) WiFi password is correct 2) Device is within WiFi range 3) Router supports 2.4GHz"
              failCode == 3 ->
                "BLE connection timeout (code=3). Please: 1) Move closer to device 2) Reset device to pairing mode 3) Check Bluetooth is enabled"
              failCode == 0 ->
                "BLE scan failed (code=0). Device not found. Please: 1) Reset device to pairing mode 2) Check device LED is blinking 3) Move closer"
              failCode == 15 ->
                "Device is in wrong state (code=15). Please press and hold RESET button for 2 seconds until you hear a beep, then retry."
              payloadMsg.contains("timeout", ignoreCase = true) ->
                "Pairing timeout (code=$failCode). Please ensure: 1) Device is in pairing mode 2) WiFi credentials are correct 3) Device is within range"
              else ->
                "Pairing failed (code=$failCode, msg=$payloadMsg). Please retry or contact support."
            }
          }
        )
      } catch (e: Exception) {
        appLogger.e("MegaBLEConnectViewModel/onWifiCredentialsSubmitted") { "exception: ${e.message}\n${e.stackTraceToString()}" }
        emitToast("error: $e")
      }
    }
  }

  fun onWifiPicked(ssid: String) {
    wifiName = ssid
    selectedWifi = wifiList.firstOrNull { it.ssid == ssid }
    appLogger.i("MegaBLEConnectViewModel/onWifiPicked: ssid=$ssid, source=picker, security=${selectedWifi?.security}, wifiAuth=${selectedWifi?.wifiAuth}, encryptType=${selectedWifi?.encryptType}")
  }

  fun bluetoothConnect() {
    if (connection) {
      appLogger.w("MegaBLEConnectViewModel/bluetoothConnect/alreadyConnecting")
      emitToast("Connecting to device, please wait...")
      return
    }
    appLogger.i("MegaBLEConnectViewModel/bluetoothConnect/start type:${mainTypes.firstOrNull()} sn:$sn bleInputData:$bleInputData")
    launchWithLoading {
      try {
        val pn = sn.snToPn()
        appLogger.i("MegaBLEConnectViewModel/bluetoothConnect/pn=$pn, creating service via PairingServiceFactory")

        // 使用 PairingServiceFactory 创建配网服务（内部完成 mainTypes 匹配 + 复合 TypeConfig 拆解）
        val result = PairingServiceFactory.createPairingService(
          pn = pn,
          preferredType = "BLE",
          inputData = bleInputData
        )

        when (result) {
          is CreatePairingServiceResult.Success -> {
            transaction = result.service
            appLogger.i("MegaBLEConnectViewModel/bluetoothConnect/serviceCreated: type=${result.service}, sn=$sn, bleInputData=$bleInputData")
          }
          is CreatePairingServiceResult.TypeNotSupported -> {
            appLogger.e("MegaBLEConnectViewModel/bluetoothConnect/typeNotSupported: requestedType=${result.requestedType}, supportedTypes=${result.supportedTypes.map { it.type }}")
            connectionBLEFail = true
            wifiFailMessage = "This device does not support BLE pairing. Supported types: ${result.supportedTypes.map { it.type }}"
            return@launchWithLoading
          }
          is CreatePairingServiceResult.DescriptionUnavailable -> {
            appLogger.e("MegaBLEConnectViewModel/bluetoothConnect/descriptionUnavailable: pn=${result.pn}")
            connectionBLEFail = true
            wifiFailMessage = "Failed to get pairing configuration for pn=${result.pn}"
            return@launchWithLoading
          }
        }

        connection = true
        appLogger.i("MegaBLEConnectViewModel/bluetoothConnect/connecting: sn=$sn, bleInputData=$bleInputData")

        // 补 payload（对齐 mega RN IOTUtils.ts:869 connectDevice payload）
        val connectPayload = mapOf<String, Any>(
          "isChangeWifi" to false,
          "connectRetryCount" to 0
        )
        appLogger.i("MegaBLEConnectViewModel/bluetoothConnect/connectDevice: payload=$connectPayload")

        transaction?.connectDevice(
          payload = connectPayload,
          onSuccess = {
          connectionBLESuccess = true
          connection = false
          appLogger.i("MegaBLEConnectViewModel/bluetoothConnect/connectDeviceSuccess")

            wifiListLoading = true
            transaction?.let { tx ->
              ESIotPairingManager.getAvailableNetworks(
                transaction = tx,
                onResult = { list ->
                  wifiListLoading = false
                  wifiList = list
                  appLogger.i("MegaBLEConnectViewModel/getWiFiList/wifiListLoaded: count=${list.size}")
                  if (list.isNotEmpty()) {
                    val currentWifiName = list.firstOrNull()?.ssid ?: ""
                    if (currentWifiName.isNotEmpty()) {
                      wifiName = currentWifiName
                      appLogger.i("MegaBLEConnectViewModel/getWiFiList/autoFilledWifi:$currentWifiName")
                    }
                  }
                },
                onFailure = { code, payload ->
                  wifiListLoading = false
                  appLogger.w("MegaBLEConnectViewModel/getWiFiList/onFailure code:$code payload:$payload")
                }
              )
            }
          }, onFailure = { code, payload ->
          connectionBLEFail = true
          connection = false
          appLogger.e("MegaBLEConnectViewModel/bluetoothConnect/connectDeviceFailure code:$code payload:$payload")
        })
      } catch (e: Exception) {
        connection = false
        appLogger.e("MegaBLEConnectViewModel/bluetoothConnect") { "exception: ${e.message}\n${e.stackTraceToString()}" }
        emitToast("error: $e")
      }
    }
  }
}

data class MegaBLEConnectState(
  val wifiName: String = "",
  val wifiPassword: String = "",
  val wifiList: List<WifiNetwork> = emptyList(),
  val wifiListLoading: Boolean = false,
  val sn: String = "",
  val isScanned: Boolean = false,
  val transaction: SecurityDevicePairingService? = null,
  val connection: Boolean = false,
  val connectionBLESuccess: Boolean = false,
  val connectionBLEFail: Boolean = false,
  val reminderPage: Int = 0,
  val mainTypes: List<TypeConfig> = emptyList(),
  val connectionWiFiSuccess: Boolean = false,
  val connectionWiFiFail: Boolean = false,
  val wifiFailMessage: String = "",
  val WiFiConnection: Boolean = false,
  val name: String = " ",
  val addSuccess: Boolean = false
)