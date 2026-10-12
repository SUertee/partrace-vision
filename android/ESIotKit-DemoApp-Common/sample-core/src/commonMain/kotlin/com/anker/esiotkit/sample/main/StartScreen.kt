package com.anker.esiotkit.sample.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import cafe.adriel.voyager.navigator.currentOrThrow
import com.anker.esiotkit.sample.main.mega.MegaMainScreen
import com.anker.esiotkit.sample.main.preview.UiPreviewApp
import com.anker.esiotkit.sample.main.mega.event.DownloadWorkerHost
import com.anker.esiotkit.sample.main.mega.login.MegaLoginScreen
import com.anker.esiotkit.sample.main.mega.login.business.BusinessLoginConfig
import com.anker.esiotkit.sample.main.mega.login.business.BusinessLoginViewModel
import com.anker.esiotkit.sample.support.cache.BusinessInfoCache
import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.sample.support.model.LoginType
import com.anker.esiotkit.sample.ui.composable.PlatformNavigatorContent
import com.anker.esiotkit.sample.view.CommonButton
import com.anker.esiotkit.sample.view.MegaToaster
import com.anker.esiotkit.sample.view.TitleBar
import com.anker.esiotkit.core.domain.repository.DeviceRepository
import com.anker.esiotkit.domain_thing.api.ESIotDeviceManager
import com.anker.esiotkit.domain_thing.api.internal.ESIotDeviceManagerImpl
import com.anker.esiotkit.domain_thing.model.device.MegaDevice
import com.anker.esiotkit.sample.main.mega.app_settings.logs.LogManagementViewModel
import com.anker.esiotkit.sample.main.mega.device_settings.device_capability.DeviceCapabilityViewModel
import com.anker.esiotkit.sample.demo.ai.RouterAiServiceImpl
import com.anker.esiotkit.sample.demo.ai.IAiAnalysisService
import com.anker.esiotkit.sample.demo.analyzer.DailyReportAnalyzerImpl
import com.anker.esiotkit.sample.demo.analyzer.SafetyAnalyzerImpl
import com.anker.esiotkit.sample.demo.data.EventDataSourceImpl
import com.anker.esiotkit.sample.demo.data.IEventDataSource
import com.anker.esiotkit.support.di.esInject
import com.anker.esiotkit.support.di.esKoin
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.koin.compose.KoinContext
import org.koin.dsl.module
import com.anker.esiotkit.demo.generated.resources.Res
import com.anker.esiotkit.demo.generated.resources.eslot_main_select_app_title

/**
 * @version 2024/3/12
 */

@Composable
fun SampleApp(uiPreview: Boolean = false) {
  var showPreview by remember { mutableStateOf(uiPreview) }
  if (showPreview) {
    UiPreviewApp(onExitPreview = { showPreview = false })
    return
  }
  SideEffect { init() }
  val canAutoLogin = remember { tryAutoLogin() }
  KoinContext(
    esKoin,
    content = {
      MaterialTheme(
        colorScheme = androidx.compose.material3.MaterialTheme.colorScheme.copy(
          primary = Color(0xFF005D8E)
        )
      ) {
        Navigator(
          screen = if (canAutoLogin) MegaMainScreen else MegaLoginScreen,
          disposeBehavior = NavigatorDisposeBehavior(),
          onBackPressed = { true }
        ) { navigator ->
          LaunchedEffect(Unit) {
            sampleAppEvent.collect {
              if (it is SampleAppEvent.IntoMainScreen) {
                navigator.push(MegaMainScreen)
              }
            }
          }
          PlatformNavigatorContent(navigator)
          // 下载队列 Worker 宿主 — App 级，页面销毁不影响下载
          DownloadWorkerHost()
          // val supportSwipeBack = remember { shouldUseSwipeBack }
          // if (supportSwipeBack) {
          //   VoyagerSwipeBackContent(navigator)
          // } else {
          //   SlideTransition(navigator)
          // }
        }
      }
      MegaToaster()
    }
  )
}

private var init = false
private val lock = SynchronizedObject()
private fun init() {
  if (!init) {
    synchronized(lock) {
      if (!init) {
        println("ESiotKit: Starting Koin init...")
        esKoin.loadModules(listOf(
          module {
            println("ESiotKit: Registering ESIotDeviceManager...")
            single<ESIotDeviceManager> {
              ESIotDeviceManagerImpl { sn ->
                esInject<DeviceRepository>().getIotDeviceBy(sn)
                  ?.let { MegaDevice(it) }
              }
            }
            factory { LogManagementViewModel() }
            factory { DeviceCapabilityViewModel(get()) }
            single<IEventDataSource> { EventDataSourceImpl() }
            single<IAiAnalysisService> { RouterAiServiceImpl() }
            single { SafetyAnalyzerImpl(get(), get()) }
            single { DailyReportAnalyzerImpl(get(), get()) }
          },
        ))
        println("ESiotKit: Koin init done")
        init = true
      }
    }
  }
}

object StartScreen : Screen {
  @Composable
  override fun Content() {
    val navigator = LocalNavigator.currentOrThrow
    Column(Modifier.fillMaxSize()) {
      TitleBar(title = Res.string.eslot_main_select_app_title, showBack = false)

      CommonButton(text = "MegaEufy") {
        navigator.push(MegaLoginScreen)
      }
    }

  }

}

private fun tryAutoLogin(): Boolean {
  val hasToken = if (BusinessInfoCache.environment == BusinessLoginConfig.ENV_PR)
    BusinessInfoCache.prToken.isNotEmpty()
  else
    BusinessInfoCache.token.isNotEmpty()
  if (InfoCache.tokenValid && InfoCache.loginType == LoginType.BUSINESS && hasToken) {
    BusinessLoginViewModel().login()
    return true
  }
  return false
}
