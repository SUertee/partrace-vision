package com.anker.esiotkit.sample.main.mega.add_device.scan_qrcode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.core.constants.ESResult
import com.anker.esiotkit.core.presentation.pairing.PairingDescriptionManager
import com.anker.esiotkit.core.support.hasSn
import com.anker.esiotkit.core.support.isSn
import com.anker.esiotkit.core.support.snToPn
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.core.device.getDevicePropertyFlow
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.net_api.response.thing.TypeConfig
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.sample.support.isProtocolMatching
import com.anker.esiotkit.sample.support.platform.getCurrentWifiSsid
import com.anker.esiotkit.sample.support.platform.generateQrCode
import com.anker.esiotkit.sample.support.log.SampleLogger
import com.anker.esiotkit.sample.support.log.PairingLogEntry
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.security_device.domain.repository.SecurityDeviceRepository
import com.anker.esiotkit.security_device.pair.service.SecurityDevicePairingService
import com.anker.esiotkit.domain.add_device.pairing.PairingTypeResolver
import com.anker.esiotkit.domain.add_device.security_qr.ESIotSecurityQrPairingManager
import com.anker.esiotkit.support.IoDispatcher
import com.anker.esiotkit.support.di.esInject
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.dataLogger
import com.anker.esiotkit.support.platform.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * @version 2024/9/11
 */
class MegaScanQrCodeViewModel(
  private val repository: SecurityDeviceRepository = esInject()
) : BaseScreenModel() {
  private var wifiName by mutableStateOf(InfoCache.pairingSsid.trim())
  private var wifiPassword by mutableStateOf(InfoCache.pairingPassword)
  private var sn: String = ""
  private var isScanned by mutableStateOf(false)
  private var qrCodeString by mutableStateOf("")
  private var imageByteArray by mutableStateOf<ByteArray?>(null)
  private var transaction: SecurityDevicePairingService? = null
  private var connection by mutableStateOf(false)
  private var connectionSuccess by mutableStateOf(false)
  private var connectionFailure by mutableStateOf(false)
  private var failureCode by mutableStateOf<ESResult?>(null)
  private var failurePayload by mutableStateOf<Any?>(null)
  private var connectType by mutableStateOf(-1)
  private var mainTypes by mutableStateOf(emptyList<TypeConfig>())
  // Bug #10679: 换网场景标识。非 null 表示从 WiFiSignalScreen 进入的换网流程（走门面 API）。
  // generateAndShowQrCode(wifiName: String?) 参数遮蔽成员变量 wifiName，需用独立变量记录。
  private var wifiNameFromChangeWifi: String? = null
  private val typeConfig
    get() = when (connectType) {
      0 -> mainTypes.find { !it.type.startsWith("+") }
      1 -> mainTypes.find { it.type.startsWith("+") }
      else -> null
    }

  private val timelineTracker = PairingTimelineTracker()
  private val _timelineNodes = MutableStateFlow<List<PairingTimelineNode>>(emptyList())
  val timelineNodes: StateFlow<List<PairingTimelineNode>> = _timelineNodes.asStateFlow()

  init {
    screenModelScope.launch {
      SampleLogger.pairingLogs.collect { entry ->
        // 时间轴为纯 UI 观测：任何解析异常静默吞掉，绝不向 screenModelScope 上抛影响配网业务
        runCatching {
          timelineTracker.onLog(entry, sn)
          _timelineNodes.value = timelineTracker.snapshot()
        }
      }
    }
  }

  val uiState = screenModelScope.launchMolecule(RecompositionMode.Immediate) {
    val nodes by timelineNodes.collectAsState()
    MegaQrCodeState(
      wifiName = wifiName,
      wifiPassword = wifiPassword,
      sn = sn,
      isScanned = isScanned,
      qrCodeString = qrCodeString,
      imageByteArray = imageByteArray,
      transaction = transaction,
      connection = connection,
      connectionSuccess = connectionSuccess,
      connectionFailure = connectionFailure,
      failureCode = failureCode,
      failurePayload = failurePayload,
      connectType = connectType,
      mainTypes = mainTypes,
      timelineNodes = nodes
    )
  }

  fun resetStates() {
    timelineTracker.reset()
    _timelineNodes.value = emptyList()
    sn = ""
    isScanned = false
    qrCodeString = ""
    imageByteArray = null
    transaction = null
    connection = false
    connectionSuccess = false
    connectionFailure = false
    failureCode = null
    failurePayload = null
    connectType = -1
    mainTypes = emptyList()
    wifiNameFromChangeWifi = null
  }

  fun choiceConnectType(value: Int) {
    connectType = value
    dataLogger.i("MegaScanQrCodeViewModel/choiceConnectType/connectType:$value, typeConfig:${typeConfig?.type}, mainTypes:${mainTypes.map { it.type }}")
  }

  fun onScanned(code: String) {
    launchWithLoading {
      dataLogger.i("MegaScanQrCodeViewModel/onScanned/code: $code")
      val codeType = when {
        isSn(code) -> "directSN"
        hasSn(code) -> "hasSN"
        else -> "setupCode"
      }
      if (codeType == "setupCode") {
        val lookupStartMs = currentTimeMillis()
        dataLogger.i("MegaScanQrCodeViewModel/onScanned/setupCodeLookup/start: codeLength=${code.length}, codeType=$codeType")
        sn = withTimeoutOrNull(10_000L) {
          MegaDeviceManager.getDeviceSnBySetupCode(code).await()
        } ?: ""
        if (sn.isBlank()) {
          dataLogger.w("MegaScanQrCodeViewModel/onScanned/setupCodeLookup/empty: snBlank=true, elapsedMs=${currentTimeMillis() - lookupStartMs}, reason=cloud-lookup-failed-or-timeout, codeType=$codeType")
        }
      } else {
        sn = when {
          isSn(code) -> code
          else -> code.substringBefore(",")
        }
      }
      if (sn.isBlank()) {
        dataLogger.e("MegaScanQrCodeViewModel/onScanned/rejected: sn is blank, codeType=$codeType, code=$code")
        emitToast("二维码无效或设备未就绪，请长按设备配网键重置后重新扫码")
        return@launchWithLoading
      }
      val pn = sn.snToPn()
      dataLogger.i("MegaScanQrCodeViewModel/onScanned/sn:$sn, pn:$pn, codeType:$codeType")

      // 自动获取当前手机连接的 WiFi 名称
      try {
        val currentSsid = getCurrentWifiSsid()
        appLogger.i("MegaScanQrCodeViewModel/getCurrentWifiSsid result: '$currentSsid'")
        if (currentSsid.isNotEmpty()) {
          wifiName = currentSsid
          appLogger.i("MegaScanQrCodeViewModel/auto-filled current WiFi: $currentSsid")
          dataLogger.i("MegaScanQrCodeViewModel/WiFi auto-filled successfully")
        } else {
          // 如果获取失败，使用上次缓存的 WiFi 名称
          appLogger.i("MegaScanQrCodeViewModel/using cached WiFi: ${InfoCache.pairingSsid}")
          dataLogger.i("MegaScanQrCodeViewModel/current WiFi is empty, using cache: wifiName='$wifiName', wifiNameLength=${wifiName.length}, hasLeadingOrTrailingSpace=${wifiName != wifiName.trim()}")
        }
      } catch (e: Exception) {
        appLogger.e("MegaScanQrCodeViewModel") { "Failed to get WiFi SSID: ${e.message}" }
        dataLogger.e("MegaScanQrCodeViewModel/WiFi auto-fill failed: $e")
      }

      isScanned = true
    }
  }

  fun onWifiNameChange(newName: String) {
    wifiName = newName
  }

  fun onWifiPasswordChange(newPassword: String) {
    wifiPassword = newPassword
  }

  fun onWifiCredentialsSubmitted(
    name: String,
    password: String,
    protocol: String,
    onCompletion: (Boolean) -> Unit
  ) {
    launchWithLoading(context = IoDispatcher) {
      try {
        wifiName = name.trim()
        wifiPassword = password
        val pn = sn.snToPn()
        dataLogger.i("MegaScanQrCodeViewModel/onWifiCredentialsSubmitted/sn:$sn, pn:$pn, wifiName=$wifiName")

        val pairingDescription = withTimeoutOrNull(15_000L) {
          PairingDescriptionManager.getPairingDescription(pn).await()
        }
        if (pairingDescription == null) {
          dataLogger.e("MegaScanQrCodeViewModel/onWifiCredentialsSubmitted/pairingDescriptionTimeout: pn=$pn, timeoutMs=15000, fallback=null-then-no-config-branch")
        }
        dataLogger.i("MegaScanQrCodeViewModel/onWifiCredentialsSubmitted/pairingDescription:$pairingDescription")

        mainTypes = pairingDescription?.mainTypes.orEmpty()
        dataLogger.i("MegaScanQrCodeViewModel/onWifiCredentialsSubmitted/mainTypes:$mainTypes, connectType:$connectType")

        // 尝试获取配置，优先使用 typeConfig，否则使用 mainTypes 中的第一个
        var config = typeConfig
        dataLogger.i("MegaScanQrCodeViewModel/onWifiCredentialsSubmitted/typeConfig:$config")

        if (config == null && mainTypes.isNotEmpty()) {
          // 如果 typeConfig 为 null，尝试使用第一个可用的配置
          config = mainTypes.firstOrNull { !it.type.startsWith("+") } ?: mainTypes.firstOrNull()
          dataLogger.i("MegaScanQrCodeViewModel/onWifiCredentialsSubmitted/fallback config:$config")
        }

        if (config != null) {
          // 跳过协议匹配检查，直接使用配置（因为 mega APP 能成功配网）
          val isMatching = isProtocolMatching(protocol, config)
          dataLogger.i("MegaScanQrCodeViewModel/onWifiCredentialsSubmitted/isMatching:$isMatching, but proceeding anyway")
          // 即使不匹配也继续，让用户尝试配网
          onCompletion(true)
        } else {
          dataLogger.e("MegaScanQrCodeViewModel/onWifiCredentialsSubmitted/no config available, cause=pairingDescription-null-or-empty, sn=$sn, pn=$pn")
          emitToast("Failed to get pairing configuration for this device")
          onCompletion(false)
        }
      } catch (e: Exception) {
        dataLogger.e("MegaScanQrCodeViewModel/onWifiCredentialsSubmitted/error:$e")
        emitToast("error: $e")
        appLogger.e("onWifiCredentialsSubmitted error: $e")
        onCompletion(false)
      }
    }
  }

  fun generateAndShowQrCode(
    wifiName: String? = null,
    wifiPassword: String? = null,
    deviceSn: String? = null,
  ) {
    launchWithLoading {
      if (wifiName != null && wifiPassword != null && deviceSn != null) {
        try {
          val megaDevice = MegaDeviceManager.getDeviceBy(deviceSn) ?: return@launchWithLoading
          sn = deviceSn
          wifiNameFromChangeWifi = wifiName
          dataLogger.i("MegaScanQrCodeViewModel/generateAndShowQrCode/wifiChange: sn=$deviceSn, pn=${megaDevice.devicePn}, wifiName='$wifiName', wifiNameLength=${wifiName.length}, passwordLength=${wifiPassword.length}")
          // Bug #10679: 必须传 SN。外部库 checkChangeWifiResult 以 setup 首段作为 foundSn 与广播 SN 精确比对
          // （DevicePairByP2PActions.checkChangeWIfiResultByBroadcast: deviceSn == foundSn），
          // 传 PN 时该比对恒不成立；门面广播监听同样按 SN 精确匹配。
          ESIotSecurityQrPairingManager.prepareQrCode(
            pnOrSn = deviceSn,
            ssid = wifiName,
            password = wifiPassword,
            isChangeWifi = true,
            onQrReady = { qrString ->
              qrCodeString = qrString
              InfoCache.pairingSsid = wifiName.trim()
              InfoCache.pairingPassword = wifiPassword
              imageByteArray = generateQrCodeAsPngBytes(qrCodeString, 512)
              dataLogger.i("MegaScanQrCodeViewModel/generateAndShowQrCode/qrReady: sn=$deviceSn, qrCodeStringLength=${qrCodeString.length}, imageSize=${imageByteArray?.size}")
            },
            onFailure = { code, payload ->
              dataLogger.e("MegaScanQrCodeViewModel/generateAndShowQrCode/prepareQrCode/failed: sn=$deviceSn, code=$code, payload=$payload")
              emitToast("Failed to generate QR code: $code")
            }
          )
        } catch (e: Exception) {
          emitToast("error: $e")
          appLogger.i("error: $e")
        }
      } else {
        try {
          dataLogger.i("MegaScanQrCodeViewModel/generateAndShowQrCode/connectType:$connectType, mainTypes:$mainTypes")

          // 尝试获取配置，优先使用 typeConfig，否则使用 mainTypes 中的第一个
          var config = typeConfig
          dataLogger.i("MegaScanQrCodeViewModel/generateAndShowQrCode/typeConfig:$config")

          if (config == null && mainTypes.isNotEmpty()) {
            config = mainTypes.firstOrNull { !it.type.startsWith("+") } ?: mainTypes.firstOrNull()
            dataLogger.i("MegaScanQrCodeViewModel/generateAndShowQrCode/fallback config:$config")
          }

          if (config == null) {
            dataLogger.e("MegaScanQrCodeViewModel/generateAndShowQrCode/no config available")
            emitToast("Failed to get pairing config. Please try again.")
            return@launchWithLoading
          }

          // 拆解复合 TypeConfig（如 BLE+BLE+BLE/2.0+1.0+2.0 → BLE/2.0）供底层精确匹配
          val resolvedConfig = PairingTypeResolver.resolveToSingleValue(config)
          dataLogger.i("MegaScanQrCodeViewModel/generateAndShowQrCode/resolvedConfig: type=${resolvedConfig.type}, protocol=${resolvedConfig.protocol}")

          transaction = SecurityDevicePairingService(type = resolvedConfig, inputData = sn)
          dataLogger.i("MegaScanQrCodeViewModel/generateAndShowQrCode/transaction created: $transaction")

          dataLogger.i("MegaScanQrCodeViewModel/generateAndShowQrCode/credentials: sn=$sn, wifiName='${this.wifiName}', wifiNameLength=${this.wifiName.length}, hasLeadingOrTrailingSpace=${this.wifiName != this.wifiName.trim()}, passwordLength=${this.wifiPassword.length}")
          qrCodeString = transaction?.getQrCodeString(this.wifiName.trim(), this.wifiPassword)?.firstOrNull() ?: ""
          if (qrCodeString.isEmpty()) {
            dataLogger.e("MegaScanQrCodeViewModel/generateAndShowQrCode/qrCodeString is empty")
            emitToast("Failed to generate QR code. Please try again.")
            return@launchWithLoading
          }

          InfoCache.pairingSsid = this.wifiName.trim()
          InfoCache.pairingPassword = this.wifiPassword
          dataLogger.v("MegaScanQrCodeViewModel/generateAndShowQrCode/qrCodeString:$qrCodeString")
          dataLogger.v("MegaScanQrCodeViewModel/qrCodeString length: ${qrCodeString.length}")
          // 使用更大的尺寸（512）和高纠错等级提升识别率
          imageByteArray = generateQrCodeAsPngBytes(qrCodeString, 512)
          dataLogger.v("MegaScanQrCodeViewModel/generateAndShowQrCode/imageSize:${imageByteArray?.size}")
        } catch (e: Exception) {
          dataLogger.e("MegaScanQrCodeViewModel/generateAndShowQrCode/error:$e")
          emitToast("error: $e")
          appLogger.i("error: $e")
        }
      }
    }
  }

  fun performPairing() {
    timelineTracker.resetForNewAttempt()
    _timelineNodes.value = timelineTracker.snapshot()
    dataLogger.i("MegaScanQrCodeViewModel/performPairing/transaction:$transaction, wifiNameFromChangeWifi=$wifiNameFromChangeWifi")

    if (wifiNameFromChangeWifi != null) {
      dataLogger.i("MegaScanQrCodeViewModel/performPairing/wifiChange: sn=$sn, pn=${sn.snToPn()}, targetSsid=$wifiNameFromChangeWifi")
      connection = true
      ESIotSecurityQrPairingManager.startPairing(
        onSuccess = { payload ->
          val deviceSn = payload?.get("deviceSn") as? String ?: sn
          dataLogger.i("MegaScanQrCodeViewModel/performPairing/success: source=facade, sn=$deviceSn")
          connectionSuccess = true
          connection = false
        },
        onFailure = { code, payload ->
          dataLogger.e("MegaScanQrCodeViewModel/performPairing/failed: source=facade, sn=$sn, code=$code, payload=$payload")
          failureCode = code
          failurePayload = payload
          connectionFailure = true
          connection = false
        },
        onStage = { stage ->
          dataLogger.i("MegaScanQrCodeViewModel/performPairing/stage: stage=$stage, sn=$sn")
        }
      )
      return
    }

    if (transaction == null) {
      emitToast("Pairing service not initialized. Please go back and try again.")
      appLogger.e("performPairing: transaction is null")
      return
    }
    launchWithLoading {
      try {
        val service = transaction!!
        connection = true
        val pairingStartMs = currentTimeMillis()
        dataLogger.i("MegaScanQrCodeViewModel/performPairing/start: sn=$sn, pn=${sn.snToPn()}, pairingTimeout=120000ms")
        var monitorDetected = false

        // 启动设备在线状态监控（应对 WiFi 切换后 P2P 重连）
        val monitorJob = launch(IoDispatcher) {
          val monitorStartMs = currentTimeMillis()
          dataLogger.i("MegaScanQrCodeViewModel/monitorReconnect/start: sn=$sn, watching=DEVICE_IS_ONLINE, timeout=120000ms")
          val onlineValue = withTimeoutOrNull(120_000) {
            MegaDeviceManager.getDevicePropertyFlow(sn, ThingIdentifier.DEVICE_IS_ONLINE)
              .first { it.asBoolean() }
          }
          val monitorElapsedMs = currentTimeMillis() - monitorStartMs
          if (onlineValue == null) {
            // 修复：超时未检测到设备上线时不再置 connectionSuccess，避免掩盖 doPairing 的真实失败
            dataLogger.w("MegaScanQrCodeViewModel/monitorReconnect/timeout: device NOT online within 120000ms, sn=$sn, no success will be set")
            return@launch
          }
          dataLogger.i("MegaScanQrCodeViewModel/monitorReconnect/device online detected: sn=$sn, elapsedMs=$monitorElapsedMs")
          if (!connectionSuccess && !connectionFailure) {
            monitorDetected = true
            connectionSuccess = true
            connection = false
            dataLogger.i("MegaScanQrCodeViewModel/monitorReconnect/success via device online: sn=$sn, elapsedMs=$monitorElapsedMs")
          } else {
            dataLogger.i("MegaScanQrCodeViewModel/monitorReconnect/skipped: already resolved, sn=$sn, connectionSuccess=$connectionSuccess, connectionFailure=$connectionFailure")
          }
        }

        service.doPairing(onSuccess = {
          val elapsedMs = currentTimeMillis() - pairingStartMs
          dataLogger.i("MegaScanQrCodeViewModel/performPairing/success: source=doPairing callback, sn=$sn, elapsedMs=$elapsedMs")
          monitorJob.cancel()
          connectionSuccess = true
          connection = false
        }, onFailure = { code, payload ->
          val elapsedMs = currentTimeMillis() - pairingStartMs
          dataLogger.e("MegaScanQrCodeViewModel/performPairing/failed code:$code, payload:$payload, sn=$sn, monitorDetected=$monitorDetected, elapsedMs=$elapsedMs")
          appLogger.i("Pairing failed with code: $code and payload: $payload")
          if (!monitorDetected) {
            failureCode = code
            failurePayload = payload
            connectionFailure = true
            connection = false
            dataLogger.i("MegaScanQrCodeViewModel/performPairing/finalState: result=FAILURE, source=doPairing, sn=$sn, code=$code")
            launch(IoDispatcher) {
              dataLogger.i("MegaScanQrCodeViewModel/postFailureMonitor/start: sn=$sn, watching=DEVICE_IS_ONLINE, timeout=120000ms")
              val lateOnline = withTimeoutOrNull(120_000) {
                MegaDeviceManager.getDevicePropertyFlow(sn, ThingIdentifier.DEVICE_IS_ONLINE)
                  .first { it.asBoolean() }
              }
              if (lateOnline != null) {
                dataLogger.w("MegaScanQrCodeViewModel/postFailureMonitor/device came online AFTER pairing failure: sn=$sn")
              } else {
                dataLogger.i("MegaScanQrCodeViewModel/postFailureMonitor/device still offline within 120000ms after failure: sn=$sn")
              }
            }
          } else {
            dataLogger.w("MegaScanQrCodeViewModel/performPairing/onFailure suppressed by monitorReconnect: sn=$sn, code=$code, payload=$payload, UI will show SUCCESS")
          }
        })
      } catch (e: Exception) {
        connection = false
        emitToast("Pairing error: ${e.message}")
        appLogger.e("performPairing error: $e")
      }
    }
  }

  private fun generateQrCodeAsPngBytes(text: String, size: Int): ByteArray {
    // 使用 ZXing 库生成二维码（Android），qrcode-kotlin（iOS）
    // Version 3 = 29x29 模块，低纠错级别
    return generateQrCode(text, size, version = 3)
  }
}

data class MegaQrCodeState(
  val wifiName: String = InfoCache.pairingSsid,
  val wifiPassword: String = InfoCache.pairingPassword,
  val sn: String = "",
  val isScanned: Boolean = false,
  val qrCodeString: String = "",
  val imageByteArray: ByteArray? = null,
  val transaction: SecurityDevicePairingService? = null,
  val connection: Boolean = false,
  val connectionSuccess: Boolean = false,
  val connectionFailure: Boolean = false,
  val failureCode: ESResult? = null,
  val failurePayload: Any? = null,
  val connectType: Int = -1,
  val mainTypes: List<TypeConfig> = emptyList(),
  val timelineNodes: List<PairingTimelineNode> = emptyList()
)