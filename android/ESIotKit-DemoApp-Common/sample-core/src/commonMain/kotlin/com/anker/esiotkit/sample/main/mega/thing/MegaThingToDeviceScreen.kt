package com.anker.esiotkit.sample.main.mega.thing

import kotlinx.serialization.Transient

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import com.anker.esiotkit.sample.main.mega.thing.mode.Thing
import com.anker.esiotkit.sample.view.BaseScreen
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_thing_device_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/7/23
 */
class MegaThingToDeviceScreen(@Transient val thing: Thing) : BaseScreen() {
  override val title: StringResource get() = Res.string.eslot_thing_device_title

  @Composable
  override fun Content() = BaseColumn {

    val viewModel = rememberScreenModel { MegaThingViewModel() }
    val uiState by viewModel.uiState.collectAsState()

    // val navigator = LocalNavigator.currentOrThrow
    // val mergedDevices = category.deviceList
    //   .groupBy { it.deviceCateLv2 }
    //   .map { (cateLv2, devices) ->
    //     MergedDevice(
    //       deviceCateLv2 = cateLv2,
    //       deviceTypeNames = devices.map { it.deviceTypeName },
    //       iconURL = devices.first().iconURL,
    //       devicePNs = devices.flatMap { it.devicePNs },
    //       isBlur = devices.any { it.isBlur },
    //       isNewTag = devices.any { it.isNewTag },
    //       isHidden = devices.all { it.isHidden },
    //       countryCodeBlackList = devices.flatMap { it.countryCodeBlackList }.distinct(),
    //       countryCodeWhiteList = devices.flatMap { it.countryCodeWhiteList }.distinct(),
    //       isFollowNewProcess = devices.any { it.isFollowNewProcess }
    //     )
    //   }

    // LazyColumn(
    //   modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)
    // ) {
    //   items(mergedDevices) { device ->
    //     LaunchedEffect(device.devicePNs) {
    //       device.devicePNs.forEach { pn ->
    //         viewModel.loadDeviceIconUrl(pn)
    //       }
    //     }
    //     val iconUrl = uiState.deviceIconUrls[device.devicePNs.first()] ?: ""
    //     appLogger.i("deviceiconUrl:$iconUrl")
    //     MergedDeviceCard(device = device.copy(iconURL = iconUrl), onClick = {
    //     })
    //     Spacer(modifier = Modifier.height(16.dp))
    //   }
    // }
  }
}