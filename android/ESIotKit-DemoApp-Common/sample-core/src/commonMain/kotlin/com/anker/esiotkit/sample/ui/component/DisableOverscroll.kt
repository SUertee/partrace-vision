package com.anker.esiotkit.sample.ui.component

import androidx.compose.runtime.Composable

/**
 * Wrapper to disable overscroll effect on different platforms
 * This helps fix the issue where scrolling to the boundary causes coordinate misalignment
 */
@Composable
expect fun DisableOverscrollEffect(content: @Composable () -> Unit)