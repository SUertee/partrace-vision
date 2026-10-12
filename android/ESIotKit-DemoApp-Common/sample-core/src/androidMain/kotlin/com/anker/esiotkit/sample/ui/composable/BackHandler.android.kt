package com.anker.esiotkit.sample.ui.composable

import androidx.compose.runtime.Composable

/**
 * @version 2024/7/19
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
  androidx.activity.compose.BackHandler(enabled, onBack)
}