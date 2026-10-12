package com.anker.esiotkit.sample.ui.composable

import androidx.compose.runtime.Composable

/**
 * @version 2024/7/19
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)