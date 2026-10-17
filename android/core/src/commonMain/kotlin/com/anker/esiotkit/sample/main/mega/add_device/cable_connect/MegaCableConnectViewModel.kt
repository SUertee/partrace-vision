package com.anker.esiotkit.sample.main.mega.add_device.cable_connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.core.support.hasSn
import com.anker.esiotkit.core.support.isSn
import com.anker.esiotkit.core.support.snToPn
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.core.presentation.pairing.PairingDescriptionManager
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
 * @version 2024/11/06
 */
class MegaCableConnectViewModel(
  private val repository: SecurityDeviceRepository = esInject()
) : BaseScreenModel() {

  private var sn: String = ""
  private var preScanned by mutableStateOf(false)
  private var isScanned by mutableStateOf(false)
  private var transaction: SecurityDevicePairingService? = null
  private var connectionSuccess by mutableStateOf(false)
  private var connectionFail by mutableStateOf(false)
  private var mainTypes by mutableStateOf(emptyList<TypeConfig>())

  val uiState = screenModelScope.launchMolecule(RecompositionMode.Immediate) {
    MegaCableConnectState(
      sn = sn,
      preScanned = preScanned,
      isScanned = isScanned,
      transaction = transaction,
      connectionSuccess = connectionSuccess,
      connectionFail = connectionFail,
      mainTypes = mainTypes,
    )
  }

  fun resetStates() {
    dataLogger.i("MegaCableConnectViewModel/resetStates")
    sn = ""
    preScanned = false
    isScanned = false
    transaction = null
    connectionSuccess = false
    connectionFail = false
    mainTypes = emptyList()
  }

  fun preScanQRCode(value: Boolean) {
    dataLogger.i("MegaCableConnectViewModel/preScanQRCode/value:$value")
    resetStates()
    preScanned = value
  }

  fun onScanned(code: String, protocol: String, onCompletion: (Boolean) -> Unit) {
    launchWithLoading {
      try {
        dataLogger.i("MegaCableConnectViewModel/onScanned/code:$code, protocol:$protocol")
        val codeType = when {
          isSn(code) -> "directSN"
          hasSn(code) -> "hasSN"
          else -> "setupCode"
        }
        sn = when {
          isSn(code) -> code
          hasSn(code) -> code.substringBefore(",")
          else -> MegaDeviceManager.getDeviceSnBySetupCode(code).await()
        }
        val pn = sn.snToPn()
        dataLogger.i("MegaCableConnectViewModel/onScanned/sn:$sn, pn:$pn, codeType:$codeType")
        mainTypes = PairingDescriptionManager.getPairingDescription(pn).await()?.mainTypes.orEmpty()
        dataLogger.i("MegaCableConnectViewModel/onScanned/mainTypes:${mainTypes.map { it.type }}")
        onCompletion(isProtocolMatching(protocol, mainTypes.first()))
      } catch (e: Exception) {
        dataLogger.e("MegaCableConnectViewModel/onScanned/error:$e")
        emitToast("scanned error: $e")
        appLogger.e("MegaCableConnectViewModel/onScanned/error: $e")
      }
    }
  }

  fun performPairing() {
    dataLogger.i("MegaCableConnectViewModel/performPairing/sn:$sn, mainTypes:${mainTypes.map { it.type }}")
    launchWithLoading {
      try {
        transaction = SecurityDevicePairingService(type = mainTypes.first(), inputData = sn)
        dataLogger.i("MegaCableConnectViewModel/performPairing/transaction created, type:${mainTypes.first()}, sn:$sn")
        isScanned = true
        dataLogger.i("MegaCableConnectViewModel/performPairing/starting doPairing")
        transaction?.doPairing(onSuccess = {
          dataLogger.i("MegaCableConnectViewModel/performPairing/success, sn:$sn")
          connectionSuccess = true
          isScanned = false
          appLogger.i("performPairing onSuccess, sn:$sn")
        }, onFailure = { code, payload ->
          dataLogger.e("MegaCableConnectViewModel/performPairing/failed, sn:$sn, code:$code, payload:$payload")
          connectionFail = true
          isScanned = false
          appLogger.i("performPairing onFailure, sn:$sn, code:$code, payload:$payload")
        })
      } catch (e: Exception) {
        dataLogger.e("MegaCableConnectViewModel/performPairing/error:$e")
        emitToast("performPairing error: $e")
        appLogger.e("performPairing error: $e")
      }
    }
  }


}

data class MegaCableConnectState(
  val sn: String,
  val preScanned: Boolean,
  val isScanned: Boolean,
  val transaction: SecurityDevicePairingService?,
  val connectionSuccess: Boolean,
  val connectionFail: Boolean,
  val mainTypes: List<TypeConfig> = emptyList(),
)
