package com.anker.esiotkit.sample.main.mega.add_device.sub_device

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.core.constants.isStation
import com.anker.esiotkit.core.presentation.pairing.PairingDescriptionManager
import com.anker.esiotkit.core.support.hasSn
import com.anker.esiotkit.core.support.isSn
import com.anker.esiotkit.core.support.snToPn
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.mega.presentation.IotThingManager
import com.anker.esiotkit.net_api.response.thing.ProductConfig
import com.anker.esiotkit.net_api.response.thing.TypeConfig
import com.anker.esiotkit.network.model.ESResponse
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.main.mega.MegaMainViewModel
import com.anker.esiotkit.sample.support.isProtocolMatching
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.security_device.domain.repository.SecurityDeviceRepository
import com.anker.esiotkit.security_device.pair.SecurityDevicePairConfig
import com.anker.esiotkit.security_device.pair.service.SecuritySubDevicePairingService
import com.anker.esiotkit.support.di.esInject
import com.anker.esiotkit.support.ktx.filterIf
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.dataLogger

/**
 * @version 2024/9/13
 */
class MegaSubDeviceViewModel(
  private val repository: SecurityDeviceRepository = esInject(),
  private val megaMainViewModel: MegaMainViewModel
) : BaseScreenModel() {

  private var sn: String = ""
  private var protocol: String = ""
  private var isScanned by mutableStateOf(false)
  private var transaction: SecuritySubDevicePairingService? = null
  private var connection by mutableStateOf(false)
  private var connectionFail by mutableStateOf(false)
  private var mainTypes by mutableStateOf(emptyList<TypeConfig>())
  private var showDialog by mutableStateOf(false)
  private var connectDoorbell by mutableStateOf(false)
  private var deviceList by mutableStateOf(emptyList<MegaDevice>())
  private var homeBaseList by mutableStateOf(emptyList<MegaDevice>())
  private var homeBase by mutableStateOf<MegaDevice?>(null)
  private var connectionSuccess by mutableStateOf(false)
  private var deviceType by mutableStateOf(7)

  val uiState = screenModelScope.launchMolecule(RecompositionMode.Immediate) {
    MegaSubDeviceState(
      sn = sn,
      isScanned = isScanned,
      transaction = transaction,
      connection = connection,
      connectionFail = connectionFail,
      mainTypes = mainTypes,
      showDialog = showDialog,
      connectDoorbell = connectDoorbell,
      deviceList = deviceList,
      homeBaseList = homeBaseList,
      homeBase = homeBase,
      connectionSuccess = connectionSuccess,
      deviceType = deviceType
    )
  }

  init {
    deviceList = megaMainViewModel.uiState.value.deviceList
  }

  fun resetStates() {
    appLogger.i("MegaSubDeviceViewModel/resetStates")
    sn = ""
    protocol = ""
    isScanned = false
    transaction = null
    connection = false
    connectionFail = false
    mainTypes = emptyList()
    showDialog = false
    connectDoorbell = false
    homeBaseList = emptyList()
    homeBase = null
    connectionSuccess = false
  }

  fun changeShowDialog(value: Boolean) {
    showDialog = value
  }

  fun onScanned(code: String, protocol: String, onCompletion: (Boolean) -> Unit) {
    launchWithLoading {
      this.protocol = protocol
      appLogger.i("MegaSubDeviceViewModel/onScanned/code:$code protocol:$protocol")
      sn = when {
        isSn(code) -> code
        hasSn(code) -> code.substringBefore(",")
        else -> MegaDeviceManager.getDeviceSnBySetupCode(code).await()
      }
      dataLogger.i("sn11: $sn")
      dataLogger.i("pn111:${sn.snToPn()}")
      try {
        mainTypes = PairingDescriptionManager.getPairingDescription(sn.snToPn()).await()?.mainTypes.orEmpty()
        dataLogger.i("MegaSubDeviceViewModel/onScanned/mainTypes:$mainTypes")
        val matchedType = mainTypes.firstOrNull { isProtocolMatching(protocol, it) }
        appLogger.i("MegaSubDeviceViewModel/onScanned/matchedType:${matchedType?.type} protocol:$protocol")
        onCompletion(matchedType != null)
      } catch (e: Exception) {
        emitToast("scanned error: $e")
        appLogger.e("MegaSubDeviceViewModel/onScanned") { "error: ${e.message}" }
      }
    }
  }

  fun protocolMatchSuccess() {
    homeBaseList = filterDevicesByConditions(mainTypes, deviceList)
    appLogger.i("MegaSubDeviceViewModel/protocolMatchSuccess/homeBaseCount:${homeBaseList.size}")
    isScanned = true
  }

  fun onHomeBaseSelected(homeBase: MegaDevice) {
    appLogger.i("MegaSubDeviceViewModel/onHomeBaseSelected/homeBaseSn:${homeBase.deviceSn}")
    connectDoorbell = true
    this.homeBase = homeBase
  }

  fun connectDoorbell() {
    connection = true
    connectionFail = false
    val payload = mutableMapOf<String, Any>()

    launchWithLoading {
      try {
        val config: ESResponse<List<ProductConfig>> =
          IotThingManager.getProductConfig(listOf(sn.snToPn())).await()
        deviceType = config.data?.firstOrNull()?.deviceType?.toIntOrNull() ?: 7
        payload["deviceType"] = deviceType
        appLogger.i("MegaSubDeviceViewModel/connectDoorbell/sn:$sn pn:${sn.snToPn()} deviceType:$deviceType protocol:$protocol")

        val matchedType = mainTypes.firstOrNull { isProtocolMatching(protocol, it) } ?: mainTypes.first()
        appLogger.i("MegaSubDeviceViewModel/connectDoorbell/matchedType:${matchedType.type} isHomebasePair:${matchedType.type.startsWith("+")}")

        transaction = homeBase?.let { hb ->
          appLogger.i("MegaSubDeviceViewModel/connectDoorbell/initSecuritySubDevice/type:${matchedType.type} parentSn:${hb.deviceSn} inputData:$sn")
          SecuritySubDevicePairingService(
            type = matchedType, parentSn = hb.deviceSn, inputData = sn
          )
        }

        if (matchedType.type.startsWith("+")) {
          appLogger.i("MegaSubDeviceViewModel/connectDoorbell/startHomebasePairing/type:${matchedType.type}")
          payload["parentSn"] = homeBase?.deviceSn ?: ""
          payload["deviceSn"] = sn
          transaction?.getHubInfo(
            homeBase?.deviceSn ?: "", deviceType,
            onSuccess = { result ->
              appLogger.i("MegaSubDeviceViewModel/getHubInfo/onSuccess/result:$result")
              transaction?.doPairing(onSuccess = {
                connectionSuccess = true
                connection = false
                appLogger.i("MegaSubDeviceViewModel/doPairing/onSuccess/sn:$sn")
              }, onFailure = { code, failPayload ->
                connectionFail = true
                connection = false
                appLogger.e("MegaSubDeviceViewModel/doPairing/onFailure code:$code payload:$failPayload")
              }, payload)
            },
            onFailure = { code, failPayload ->
              connectionFail = true
              connection = false
              appLogger.e("MegaSubDeviceViewModel/getHubInfo/onFailure code:$code payload:$failPayload")
            }
          )
        } else {
          appLogger.i("MegaSubDeviceViewModel/connectDoorbell/startDirectPairing/type:${matchedType.type}")
          transaction?.doPairing(onSuccess = {
            connectionSuccess = true
            connection = false
            appLogger.i("MegaSubDeviceViewModel/doPairing/onSuccess/sn:$sn")
          }, onFailure = { code, failPayload ->
            connectionFail = true
            connection = false
            appLogger.e("MegaSubDeviceViewModel/doPairing/onFailure code:$code payload:$failPayload")
          }, payload)
        }
      } catch (e: Exception) {
        connection = false
        appLogger.e("MegaSubDeviceViewModel/connectDoorbell") { "exception: ${e.message}" }
      }
    }
  }

  private fun filterDevicesByConditions(
    mainTypes: List<TypeConfig>,
    deviceList: List<MegaDevice>
  ): List<MegaDevice> {
    // 提取所有的条件值
    val conditionValues = mutableSetOf<String>()
    for(typeConfig in mainTypes) {
      typeConfig.conditionList?.forEach { tdCondition ->
        for(specificCondition in tdCondition.conditions) {
          if (specificCondition.type == "device_sn" || specificCondition.type == "parent_sn") {
            conditionValues.add(specificCondition.value)
          }
        }
      }
    }
    // 筛选 deviceList 中 deviceSn 的前五位相匹配的 MegaDevice
    val result = deviceList.filter {
      it.deviceSn.isStation()
    }.filterIf(conditionValues.isNotEmpty()) { device ->
      conditionValues.any { conditionValue ->
        device.deviceSn.startsWith(conditionValue)
      }
    }
    appLogger.i("MegaSubDeviceViewModel/filterDevicesByConditions/conditionValues:$conditionValues matchedCount:${result.size}")
    return result
  }
}

data class MegaSubDeviceState(
  val sn: String = "",
  val isScanned: Boolean = false,
  val transaction: SecuritySubDevicePairingService? = null,
  val connection: Boolean = false,
  val connectionFail: Boolean = false,
  val mainTypes: List<TypeConfig> = emptyList(),
  val showDialog: Boolean = false,
  val connectDoorbell: Boolean = false,
  val deviceList: List<MegaDevice> = emptyList(),
  val homeBaseList: List<MegaDevice> = emptyList(),
  val homeBase: MegaDevice? = null,
  val connectionSuccess: Boolean = false,
  val deviceType: Int = 7
)