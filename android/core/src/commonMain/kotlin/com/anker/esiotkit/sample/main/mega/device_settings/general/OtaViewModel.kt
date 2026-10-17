package com.anker.esiotkit.sample.main.mega.device_settings.general

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anker.esiotkit.support.json.kJson
import com.anker.esiotkit.core.entities.ota.RomVersion
import com.anker.esiotkit.core.support.constants.ThingIdentifier
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.sample.base.BaseViewModel
import com.anker.esiotkit.core.iotmodel.default_service.OtaUpgradeCoordinator
import com.anker.esiotkit.core.iotmodel.default_service.OtaUpgradeDelegate
import com.anker.esiotkit.core.iotmodel.default_service.OtaUpgradeResult
import com.anker.esiotkit.core.iotmodel.default_service.OtaUpgradeStatus
import com.anker.esiotkit.sample.view.emitToast
import com.anker.esiotkit.sample.support.perf.RepLog
import com.anker.esiotkit.support.log.dataLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * @version 2025/8/11
 */
class OtaViewModel(private val megaDevice: MegaDevice) : BaseViewModel() {
  companion object {
    private const val TAG = "OtaViewModel"
  }

  var newVersionString by mutableStateOf("")
  var newVersion: RomVersion? by mutableStateOf(null)
  var isOtaing by mutableStateOf(false)
  var isOtaLoading by mutableStateOf(false)

  init {
    checkForUpdates()
    if (megaDevice.getProperty(ThingIdentifier.IS_DEVICE_UPGRADE)?.get()?.asBoolean() == true) {
      startMonitor()
    }
  }

  fun checkForUpdates() {
    launchWithLoading {
      megaDevice.getAction(ThingIdentifier.GET_ROM_VERSION)?.invoke()
        ?.await()
        ?.onSuccess {
          val update = it.asJson()
          newVersion = runCatching {
            kJson.decodeFromString<RomVersion>(update)
          }.getOrNull()
          dataLogger.i("OtaViewModel/checkForUpdates/newVersion:$newVersion")
          newVersionString = update
        }?.onFailure { result, payload ->
          emitToast("Failed to check for updates, code: ${result.code}")
        }
    }
  }

  fun startOta() {
    RepLog.i(TAG) { "startOta: TRIGGERED — device=${megaDevice.deviceSn}, targetVersion=${newVersion?.romVersion}" }
    coordinator?.cancel()
    coordinator = OtaUpgradeCoordinator(megaDevice)

    isOtaLoading = true
    RepLog.i(TAG) { "startOta: LOADING — sending upgrade command" }
    viewModelScope.launch {
      try {
        val sendResult = coordinator!!.sendCommand()
        isOtaLoading = false
        if (sendResult.status != OtaUpgradeStatus.SUCCESS) {
          RepLog.e(TAG) { "startOta: ABORTED — status=${sendResult.status}, code=${sendResult.errorCode}, msg=${sendResult.errorMessage}" }
          emitToast("Start OTA Failed: ${sendResult.errorMessage}")
          return@launch
        }
        RepLog.i(TAG) { "startOta: UPGRADING — command sent, starting monitor" }
        isOtaing = true
        startMonitor()
        coordinator!!.monitorCompletion(object : OtaUpgradeDelegate {
          override fun onProgress(stage: Int, progress: Int) {
            RepLog.d(TAG) { "onProgress: stage=$stage, progress=$progress%" }
          }
          override fun onCompleted(result: OtaUpgradeResult) {
            RepLog.i(TAG) { "onCompleted: status=${result.status}, pre=${result.preVersion}, post=${result.postVersion}" }
            isOtaing = false
            when (result.status) {
              OtaUpgradeStatus.SUCCESS -> emitToast("Upgrade successful: ${result.postVersion}")
              OtaUpgradeStatus.FAILED  -> emitToast("Upgrade failed: ${result.errorMessage}")
              OtaUpgradeStatus.TIMEOUT -> emitToast("Upgrade timeout")
              OtaUpgradeStatus.CANCELLED -> {}
            }
          }
        }, sendResult.preVersion)
      } catch (e: Exception) {
        RepLog.e(TAG) { "startOta: EXCEPTION — ${e.message}" }
        isOtaLoading = false
        emitToast("OTA upgrade error: ${e.message}")
      }
    }
  }

  private var monitorJob: Job? = null
  private fun startMonitor() {
    RepLog.i(TAG) { "startMonitor: polling every 5s for ThingProperty updates" }
    monitorJob?.cancel()
    monitorJob = viewModelScope.launch {
      while(isActive) {
        delay(5000)
        MegaDeviceManager.refreshDeviceList().await()
      }
    }
  }

  private var coordinator: OtaUpgradeCoordinator? = null
}