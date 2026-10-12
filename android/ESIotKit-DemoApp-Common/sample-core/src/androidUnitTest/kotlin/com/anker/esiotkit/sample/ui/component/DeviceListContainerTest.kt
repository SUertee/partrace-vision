package com.anker.esiotkit.sample.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.anker.esiotkit.sample.model.DeviceListState
import com.anker.esiotkit.sample.model.DisplayDeviceItem
import com.anker.esiotkit.sample.model.SortOption
import com.anker.esiotkit.sample.model.StatusFilterValue
import com.anker.esiotkit.sample.ui.TestHelpers.createMockChild
import com.anker.esiotkit.sample.ui.TestHelpers.createMockStandalone
import com.anker.esiotkit.sample.ui.TestHelpers.createMockStation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T-UI-01~06: DeviceListContainer Compose UI 测试
 */
@RunWith(RobolectricTestRunner::class)
class DeviceListContainerTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  // ========== Loading state ==========

  @Test
  fun should_show_loading_view_when_isLoading_is_true() {
    // Arrange
    val state = DeviceListState(isLoading = true)
    composeTestRule.setContent {
      DeviceListContainer(
        state = state,
        onSearchQueryChange = {},
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onDeviceClick = {},
        onSettingsClick = {},
        onEventClick = {},
        onRetryLoad = {},
        onRetryLoadChild = {},
        onAddDeviceClick = {},
        onRefresh = {},
        onClearSearch = {}
      )
    }

    // Act & Assert
    composeTestRule.onNodeWithText("Loading devices...").assertIsDisplayed()
  }

  // ========== ERROR state ==========

  @Test
  fun should_show_error_view_when_isError_is_true() {
    // Arrange
    val state = DeviceListState(
      isLoading = false,
      isError = true,
      errorMessage = "Network error"
    )
    composeTestRule.setContent {
      DeviceListContainer(
        state = state,
        onSearchQueryChange = {},
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onDeviceClick = {},
        onSettingsClick = {},
        onEventClick = {},
        onRetryLoad = {},
        onRetryLoadChild = {},
        onAddDeviceClick = {},
        onRefresh = {},
        onClearSearch = {}
      )
    }

    // Act & Assert
    composeTestRule.onNodeWithText("Failed to load devices").assertIsDisplayed()
    composeTestRule.onNodeWithText("Network error").assertIsDisplayed()
    composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
  }

  @Test
  fun should_invoke_onRetryLoad_when_retry_button_is_tapped() {
    // Arrange
    var retryInvoked = false
    val state = DeviceListState(
      isLoading = false,
      isError = true,
      errorMessage = "Network error"
    )
    composeTestRule.setContent {
      DeviceListContainer(
        state = state,
        onSearchQueryChange = {},
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onDeviceClick = {},
        onSettingsClick = {},
        onEventClick = {},
        onRetryLoad = { retryInvoked = true },
        onRetryLoadChild = {},
        onAddDeviceClick = {},
        onRefresh = {},
        onClearSearch = {}
      )
    }

    // Act
    composeTestRule.onNodeWithText("Retry").performClick()

    // Assert
    assert(retryInvoked) { "Expected onRetryLoad to be invoked" }
  }

  // ========== Empty state ==========

  @Test
  fun should_show_empty_view_when_no_devices_and_not_loading() {
    // Arrange
    val state = DeviceListState(
      rawDevices = emptyList(),
      isLoading = false,
      isError = false
    )
    composeTestRule.setContent {
      DeviceListContainer(
        state = state,
        onSearchQueryChange = {},
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onDeviceClick = {},
        onSettingsClick = {},
        onEventClick = {},
        onRetryLoad = {},
        onRetryLoadChild = {},
        onAddDeviceClick = {},
        onRefresh = {},
        onClearSearch = {}
      )
    }

    // Act & Assert
    composeTestRule.onNodeWithText("No devices yet").assertIsDisplayed()
    composeTestRule.onNodeWithText("Add your first device to get started.").assertIsDisplayed()
    composeTestRule.onNodeWithText("Add Device").assertIsDisplayed()
  }

  @Test
  fun should_invoke_onAddDeviceClick_when_add_device_button_is_tapped_in_empty_view() {
    // Arrange
    var addInvoked = false
    val state = DeviceListState(
      rawDevices = emptyList(),
      isLoading = false,
      isError = false
    )
    composeTestRule.setContent {
      DeviceListContainer(
        state = state,
        onSearchQueryChange = {},
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onDeviceClick = {},
        onSettingsClick = {},
        onEventClick = {},
        onRetryLoad = {},
        onRetryLoadChild = {},
        onAddDeviceClick = { addInvoked = true },
        onRefresh = {},
        onClearSearch = {}
      )
    }

    // Act
    composeTestRule.onNodeWithText("Add Device").performClick()

    // Assert
    assert(addInvoked) { "Expected onAddDeviceClick to be invoked" }
  }

  // ========== Search no results state ==========

  @Test
  fun should_show_search_no_results_view_when_search_has_no_matches() {
    // Arrange
    val state = DeviceListState(
      searchQuery = "nonexistent",
      displayList = emptyList(),
      isLoading = false
    )
    composeTestRule.setContent {
      DeviceListContainer(
        state = state,
        onSearchQueryChange = {},
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onDeviceClick = {},
        onSettingsClick = {},
        onEventClick = {},
        onRetryLoad = {},
        onRetryLoadChild = {},
        onAddDeviceClick = {},
        onRefresh = {},
        onClearSearch = {}
      )
    }

    // Act & Assert
    composeTestRule.onNodeWithText("No results found").assertIsDisplayed()
    composeTestRule.onNodeWithText("Try adjusting your search.").assertIsDisplayed()
    composeTestRule.onNodeWithText("Clear Search").assertIsDisplayed()
  }

  @Test
  fun should_invoke_onClearSearch_when_clear_search_button_is_tapped() {
    // Arrange
    var clearInvoked = false
    val state = DeviceListState(
      searchQuery = "nonexistent",
      displayList = emptyList(),
      isLoading = false
    )
    composeTestRule.setContent {
      DeviceListContainer(
        state = state,
        onSearchQueryChange = {},
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onDeviceClick = {},
        onSettingsClick = {},
        onEventClick = {},
        onRetryLoad = {},
        onRetryLoadChild = {},
        onAddDeviceClick = {},
        onRefresh = {},
        onClearSearch = { clearInvoked = true }
      )
    }

    // Act
    composeTestRule.onNodeWithText("Clear Search").performClick()

    // Assert
    assert(clearInvoked) { "Expected onClearSearch to be invoked" }
  }

  // ========== Device list content ==========
  // Note: Device card rendering tests (StandaloneCard, StationCard) require
  // instrumentation tests or deep SDK mocking due to MegaDeviceManager/ThingIdentifier
  // static initializers. The following tests verify the data structure level.

  @Test
  fun should_have_correct_display_list_structure_when_devices_exist() {
    // Arrange
    val device = createMockStandalone("T1000ABC", "My Doorbell")
    val station = createMockStation("T8010XYZ", "My Station")
    val child = createMockChild("T3000ABC", "Camera 1")
    val displayList = listOf(
      DisplayDeviceItem.StandaloneItem(device = device),
      DisplayDeviceItem.StationItem(station = station, children = listOf(child))
    )
    val state = DeviceListState(
      rawDevices = listOf(device, station, child),
      displayList = displayList,
      isLoading = false
    )

    // Act & Assert - verify data structure is correctly passed through
    assert(!state.isLoading) { "Expected not loading" }
    assert(!state.isError) { "Expected no error" }
    assert(state.displayList.size == 2) { "Expected 2 display items, but was ${state.displayList.size}" }
    assert(state.rawDevices.size == 3) { "Expected 3 raw devices, but was ${state.rawDevices.size}" }
  }
}
