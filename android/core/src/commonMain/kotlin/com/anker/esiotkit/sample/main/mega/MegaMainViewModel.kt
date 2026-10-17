package com.anker.esiotkit.sample.main.mega

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import cafe.adriel.voyager.core.model.screenModelScope
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.house.presenter.MegaHouseManager
import com.anker.esiotkit.net_api.response.HouseInfo
import com.anker.esiotkit.p2p.client.PlatformP2PClient
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.data.ProductInfoManager
import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.sample.support.model.LoginType
import com.anker.esiotkit.security_device.p2p.P2PPingManager
import com.anker.esiotkit.support.IoDispatcher
import com.anker.esiotkit.support.di.esInject
import com.anker.esiotkit.support.log.appLogger
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * @version 2024/5/1
 */
class MegaMainViewModel : BaseScreenModel() {
  var filterDeviceSn by mutableStateOf<String?>(null)

  val uiState = screenModelScope.launchMolecule(RecompositionMode.Immediate) {
    val houseDeviceList by MegaHouseManager.houseDeviceList.collectAsState()
    val directDeviceList by MegaDeviceManager.deviceList.collectAsState()
    val deviceList = if (InfoCache.loginType == LoginType.BUSINESS) directDeviceList else houseDeviceList
    appLogger.i("MegaMainViewModel/deviceList:${deviceList.size}, loginType:${InfoCache.loginType}")
    val houseId by MegaHouseManager.houseId.collectAsState()
    val houseList by MegaHouseManager.houseList.collectAsState()
    MegaMainUiState(
      loading = loading,
      deviceList = sortSdkDeviceList(deviceList),
      houseId = houseId,
      houseList = houseList
    )
  }

  init {
    appLogger.i("MegaMainViewModel/init/loginType:${InfoCache.loginType}")
    appLogger.i("MegaMainViewModel/init/houseId:${MegaHouseManager.houseId.value}")
    appLogger.i("MegaMainViewModel/init/MegaDeviceManager.deviceList.size:${MegaDeviceManager.deviceList.value.size}")
    appLogger.i("MegaMainViewModel/init/MegaHouseManager.houseDeviceList.size:${MegaHouseManager.houseDeviceList.value.size}")
    
    // 首次加载：走 launchWithLoading 置 loading=true，触发下拉刷新动画
    // 使用 "refreshDevice" Job 名，首载中用户手动下拉会取消首载 Job（同名取消），避免并发重复请求
    launchWithLoading("refreshDevice") {
      appLogger.i("MegaMainViewModel/refreshDevice/start: loginType=${InfoCache.loginType}")
      if (InfoCache.loginType == LoginType.BUSINESS) {
        val result = MegaDeviceManager.refreshDeviceList().await()
        appLogger.i("MegaMainViewModel/refreshDevice/business: code=${result.code}, size=${result.data?.size}")
        if (!result.isSuccess) error = "设备列表加载失败，请重试。"
      } else {
        val currentHouseId = MegaHouseManager.houseId.value
        if (currentHouseId.isEmpty()) {
          appLogger.i("MegaMainViewModel/refreshDevice/house: houseId empty, refreshing house list first")
          val result = MegaHouseManager.refreshHouseList().await()
          if (!result.isSuccess) {
            error = "站点列表加载失败，请重试。"
            return@launchWithLoading
          }
        }
        val houseId = MegaHouseManager.houseId.value
          .ifEmpty { MegaHouseManager.houseList.value.firstOrNull()?.houseId ?: "" }
        if (houseId.isEmpty()) {
          appLogger.w("MegaMainViewModel/refreshDevice/house: no houseId available, skipping device fetch")
          return@launchWithLoading
        }
        appLogger.i("MegaMainViewModel/refreshDevice/house: fetching devices for houseId=$houseId")
        val result = MegaHouseManager.refreshHouseDeviceListBy(houseId).await()
        if (!result.isSuccess) error = "设备列表加载失败，请重试。"
        appLogger.i("MegaMainViewModel/refreshDevice/house: done, houseDeviceList.size=${MegaHouseManager.houseDeviceList.value.size}")
      }
    }
    launch(IoDispatcher) {
      uiState.map { it.deviceList }.distinctUntilChangedBy {
        it.joinToString { it.deviceSn }
      }.collect {
        PlatformP2PClient.p2pConnectAll(it.map { it.iotDevice })
        it.forEach {
          esInject<P2PPingManager>().startPeriodPing(it.iotDevice)
        }
      }
    }
    // for init
    ProductInfoManager
  }

  fun refreshDevice() {
    launchWithLoading("refreshDevice") {
      if (InfoCache.loginType == LoginType.BUSINESS) {
        val result = MegaDeviceManager.refreshDeviceList().await()
        if (!result.isSuccess) error = "设备列表加载失败，请重试。"
      } else {
        val houseId = uiState.value.houseId
        if (houseId.isEmpty()) {
          val result = MegaHouseManager.refreshHouseList().await()
          if (!result.isSuccess) {
            error = "站点列表加载失败，请重试。"
            return@launchWithLoading
          }
        }
        val selectedHouseId = houseId.ifEmpty {
          MegaHouseManager.houseId.value.ifEmpty { MegaHouseManager.houseList.value.firstOrNull()?.houseId ?: "" }
        }
        if (selectedHouseId.isEmpty()) return@launchWithLoading
        val result = MegaHouseManager.refreshHouseDeviceListBy(selectedHouseId).await()
        if (!result.isSuccess) error = "设备列表加载失败，请重试。"
      }
    }
  }

  fun toggleHouse(id: String) {
    launchWithLoading("toggleHouse") {
      MegaHouseManager.toggleHouse(id).await()
    }
  }

}

/** Keep cameras grouped below their parent while retaining devices from partial SDK responses. */
internal fun sortSdkDeviceList(deviceList: List<MegaDevice>): List<MegaDevice> {
  if (deviceList.isEmpty()) return emptyList()

  val childrenByParent = deviceList
    .filter { it.deviceSn != it.parentSn }
    .groupBy { it.parentSn }
  val parents = deviceList
    .filter { it.deviceSn == it.parentSn }
    .sortedByDescending { it.iotDevice.bindTime }

  val result = mutableListOf<MegaDevice>()
  parents.forEach { parent ->
    result.add(parent)
    childrenByParent[parent.deviceSn]
      ?.sortedByDescending { it.iotDevice.bindTime }
      ?.let { result.addAll(it) }
  }
  // A parent may be absent from a partial SDK response; keep its cameras visible.
  val addedSns = result.mapTo(mutableSetOf()) { it.deviceSn }
  result.addAll(deviceList.filter { it.deviceSn !in addedSns })
  return result
}

data class MegaMainUiState(
  val deviceList: List<MegaDevice>,
  val loading: Boolean,
  val houseId: String,
  val houseList: List<HouseInfo>
)
