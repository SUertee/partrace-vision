package com.anker.esiotkit.sample.main.mega.add_device.device_family

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.support.json.kJson
import com.anker.esiotkit.data_center.presentation.GetProductCategoryListManager
import com.anker.esiotkit.data_center.presentation.GetProductCategoryListManager.getCategoryIconBy
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.domain_thing.model.thing.ThingDescription
import com.anker.esiotkit.mega.event.MegaEventManager
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.platform.ESByteArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * @version 2024/8/22
 */
class MegaDeviceFamilyViewModel : BaseScreenModel() {
  private var categoryIconUrls = mutableStateMapOf<String, String>()
  private var deviceIconUrls = mutableStateMapOf<String, String>()
  private var deviceCategories = mutableStateListOf<DeviceCategory>()
  private var selectedCategoryPlatform by mutableStateOf<String?>(null)
  private var searchQuery by mutableStateOf("")

  val uiState = screenModelScope.launchMolecule(RecompositionMode.Immediate) {
    MegaDeviceFamilyUiState(
      categoryIconUrls = categoryIconUrls,
      deviceIconUrls = deviceIconUrls,
      deviceCategories = deviceCategories,
      selectedCategoryPlatform = selectedCategoryPlatform,
      searchQuery = searchQuery,
      filteredDeviceCategories = filteredDeviceCategories,
      mergedDevices = mergedDevices
    )
  }

  init {
    loadDeviceCategories()
  }

  @OptIn(ExperimentalResourceApi::class)
  private fun loadDeviceCategories() {
    screenModelScope.launch {
      try {
        val bytes = Res.readBytes("files/eufy_PN_deviceFamilyList.json")
        val jsonString = bytes.decodeToString()
        if (jsonString.isNotEmpty()) {
          val categories = kJson.decodeFromString<List<DeviceCategory>>(jsonString)
          deviceCategories.addAll(categories)
          selectedCategoryPlatform = categories.firstOrNull()?.categoryPlatform
        } else {
          println("Error: JSON content is empty")
        }
      } catch (e: Exception) {
        println("Error reading or parsing JSON: ${e.message}")
      }
    }
  }

  fun updateSearchQuery(query: String) {
    searchQuery = query
  }

  fun updateSelectedCategoryPlatform(platform: String) {
    selectedCategoryPlatform = platform
  }


  fun loadCategoryIconUrl(categoryName: String) {
    screenModelScope.launch {
      val iconUrl = GetProductCategoryListManager.getCategoryIconBy(categoryName)
      categoryIconUrls[categoryName] = iconUrl ?: ""
      appLogger.i("loadCategoryIconUrl:$iconUrl")
    }
  }

  fun loadDeviceIconUrl(pn: String) {
    screenModelScope.launch {
      appLogger.i("pn:$pn")
      val iconUrl = GetProductCategoryListManager.getDeviceIconsBy(pn)
      deviceIconUrls[pn] = iconUrl ?: ""
      appLogger.i("loadDeviceIconUrl:$iconUrl")
    }
  }

  val filteredDeviceCategories: List<DeviceCategory>
    get() = deviceCategories.map { category ->
      category.copy(
        deviceList = category.deviceList.filter { device ->
          device.deviceTypeName.contains(searchQuery, ignoreCase = true) ||
          device.deviceCateLv2.contains(searchQuery, ignoreCase = true) ||
          device.devicePNs.any { it.contains(searchQuery, ignoreCase = true) }
        }
      )
    }

  val mergedDevices: List<MergedDevice>
    get() = filteredDeviceCategories.flatMap { it.deviceList }
      .groupBy { it.deviceCateLv2 }
      .map { (cateLv2, devices) ->
        MergedDevice(
          deviceCateLv2 = cateLv2,
          deviceTypeNames = devices.map { it.deviceTypeName },
          iconURL = devices.first().iconURL,
          devicePNs = devices.flatMap { it.devicePNs },
          isBlur = devices.any { it.isBlur },
          isNewTag = devices.any { it.isNewTag },
          isHidden = devices.all { it.isHidden },
          countryCodeBlackList = devices.flatMap { it.countryCodeBlackList }.distinct(),
          countryCodeWhiteList = devices.flatMap { it.countryCodeWhiteList }.distinct(),
          isFollowNewProcess = devices.any { it.isFollowNewProcess }
        )
      }
}
data class MegaDeviceFamilyUiState(
  val categoryIconUrls: Map<String, String>,
  val deviceIconUrls: Map<String, String>,
  val deviceCategories: List<DeviceCategory>,
  val selectedCategoryPlatform: String?,
  var searchQuery: String,
  val filteredDeviceCategories: List<DeviceCategory>,
  val mergedDevices: List<MergedDevice>
)

