package com.anker.esiotkit.sample.data

import androidx.compose.runtime.mutableStateListOf
import com.anker.esiotkit.core.domain.repository.onlyListChanged
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.net_api.api.ThingNetApi
import com.anker.esiotkit.net_api.request.GetProductListRequest
import com.anker.esiotkit.net_api.response.thing.ProductDescription
import com.anker.esiotkit.network.constants.NetConstant
import com.anker.esiotkit.support.di.esInject
import com.anker.esiotkit.support.ioScope
import com.anker.esiotkit.support.log.dataLogger
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * @version 2025/8/19
 */
object ProductInfoManager {
  val securityProductInfo = mutableStateListOf<ProductDescription>()

  init {
    ioScope.launch {
      MegaDeviceManager.deviceList.onlyListChanged().map {
        val pnList = it.filter { it.category == NetConstant.CATEGORY_SECURITY }.map { it.devicePn }.distinct()
        pnList
      }.collect { pnList ->
        dataLogger.i("ProductInfoManager/init/deviceListChanged:${pnList}")
        esInject<ThingNetApi>().getProductList(NetConstant.CATEGORY_SECURITY, GetProductListRequest(pnList))
          .onSuccessNonnull {
            securityProductInfo.clear()
            securityProductInfo.addAll(it.products)
          }
      }
    }
  }

}