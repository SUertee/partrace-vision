package com.anker.esiotkit.sample.view

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import com.anker.esiotkit.sample.main.mega.add_device.MegaAddressScreen.TitleRightIcon
import com.anker.esiotkit.sample.support.fullScreen
import com.anker.esiotkit.sample.ui.composable.appendWhen
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_common_untitled_title
import org.jetbrains.compose.resources.StringResource

/**
 * @version 2024/3/12
 */
var currentNavigator: Navigator? = null

abstract class BaseScreen : Screen {
  open val title: StringResource = Res.string.eslot_common_untitled_title

  /**
   * 动态标题钩子：设备名/SN 等运行时文本无法资源化。返回非 null 时 [BaseTitle] 优先渲染它。
   * Composable 形态以便实现方订阅属性 Flow（如 rememberStringProperty），设备改名后标题实时刷新。
   */
  @Composable
  open fun dynamicTitle(): String? = null

  open val showTitleBack = true
  override val key: ScreenKey = uniqueScreenKey

  @Composable
  open fun RowScope.TitleRightIcon() {
  }

  open fun onBackClick() {
    currentNavigator?.pop()
  }

  @Composable
  protected fun BaseColumn(
    paddingHorizontal: Dp = 16.dp,
    scrollState: ScrollState? = null,
    modifier: Modifier = Modifier,
    titleBar: Boolean = true,
    imePadding: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
  ) {
    initNavigator()
    val fullScreen by fullScreen.collectAsState()
    Column(
      modifier = modifier.fillMaxSize().background(Color.White).appendWhen(imePadding) {
        imePadding()
      }.appendWhen(!fullScreen) {
        navigationBarsPadding()
      }
    ) {
      if (titleBar) {
        BaseTitle(!fullScreen)
      }
      Column(
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = paddingHorizontal)
          .appendWhen(scrollState != null) {
            verticalScroll(scrollState!!)
          }
      ) {
        content()
      }
    }
  }

  @Composable
  protected fun BaseBox(
    modifier: Modifier = Modifier,
    paddingHorizontal: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit
  ) {
    initNavigator()
    val fullScreen by fullScreen.collectAsState()
    Box(
      modifier = modifier.fillMaxSize().background(Color.White)
        .appendWhen(!fullScreen) {
          statusBarsPadding().navigationBarsPadding()
        }.padding(horizontal = paddingHorizontal)
    ) {
      content()
    }
  }

  @Composable
  protected fun BaseTitle(
    statusBarPadding: Boolean = true
  ) {
    initNavigator()
    val dynTitle = dynamicTitle()
    if (dynTitle != null) {
      TitleBar(
        modifier = Modifier.appendWhen(statusBarPadding) { statusBarsPadding() },
        title = dynTitle,
        showBack = showTitleBack,
        rightIcon = { TitleRightIcon() },
        onBackClick = null
      )
    } else {
      TitleBar(
        modifier = Modifier.appendWhen(statusBarPadding) { statusBarsPadding() },
        title = title,
        showBack = showTitleBack,
        rightIcon = { TitleRightIcon() },
        onBackClick = null
      )
    }
  }

  @Composable
  private fun initNavigator() {
    if (currentNavigator !== LocalNavigator.current) {
      currentNavigator = LocalNavigator.current
    }
  }

}