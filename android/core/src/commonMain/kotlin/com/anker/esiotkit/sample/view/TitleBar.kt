package com.anker.esiotkit.sample.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * @version 2024/3/12
 */
@Composable
fun TitleBar(
  modifier: Modifier = Modifier,
  title: StringResource,
  showBack: Boolean = true,
  rightIcon: @Composable RowScope.() -> Unit = {},
  onBackClick: (() -> Unit)? = null
) {
  TopAppBar(
    colors = TopAppBarDefaults.topAppBarColors().copy(containerColor = Color.White),
    modifier = modifier,
    title = { Text(text = stringResource(title)) },
    navigationIcon = {
      if (showBack) {
        Icon(
          painter = rememberVectorPainter(image = Icons.AutoMirrored.Filled.ArrowBack),
          contentDescription = "",
          modifier = Modifier.clickable {
            onBackClick?.invoke() ?: currentNavigator?.pop()
          }.padding(8.dp)
        )
      }
    },
    actions = rightIcon
  )
}

@Composable
fun TitleBar(
  modifier: Modifier = Modifier,
  title: String,
  showBack: Boolean = true,
  rightIcon: @Composable RowScope.() -> Unit = {},
  onBackClick: (() -> Unit)? = null
) {
  TopAppBar(
    colors = TopAppBarDefaults.topAppBarColors().copy(containerColor = Color.White),
    modifier = modifier,
    title = { Text(text = title) },
    navigationIcon = {
      if (showBack) {
        Icon(
          painter = rememberVectorPainter(image = Icons.AutoMirrored.Filled.ArrowBack),
          contentDescription = "",
          modifier = Modifier.clickable {
            onBackClick?.invoke() ?: currentNavigator?.pop()
          }.padding(8.dp)
        )
      }
    },
    actions = rightIcon
  )
}
