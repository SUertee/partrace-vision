package com.anker.esiotkit.sample.main.mega.thing

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.house.presenter.MegaHouseManager
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.main.mega.thing.mode.Thing
import com.anker.esiotkit.sample.main.mega.thing.mode.ThingType
import com.anker.esiotkit.support.log.dataLogger

/**
 * @version 2024/11/1
 */

class MegaThingViewModel : BaseScreenModel() {
  private var searchQuery by mutableStateOf("")
  private var thingQuery by mutableStateOf("")

  private var _selectedDevice by mutableStateOf<MegaDevice?>(null)
  val selectedDevice: MegaDevice?
    get() = _selectedDevice

  fun selectDevice(device: MegaDevice) {
    _selectedDevice = device
  }

  val uiState = screenModelScope.launchMolecule(RecompositionMode.Immediate) {
    val houseDeviceList by MegaHouseManager.houseDeviceList.collectAsState()
    dataLogger.d("MegaThingViewModel/houseDeviceList/size:${houseDeviceList.size}")

    MegaThingUiState(
      loading = loading,
      deviceList = sortDeviceList(houseDeviceList),
      searchQuery = searchQuery,
      thingQuery = thingQuery
    )
  }

  private fun sortDeviceList(deviceList: List<MegaDevice>): List<MegaDevice> {
    val result = mutableListOf<MegaDevice>()
    deviceList.filter { it.deviceSn == it.parentSn }.forEach { parent ->
      result.add(parent)
      deviceList.filter { it.parentSn == parent.deviceSn && it.deviceSn != parent.deviceSn }.forEach {
        result.add(it)
      }
    }
    return result
  }

  init {
    MegaHouseManager.refreshHouseList()
  }


  fun getThingByDevice(device: MegaDevice):List<ThingType> {
    val thingTypeList = mutableListOf<ThingType>()
    val description = device.getThingDescription()
    description?.let {
      it.actionMap.let { actions ->
        thingTypeList.add(ThingType("action", actions.keys.map { key -> Thing(key) }))
      }
      it.propMap.let { properties ->
        thingTypeList.add(ThingType("property", properties.keys.map { key ->  Thing(key) }))
      }
      it.eventMap.let { events ->
        thingTypeList.add(ThingType("event", events.keys.map { key -> Thing(key) }))
      }
    }
    return thingTypeList
  }

  fun updateSearchQuery(query: String) {
    searchQuery = query
  }

  fun updateThingQuery(query: String) {
    thingQuery = query
  }

}

data class MegaThingUiState(
  var deviceList: List<MegaDevice>,
  var searchQuery: String,
  var thingQuery: String,
  val loading:Boolean
)

