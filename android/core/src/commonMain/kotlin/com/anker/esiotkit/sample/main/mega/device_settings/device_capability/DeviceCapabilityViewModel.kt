package com.anker.esiotkit.sample.main.mega.device_settings.device_capability

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.net_api.response.thing.TDAction
import com.anker.esiotkit.net_api.response.thing.TDEvent
import com.anker.esiotkit.net_api.response.thing.TDProperty
import com.anker.esiotkit.sample.base.BaseScreenModel

data class CapabilityData(
    val properties: List<TDProperty>,
    val actions: List<TDAction>,
    val events: List<TDEvent>,
)

class DeviceCapabilityViewModel(
    private val deviceManager: ESIotDeviceManager,
) : BaseScreenModel() {

    val deviceList get() = MegaDeviceManager.deviceList.value
        .map { it.deviceSn }
        .distinct()

    var selectedSn by mutableStateOf(deviceList.firstOrNull() ?: "")
        private set

    var capabilityData by mutableStateOf<CapabilityData?>(null)
        private set

    fun selectDevice(sn: String) {
        selectedSn = sn
        capabilityData = null
    }

    fun query() {
        val sn = selectedSn.ifBlank { return }
        val api = deviceManager.capability(sn)
        capabilityData = CapabilityData(
            properties = api.getProperties(),
            actions = api.getActions(),
            events = api.getEvents(),
        )
    }
}
