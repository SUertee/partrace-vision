package com.anker.esiotkit.sample.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T-UI-06: AddDeviceButton Compose UI 测试
 */
@RunWith(RobolectricTestRunner::class)
class AddDeviceButtonTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun should_display_add_icon_and_text_label() {
    // Arrange
    var clickCount = 0
    composeTestRule.setContent {
      AddDeviceButton(onClick = { clickCount++ })
    }

    // Act & Assert
    composeTestRule.onNodeWithContentDescription("添加设备").assertIsDisplayed()
    composeTestRule.onNodeWithText("添加设备").assertIsDisplayed()
  }

  @Test
  fun should_invoke_onClick_callback_when_tapped() {
    // Arrange
    var clickCount = 0
    composeTestRule.setContent {
      AddDeviceButton(onClick = { clickCount++ })
    }

    // Act
    composeTestRule.onNodeWithText("添加设备").performClick()

    // Assert
    assert(clickCount == 1) { "Expected onClick to be invoked once, but was $clickCount" }
  }
}
