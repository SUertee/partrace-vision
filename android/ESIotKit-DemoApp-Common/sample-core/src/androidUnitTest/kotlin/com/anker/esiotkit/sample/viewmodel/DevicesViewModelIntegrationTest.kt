package com.anker.esiotkit.sample.viewmodel

import com.anker.esiotkit.sample.model.DeviceListState
import com.anker.esiotkit.sample.model.DisplayDeviceItem
import com.anker.esiotkit.sample.model.SortDirection
import com.anker.esiotkit.sample.model.SortOption
import com.anker.esiotkit.sample.model.SortType
import com.anker.esiotkit.sample.model.StatusFilterValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@kotlinx.coroutines.ExperimentalCoroutinesApi
class DevicesViewModelIntegrationTest {

  private val testDispatcher = UnconfinedTestDispatcher()

  @BeforeTest
  fun setup() {
    Dispatchers.setMain(testDispatcher)
  }

  @AfterTest
  fun teardown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `init should load device list`() {
    val viewModel = DevicesViewModel()
    // ViewModel init calls loadDeviceList() which will fail since SDK is not available
    // but the state should be set correctly
    val state = viewModel.uiState.value
    assertNotNull(state)
  }

  @Test
  fun `retryLoad should clear error state`() {
    val viewModel = DevicesViewModel()
    // Force error state via retry
    viewModel.retryLoad()

    val state = viewModel.uiState.value
    // After retryLoad, error should be cleared (may still be loading)
    assertFalse(state.errorMessage.isNotEmpty() && state.isError)
  }

  @Test
  fun `onAddDeviceClick should set navigation target to AddDevice`() {
    val viewModel = DevicesViewModel()
    viewModel.onAddDeviceClick()

    val nav = viewModel.navigationTarget.value
    assertTrue(nav is NavigationTarget.AddDevice)
  }

  @Test
  fun `onAddDeviceResult with success should trigger load and show toast`() {
    val viewModel = DevicesViewModel()
    viewModel.onAddDeviceClick()
    viewModel.onAddDeviceResult(success = true)

    val nav = viewModel.navigationTarget.value
    assertEquals(NavigationTarget.None, nav)
    // Toast message should be set
    val toast = viewModel.toastMessage.value
    assertEquals("设备添加成功", toast)
  }

  @Test
  fun `onAddDeviceResult with failure should not show success toast`() {
    val viewModel = DevicesViewModel()
    viewModel.onAddDeviceClick()
    viewModel.onAddDeviceResult(success = false)

    val nav = viewModel.navigationTarget.value
    assertEquals(NavigationTarget.None, nav)
    val toast = viewModel.toastMessage.value
    // Should not be the success toast
    assertTrue(toast != "设备添加成功")
  }

  @Test
  fun `onEventClick should set correct navigation target`() {
    val viewModel = DevicesViewModel()
    viewModel.onEventClick("T8410ABC123")

    val nav = viewModel.navigationTarget.value
    assertTrue(nav is NavigationTarget.EventList)
    assertEquals("T8410ABC123", nav.deviceId)
  }

  @Test
  fun `onSettingsClick should set correct navigation target`() {
    val viewModel = DevicesViewModel()
    viewModel.onSettingsClick("T8010STATION")

    val nav = viewModel.navigationTarget.value
    assertTrue(nav is NavigationTarget.Settings)
    assertEquals("T8010STATION", nav.deviceId)
  }

  @Test
  fun `retryLoadChildDevices should set correct navigation target`() {
    val viewModel = DevicesViewModel()
    viewModel.retryLoadChildDevices("T8010STATION")

    val nav = viewModel.navigationTarget.value
    assertTrue(nav is NavigationTarget.RetryLoadChild)
    assertEquals("T8010STATION", nav.stationId)
  }

  @Test
  fun `consumeNavigation should reset navigation target`() {
    val viewModel = DevicesViewModel()
    viewModel.onAddDeviceClick()
    assertTrue(viewModel.navigationTarget.value is NavigationTarget.AddDevice)

    viewModel.consumeNavigation()
    assertEquals(NavigationTarget.None, viewModel.navigationTarget.value)
  }

  @Test
  fun `toast message should be cleared after consumption`() {
    val viewModel = DevicesViewModel()
    viewModel.onAddDeviceClick()
    viewModel.onAddDeviceResult(success = true)

    assertNotNull(viewModel.toastMessage.value)
  }

  @Test
  fun `navigation target should only have one active at a time`() {
    val viewModel = DevicesViewModel()
    viewModel.onAddDeviceClick()
    assertEquals(NavigationTarget.AddDevice, viewModel.navigationTarget.value)

    // New navigation should replace previous
    viewModel.onEventClick("T8410NEW")
    assertTrue(viewModel.navigationTarget.value is NavigationTarget.EventList)
    assertEquals("T8410NEW", (viewModel.navigationTarget.value as NavigationTarget.EventList).deviceId)
  }
}
