package com.anker.esiotkit.sample.main.mega.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.main.mega.login.business.BusinessLoginScreen
import com.anker.esiotkit.sample.support.exitSampleApplication
import com.anker.esiotkit.sample.ui.component.MegaLoading
import com.anker.esiotkit.sample.ui.component.PagerHeader
import com.anker.esiotkit.sample.ui.component.PagerIndicator
import com.anker.esiotkit.sample.ui.composable.BackHandler
import com.anker.esiotkit.sample.ui.composable.touchToHideKeyboard
import kotlinx.coroutines.launch

/**
 * @version 2024/3/12
 */
object MegaLoginScreen : Screen {

  @Composable
  override fun Content() {
    val viewModel = rememberScreenModel { MegaLoginViewModel() }
    val navigator = LocalNavigator.currentOrThrow
    Column(Modifier.fillMaxSize().touchToHideKeyboard().statusBarsPadding().navigationBarsPadding()) {
      val pageHeaders = remember {
        listOf(
          PagerHeader("Business"),
        )
      }
      val pageState = rememberPagerState { pageHeaders.size }

      val scope = rememberCoroutineScope()
      Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        PagerIndicator(
          modifier = Modifier.fillMaxWidth(),
          currentPage = pageState.currentPage,
          list = pageHeaders,
        ) {
          scope.launch {
            pageState.animateScrollToPage(it)
          }
        }
        HorizontalPager(
          modifier = Modifier.imePadding(),
          state = pageState,
        ) {
          BusinessLoginScreen.Content()
        }
      }

      BackHandler {
        exitSampleApplication()
      }
    }
    MegaLoading(viewModel.loading)
  }

}