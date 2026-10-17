package com.anker.esiotkit.sample.main.mega.device_settings.package_detection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.support.log.appLogger

class PackageDetectionViewModel(
    private val deviceSn: String,
    private val deviceManager: ESIotDeviceManager
) : ScreenModel {

    private val api = deviceManager.packageDetection(deviceSn)

    val isSupportPackageDetection: Boolean = api.isSupportPackageDetection()
    val isSupportPackageNotification: Boolean = api.isSupportPackageNotification()
    val isSupportPackageRecording: Boolean = api.isSupportPackageRecording()
    val isSupportPackageGuard: Boolean = api.isSupportPackageGuard()
    val isSupportUncollectedAlert: Boolean = api.isSupportUncollectedAlert()
    val isSupportLiveCheck: Boolean = api.isSupportLiveCheck()

    var isEnabled by mutableStateOf(api.getDeliveryGuardEnabled())
        private set
    var notificationEnabled by mutableStateOf(api.getNotificationEnabled())
        private set
    var recordingEnabled by mutableStateOf(api.getRecordingEnabled())
        private set
    var uncollectedAlertEnabled by mutableStateOf(api.getUncollectedAlertEnabled())
        private set
    var liveCheckEnabled by mutableStateOf(api.getLiveCheckEnabled())
        private set

    suspend fun toggleNotification(enabled: Boolean): Boolean {
        appLogger.i("PackageDetectionViewModel/toggleNotification/start: enabled=$enabled, sn=$deviceSn")
        val result = api.setNotificationEnabled(enabled)
        if (result.isSuccess) {
            notificationEnabled = enabled
            appLogger.i("PackageDetectionViewModel/toggleNotification/success: enabled=$enabled, sn=$deviceSn")
        }
        return result.isSuccess
    }

    suspend fun toggleRecording(enabled: Boolean): Boolean {
        appLogger.i("PackageDetectionViewModel/toggleRecording/start: enabled=$enabled, sn=$deviceSn")
        val result = api.setRecordingEnabled(enabled)
        if (result.isSuccess) {
            recordingEnabled = enabled
            appLogger.i("PackageDetectionViewModel/toggleRecording/success: enabled=$enabled, sn=$deviceSn")
        }
        return result.isSuccess
    }

    suspend fun toggleUncollectedAlert(enabled: Boolean): Boolean {
        appLogger.i("PackageDetectionViewModel/toggleUncollectedAlert/start: enabled=$enabled, sn=$deviceSn")
        val result = api.setUncollectedAlertEnabled(enabled)
        if (result.isSuccess) {
            uncollectedAlertEnabled = enabled
            appLogger.i("PackageDetectionViewModel/toggleUncollectedAlert/success: enabled=$enabled, sn=$deviceSn")
        }
        return result.isSuccess
    }

    suspend fun toggleLiveCheck(enabled: Boolean): Boolean {
        appLogger.i("PackageDetectionViewModel/toggleLiveCheck/start: enabled=$enabled, sn=$deviceSn")
        val result = api.setLiveCheckEnabled(enabled)
        if (result.isSuccess) {
            liveCheckEnabled = enabled
            appLogger.i("PackageDetectionViewModel/toggleLiveCheck/success: enabled=$enabled, sn=$deviceSn")
        }
        return result.isSuccess
    }
}
