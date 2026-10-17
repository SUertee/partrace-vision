package com.anker.esiotkit.sample.ui.composable

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition

/**
 * @version 2024/11/21
 */
@Composable
actual fun PlatformNavigatorContent(navigator: Navigator) {
  SlideTransition(navigator)
}