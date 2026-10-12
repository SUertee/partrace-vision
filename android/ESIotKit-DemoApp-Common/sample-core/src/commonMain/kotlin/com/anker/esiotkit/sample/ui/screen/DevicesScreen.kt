package com.anker.esiotkit.sample.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.anker.esiotkit.sample.ui.component.DeviceListContainer
import com.anker.esiotkit.sample.view.MegaToaster
import com.anker.esiotkit.sample.viewmodel.DevicesViewModel
import com.anker.esiotkit.sample.viewmodel.NavigationTarget

/**
 * T-UI-01 ~ T-UI-06: 设备列表主屏幕
 *
 * Add Device 按钮已移入搜索行，不使用 FAB。
 *
 * @param viewModel 设备列表 ViewModel
 * @param onNavigate 页面导航回调
 */
@Composable
fun DevicesScreen(
  viewModel: DevicesViewModel,
  onNavigate: (NavigationTarget) -> Unit = {},
) {
  val uiState by viewModel.uiState.collectAsState()
  val navigation by viewModel.navigationTarget.collectAsState()

  LaunchedEffect(navigation) {
    if (navigation !is NavigationTarget.None) {
      onNavigate(navigation)
      viewModel.consumeNavigation()
    }
  }

  Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
    ) {
      val pullRefreshState = rememberPullRefreshState(
        refreshing = uiState.isRefreshing,
        onRefresh = { viewModel.refreshDeviceList() }
      )

      DeviceListContainer(
        state = uiState,
        onSearchQueryChange = { viewModel.onSearchQueryChange(it) },
        onStatusFilterChange = { /* filter handled locally in ViewModel if needed */ },
        onSortOptionChange = { /* sort handled locally in ViewModel if needed */ },
        onDeviceClick = { device -> viewModel.onDeviceClick(device) },
        onSettingsClick = { device -> viewModel.onSettingsClick(device.deviceSn) },
        onEventClick = { deviceId -> viewModel.onEventClick(deviceId) },
        onRetryLoad = { viewModel.retryLoad() },
        onRetryLoadChild = { stationId -> viewModel.retryLoadChildDevices(stationId) },
        onAddDeviceClick = { viewModel.onAddDeviceClick() },
        onRefresh = { viewModel.refreshDeviceList() },
        onClearSearch = { viewModel.onClearSearch() },
      )

      PullRefreshIndicator(
        refreshing = uiState.isRefreshing,
        state = pullRefreshState,
        modifier = Modifier.align(Alignment.TopCenter)
      )
    }
  }

  MegaToaster()
}
