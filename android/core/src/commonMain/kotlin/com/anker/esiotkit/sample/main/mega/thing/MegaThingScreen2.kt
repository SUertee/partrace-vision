package com.anker.esiotkit.sample.main.mega.thing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.TabRowDefaults.Divider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.sample.main.mega.thing.mode.Thing
import com.anker.esiotkit.sample.main.mega.thing.mode.ThingType
import com.anker.esiotkit.sample.main.mega.thing.thing_test.ThingTestScreen
import com.anker.esiotkit.sample.ui.component.DisableOverscrollEffect
import com.anker.esiotkit.sample.view.ClickableTextItem
import com.anker.esiotkit.sample.view.SearchBar

object MegaThingScreen2 : Screen {

  @Composable
  override fun Content() {
    val viewModel = rememberScreenModel { MegaThingViewModel() }
    val uiState by viewModel.uiState.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    var searchQuery by remember { mutableStateOf("") }
    var thingQuery by remember { mutableStateOf("") }
    val filteredDeviceList = uiState.deviceList.filter { device ->
      (device.deviceSn.contains(searchQuery, ignoreCase = true)
      || device.iotDevice.deviceName.contains(searchQuery, ignoreCase = true))
      && viewModel.getThingByDevice(device).any { thingType ->
        thingType.thingList.any { thing ->
          thing.thingIdentifier.contains(thingQuery, ignoreCase = true)
        }
      }
    }
    Column(modifier = Modifier.navigationBarsPadding()) {
      SearchBarSection(onSearchQueryChange = { query ->
        searchQuery = query
        viewModel.updateSearchQuery(query)
      })
      SearchBarThing(onSearchQueryChange = { query ->
        thingQuery = query
        viewModel.updateThingQuery(query)
      })

      ThingListSection(
        uiState = uiState,
        onDeviceClick = { device ->
          navigator.push(ThingTestScreen(device, 0, ""))
        },
        onTypeClick = { device, thing, tabPosition ->
          navigator.push(ThingTestScreen(device, tabPosition, thing.thingIdentifier))
        },
        deviceList = filteredDeviceList
      )
    }
  }

  @Composable
  fun SearchBarSection(onSearchQueryChange: (String) -> Unit) {
    SearchBar("pn/sn", onSearch = { query ->
      onSearchQueryChange(query)
    }, modifier = Modifier.padding(8.dp))
  }

  @Composable
  fun SearchBarThing(onSearchQueryChange: (String) -> Unit) {
    SearchBar("thing", onSearch = { query ->
      onSearchQueryChange(query)
    }, modifier = Modifier.padding(8.dp))
  }

  @Composable
  fun ThingListSection(
    uiState: MegaThingUiState,
    onDeviceClick: (MegaDevice) -> Unit,
    onTypeClick: (MegaDevice, Thing, Int) -> Unit,
    deviceList: List<MegaDevice>
  ) {
    if (uiState.searchQuery.isNotEmpty()) {
      DeviceWithThingList2(
        uiState = uiState,
        onDeviceClick = onDeviceClick,
        onTypeClick = onTypeClick,
        deviceList = deviceList
      )

    } else {
      DeviceWithThingList2(
        uiState = uiState,
        onDeviceClick = onDeviceClick,
        onTypeClick = onTypeClick,
        deviceList = deviceList
      )
    }
  }

  @Composable
  fun DeviceWithThingList2(
    uiState: MegaThingUiState,
    onDeviceClick: (MegaDevice) -> Unit,
    onTypeClick: (MegaDevice, Thing, Int) -> Unit,
    deviceList: List<MegaDevice>
  ) {
    val viewModel = rememberScreenModel { MegaThingViewModel() }
    val selectedDevice = viewModel.selectedDevice ?: deviceList.firstOrNull() // 默认选中第一个设备
    selectedDevice?.let {
      viewModel.selectDevice(selectedDevice) // 初始化选中状态
    }
    // 禁用 overscroll 效果，解决滚动到边界时点击坐标错位的问题
    DisableOverscrollEffect {
      LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 16.dp)
      ) {
        items(deviceList, key = { device -> device.deviceSn }) { device ->
          ExpandableCard(
            device = device,
            onTypeClick = onTypeClick,
            viewModel = viewModel,
            onDeviceClick = onDeviceClick
          )
        }
      }
    }
  }

  @Composable
  fun ExpandableCard(
    device: MegaDevice,
    onTypeClick: (MegaDevice, Thing, Int) -> Unit,
    onDeviceClick: (MegaDevice) -> Unit,
    viewModel: MegaThingViewModel
  ) {
    var isExpanded by remember { mutableStateOf(false) }
    Card(
      modifier = Modifier.fillMaxWidth().padding(8.dp)
    ) {
      Column(modifier = Modifier.padding(4.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth().clickable {
            onDeviceClick(device)
          },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "${device.deviceSn},\n${device.iotDevice.deviceName}",
            fontSize = 16.sp,
            modifier = Modifier.padding(4.dp).weight(1f)
          )
          Icon(
            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = "Expand Icon",
            modifier = Modifier.size(24.dp).clickable { isExpanded = !isExpanded }
          )
        }
        if (isExpanded) {
          Divider(color = Color.Black.copy(alpha = 0.2f), thickness = 1.dp, modifier = Modifier.padding(4.dp))
          Column {
            viewModel.getThingByDevice(device).forEach { thingType ->

              Text(
                text = thingType.thingModel,
                fontSize = 20.sp,
                modifier = Modifier.padding(vertical = 8.dp)
              )

              Card {
                thingType.thingList.forEach { thing ->
                  ClickableTextItem(
                    text = "•${thing.thingIdentifier}",
                    onClick = {
                      onTypeClick(
                        device, thing, when (thingType.thingModel.lowercase()) {
                          "action" -> 1
                          "property" -> 0
                          "event" -> 2
                          else -> 0
                        }
                      )
                    }
                  )
                }
              }
            }

          }

        }

      }
    }
  }

  @Composable
  private fun ContentThings(
    modifier: Modifier,
    device: MegaDevice,
    thingList: List<ThingType>,
    onTypeClick: (Thing) -> Unit
  ) {
    // 禁用 overscroll 效果，解决滚动到边界时点击坐标错位的问题
    DisableOverscrollEffect {
      LazyColumn(
        modifier = modifier.clipToBounds(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        val groupedMap = thingList.groupBy { it.thingModel }
        groupedMap.forEach { (thingModel, thingTypeGroup) ->
          // 添加每个分组的标题
          // 添加分组标题
          item {
            Text(
              text = thingModel,
              fontSize = 20.sp,
              modifier = Modifier.padding(vertical = 8.dp)
            )
          }

          // 展示分组内的 Thing 数据
          items(thingTypeGroup.flatMap { it.thingList }) { thing ->
            Text(
              text = "• ${thing.thingIdentifier}",
              modifier = Modifier.padding(start = 16.dp)
            )
          }
        }
      }
    }

  }

}








