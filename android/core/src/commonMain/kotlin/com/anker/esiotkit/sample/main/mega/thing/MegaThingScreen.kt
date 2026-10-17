package com.anker.esiotkit.sample.main.mega.thing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.anker.esiotkit.sample.ui.component.DisableOverscrollEffect
import com.anker.esiotkit.sample.view.SearchBar
import com.anker.esiotkit.support.log.appLogger

/**
 * @version 2024/10/22
 */
object MegaThingScreen : Screen {

  @Composable
  override fun Content() {
    val viewModel = rememberScreenModel { MegaThingViewModel() }
    val uiState by viewModel.uiState.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    var searchQuery by remember { mutableStateOf("") }
    val filteredDeviceList = uiState.deviceList.filter { device ->
      device.deviceSn.contains(searchQuery, ignoreCase = true) ||
              device.iotDevice.deviceName.contains(searchQuery, ignoreCase = true)
    }
    Column(modifier = Modifier.navigationBarsPadding()) {
      SearchBarSection(onSearchQueryChange= { query ->
        searchQuery = query
        viewModel.updateSearchQuery(query)
      })
      ThingListSection(
        uiState = uiState,
        onDeviceClick = {device ->
          //update thing content by device
        },
        onTypeClick = { thing ->
          navigator.push(MegaThingToDeviceScreen(thing))
        },
        deviceList = filteredDeviceList
      )
    }
  }

  @Composable
  fun SearchBarSection(onSearchQueryChange: (String) -> Unit) {
    SearchBar("pn/sn/thing", onSearch = { query ->
      onSearchQueryChange(query)
    }, modifier = Modifier.padding(8.dp))
  }

  @Composable
  fun ThingListSection(
    uiState: MegaThingUiState,
    onDeviceClick: (MegaDevice) -> Unit,
    onTypeClick: (Thing) -> Unit,
    deviceList: List<MegaDevice>
  ) {
    if (uiState.searchQuery.isNotEmpty()) {
      DeviceWithThingList(
        uiState = uiState,
        onDeviceClick = onDeviceClick,
        onTypeClick = onTypeClick,
        deviceList = deviceList
      )

    } else {
      DeviceWithThingList(
        uiState = uiState,
        onDeviceClick = onDeviceClick,
        onTypeClick = onTypeClick,
        deviceList = deviceList
      )
    }
  }

  @Composable
  fun DeviceWithThingList(
    uiState: MegaThingUiState,
    onDeviceClick:(MegaDevice) -> Unit,
    onTypeClick: (Thing) -> Unit,
    deviceList: List<MegaDevice>
  ) {
    val viewModel = rememberScreenModel { MegaThingViewModel() }
    val selectedDevice = viewModel.selectedDevice ?: deviceList[0] // 默认选中第一个设备
    viewModel.selectDevice(selectedDevice) // 初始化选中状态
    Row(modifier = Modifier.fillMaxSize()) {
      DeviceList(
        modifier = Modifier.weight(3f).background(Color.LightGray),
        deviceList = deviceList,
        selectedDevice = selectedDevice,
        onClick = { device ->
          appLogger.d("DeviceWithThingList/DeviceList/onClick/device:${device.deviceSn}")
          viewModel.selectDevice(device) // 更新选中的设备
          onDeviceClick(device)
        }
      )
      ThingListByMegaDevice(
        modifier = Modifier.weight(7f),
        device = selectedDevice,
        thingList = viewModel.getThingByDevice(selectedDevice),
        onTypeClick = onTypeClick

      )
    }
  }



  @Composable
  private fun ThingListByMegaDevice(
    modifier: Modifier,
    device: MegaDevice,
    thingList:List<ThingType>,
    onTypeClick: (Thing) -> Unit
  ){
    // 禁用 overscroll 效果，解决滚动到边界时点击坐标错位的问题
    DisableOverscrollEffect {
      LazyColumn(
        modifier = modifier.clipToBounds(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        val groupedMap = thingList.groupBy { it.thingModel }
        groupedMap.forEach { (thingModel, thingTypeGroup)->
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




  @Composable
  fun DeviceList(
    modifier: Modifier,
    selectedDevice: MegaDevice,
    deviceList: List<MegaDevice>,
    onClick:(MegaDevice) -> Unit
  ) {
    // 禁用 overscroll 效果，解决滚动到边界时点击坐标错位的问题
    DisableOverscrollEffect {
      LazyColumn (
        modifier = modifier.clipToBounds(),
        contentPadding = PaddingValues(bottom = 16.dp)
      ){
        items(deviceList){ device ->
          Card(
            colors = CardDefaults.cardColors(
              containerColor = if (device == selectedDevice) Color.Yellow else Color.White // 高亮选中项
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
              .padding(horizontal = 16.dp).padding(vertical = 4.dp)
              .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)).clickable {
                onClick(device)
              }
          ) {
            Column (modifier = Modifier.padding(vertical = 8.dp).padding(horizontal = 16.dp)){
              Text(
                text = device.deviceSn,
                fontSize = 14.sp,
                color = Color.Black,
              )
              Text(
                text = device.iotDevice.deviceName,
                fontSize = 12.sp,
                color = Color.Black,
                modifier = Modifier.alpha(0.8f),
              )
            }
          }
        }
      }
    }

  }
}




