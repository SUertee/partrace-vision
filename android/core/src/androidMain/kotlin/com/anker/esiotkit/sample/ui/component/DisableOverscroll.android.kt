package com.anker.esiotkit.sample.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Android implementation to disable overscroll effect
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun DisableOverscrollEffect(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        content()
    }
}