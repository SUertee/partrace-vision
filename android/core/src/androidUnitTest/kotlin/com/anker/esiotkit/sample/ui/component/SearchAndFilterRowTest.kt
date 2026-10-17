package com.anker.esiotkit.sample.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.anker.esiotkit.sample.model.SortOption
import com.anker.esiotkit.sample.model.StatusFilterValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T-UI-01: SearchAndFilterRow Compose UI 测试
 */
@RunWith(RobolectricTestRunner::class)
class SearchAndFilterRowTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun should_display_avatar_search_and_add_button() {
    // Arrange
    composeTestRule.setContent {
      SearchAndFilterRow(
        searchQuery = "",
        statusFilter = StatusFilterValue.ALL,
        sortOption = SortOption.DEFAULT,
        onSearchQueryChange = {},
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onClearSearch = {},
        onAddDeviceClick = {}
      )
    }

    // Act & Assert
    composeTestRule.onNodeWithContentDescription("Profile").assertIsDisplayed()
    composeTestRule.onNodeWithText("Search name, SN, PN...").assertIsDisplayed()
    composeTestRule.onNodeWithContentDescription("Add Device").assertIsDisplayed()
  }

  @Test
  fun should_display_clear_button_when_search_query_is_not_empty() {
    // Arrange
    composeTestRule.setContent {
      SearchAndFilterRow(
        searchQuery = "test",
        statusFilter = StatusFilterValue.ALL,
        sortOption = SortOption.DEFAULT,
        onSearchQueryChange = {},
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onClearSearch = {},
        onAddDeviceClick = {}
      )
    }

    // Act & Assert
    composeTestRule.onNodeWithContentDescription("Clear").assertIsDisplayed()
  }

  @Test
  fun should_hide_clear_button_when_search_query_is_empty() {
    // Arrange
    composeTestRule.setContent {
      SearchAndFilterRow(
        searchQuery = "",
        statusFilter = StatusFilterValue.ALL,
        sortOption = SortOption.DEFAULT,
        onSearchQueryChange = {},
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onClearSearch = {},
        onAddDeviceClick = {}
      )
    }

    // Act & Assert
    composeTestRule.onNodeWithContentDescription("Clear").assertDoesNotExist()
  }

  @Test
  fun should_invoke_onSearchQueryChange_when_text_is_entered() {
    // Arrange
    var query = ""
    composeTestRule.setContent {
      SearchAndFilterRow(
        searchQuery = query,
        statusFilter = StatusFilterValue.ALL,
        sortOption = SortOption.DEFAULT,
        onSearchQueryChange = { query = it },
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onClearSearch = {},
        onAddDeviceClick = {}
      )
    }

    // Act
    composeTestRule.onNodeWithText("Search name, SN, PN...").performTextInput("T8010")

    // Assert
    assert(query == "T8010") { "Expected search query 'T8010', but was '$query'" }
  }

  @Test
  fun should_invoke_onClearSearch_when_clear_button_is_tapped() {
    // Arrange
    var clearInvoked = false
    composeTestRule.setContent {
      SearchAndFilterRow(
        searchQuery = "test",
        statusFilter = StatusFilterValue.ALL,
        sortOption = SortOption.DEFAULT,
        onSearchQueryChange = {},
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onClearSearch = { clearInvoked = true },
        onAddDeviceClick = {}
      )
    }

    // Act
    composeTestRule.onNodeWithContentDescription("Clear").performClick()

    // Assert
    assert(clearInvoked) { "Expected onClearSearch to be invoked" }
  }

  @Test
  fun should_invoke_onAddDeviceClick_when_add_button_is_tapped() {
    // Arrange
    var addInvoked = false
    composeTestRule.setContent {
      SearchAndFilterRow(
        searchQuery = "",
        statusFilter = StatusFilterValue.ALL,
        sortOption = SortOption.DEFAULT,
        onSearchQueryChange = {},
        onStatusFilterChange = {},
        onSortOptionChange = {},
        onClearSearch = {},
        onAddDeviceClick = { addInvoked = true }
      )
    }

    // Act
    composeTestRule.onNodeWithContentDescription("Add Device").performClick()

    // Assert
    assert(addInvoked) { "Expected onAddDeviceClick to be invoked" }
  }
}
