package com.anker.esiotkit.sample.main.mega.add_device.device_family

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.anker.esiotkit.sample.main.SampleAppEvent
import com.anker.esiotkit.sample.view.MergedDeviceCard
import com.anker.esiotkit.sample.view.SearchBar

/**
 * @version 2024/8/23
 */
object MegaDeviceFamilyPage {

  @Composable
  fun Content(
    uiState: MegaDeviceFamilyUiState,
    onSearchQueryChange: (String) -> Unit,
    onCategoryPlatformChange: (String) -> Unit
  ) {
    val navigator = LocalNavigator.currentOrThrow

    Column {
      SearchBarSection(onSearchQueryChange)
      DeviceListSection(
        uiState = uiState,
        onCategoryPlatformChange = onCategoryPlatformChange,
        onCategoryClick = { category ->
          navigator.push(MegaDeviceFamilyDetailScreen(category))
        }
      )
    }
  }

  @Composable
  fun SearchBarSection(onSearchQueryChange: (String) -> Unit) {
    SearchBar("pn/serial name", onSearch = { query ->
      onSearchQueryChange(query)
    }, modifier = Modifier.padding(8.dp))
  }

  @Composable
  fun DeviceListSection(
    uiState: MegaDeviceFamilyUiState,
    onCategoryPlatformChange: (String) -> Unit,
    onCategoryClick: (DeviceCategory) -> Unit
  ) {
    if (uiState.searchQuery.isNotEmpty()) {
      MergedDeviceList(uiState.mergedDevices)
    } else {
      CategoryPlatformAndNameList(
        uiState = uiState,
        onCategoryPlatformChange = onCategoryPlatformChange,
        onCategoryClick = onCategoryClick
      )
    }
  }

  @Composable
  fun MergedDeviceList(mergedDevices: List<MergedDevice>) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(vertical = 8.dp, horizontal = 16.dp)
    ) {
      items(mergedDevices) { device ->
        MergedDeviceCard(device = device, onClick = {
          SampleAppEvent.emit(SampleAppEvent.IntoPairing(device.devicePNs.first()))
        })
        Spacer(modifier = Modifier.height(16.dp))
      }
    }
  }

  @Composable
  fun CategoryPlatformAndNameList(
    uiState: MegaDeviceFamilyUiState,
    onCategoryPlatformChange: (String) -> Unit,
    onCategoryClick: (DeviceCategory) -> Unit
  ) {
    Row(modifier = Modifier.fillMaxSize()) {
      CategoryPlatformList(
        modifier = Modifier.weight(1f),
        deviceCategories = uiState.deviceCategories,
        selectedCategoryPlatform = uiState.selectedCategoryPlatform,
        onCategoryPlatformChange = onCategoryPlatformChange,
      )
      CategoryNameList(
        modifier = Modifier.weight(3f),
        deviceCategories = uiState.deviceCategories,
        selectedCategoryPlatform = uiState.selectedCategoryPlatform,
        onCategoryClick = onCategoryClick
      )
    }
  }

  @Composable
  fun CategoryPlatformList(
    modifier: Modifier = Modifier,
    deviceCategories: List<DeviceCategory>,
    selectedCategoryPlatform: String?,
    onCategoryPlatformChange: (String) -> Unit
  ) {
    LazyColumn(
      modifier = modifier
        .fillMaxHeight()
        .padding(vertical = 8.dp)
        .background(Color.Gray.copy(0.1f))
    ) {
      items(deviceCategories.distinctBy { it.categoryPlatform }) { category ->
        Text(
          text = category.categoryPlatform,
          fontSize = 16.sp,
          textAlign = TextAlign.Center,
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .clickable {
              onCategoryPlatformChange(category.categoryPlatform)
            }
            .background(if (selectedCategoryPlatform == category.categoryPlatform) Color.LightGray else Color.Transparent)
            .padding(vertical = 8.dp, horizontal = 4.dp)
        )
      }
    }
  }

  @Composable
  fun CategoryNameList(
    modifier: Modifier = Modifier,
    deviceCategories: List<DeviceCategory>,
    selectedCategoryPlatform: String?,
    onCategoryClick: (DeviceCategory) -> Unit
  ) {
    val selectedCategories = deviceCategories.filter { it.categoryPlatform == selectedCategoryPlatform }
    LazyColumn(
      modifier = modifier
        .fillMaxHeight()
        .padding(vertical = 8.dp, horizontal = 16.dp)
    ) {
      items(selectedCategories) { category ->
        CategoryItemCard(category = category, onClick = {
          // 处理点击事件
          onCategoryClick(category)
        })
        Spacer(modifier = Modifier.height(16.dp))
      }
    }
  }
}

@Composable
fun CategoryItemCard(category: DeviceCategory, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color.Gray.copy(0.5f))
      .padding(vertical = 8.dp)
      .clickable { onClick() },
    verticalAlignment = Alignment.CenterVertically
  ) {
    Spacer(modifier = Modifier.width(16.dp))
    AsyncImage(
      model = category.categoryIcon,
      contentDescription = category.categoryName,
      modifier = Modifier
        .size(64.dp)
        .clip(RoundedCornerShape(8.dp)),
      contentScale = ContentScale.Crop
    )
    Text(
      text = category.categoryName.substringBefore('/'),
      fontSize = 14.sp,
      modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
    )
  }
}
