package com.anker.esiotkit.sample.main

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import com.anker.esiotkit.core.config.ESIotAppConfig
import com.anker.esiotkit.mega.MegaIotKit
import com.anker.esiotkit.sample.support.i18n.LanguageManager
import com.anker.esiotkit.sample.main.station.StationSdkApp
import com.anker.esiotkit.sample.main.station.agent.TraceAgent
import com.anker.esiotkit.sample.main.station.ui.events.LocalStationBackendEventLauncher
import com.anker.esiotkit.support.appConfig

/**
@date: 2023/7/18
@desc:
 */
class ESlotKitMainActivity : AppCompatActivity() {
  private var stationPage = "home"

  override fun onStart() {
    super.onStart()
    TraceAgent.setActive(stationPage == "assistant")
  }

  override fun onStop() {
    TraceAgent.setActive(false)
    super.onStop()
  }

  @Suppress("DEPRECATION")
  override fun attachBaseContext(newBase: Context?) {
    val locale = LanguageManager.currentLocale
    val wrappedContext = if (newBase != null) {
      val config = Configuration(newBase.resources.configuration)
      config.setLocale(locale)
      newBase.createConfigurationContext(config)
    } else {
      newBase
    }
    super.attachBaseContext(wrappedContext)
  }

  @SuppressLint("SetTextI18n")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // WindowCompat.setDecorFitsSystemWindows(window, false)
    enableEdgeToEdge()
    initData()
    val preferences = getSharedPreferences("station_design", Context.MODE_PRIVATE)
    setContent {
      var showSdkTools by rememberSaveable { mutableStateOf(intent?.data?.scheme == "esiotkit") }
      LaunchedEffect(showSdkTools) {
        if (showSdkTools) { stationPage = "sdk"; TraceAgent.setActive(false) }
      }
      val screenState = rememberSaveableStateHolder()
      screenState.SaveableStateProvider(if (showSdkTools) "sdk" else "station") {
      if (showSdkTools) {
        Box(Modifier.fillMaxSize()) {
          SampleApp()
          TextButton(
            onClick = { showSdkTools = false },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
              .padding(bottom = 80.dp).background(Color.White)
          ) { Text("返回驿站界面") }
        }
      } else {
        CompositionLocalProvider(LocalStationBackendEventLauncher provides { eventId, recordingId, evidenceId ->
          startActivity(StationEventDetailActivity.intent(this@ESlotKitMainActivity, eventId, recordingId, evidenceId))
        }) {
        StationSdkApp(
          onSdkTools = { showSdkTools = true },
          onNotificationSettings = {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
              putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
          },
          nowMillis = System::currentTimeMillis,
          initialCampusMute = preferences.getLong("campus_mute_until", 0L),
          initialVenueMute = preferences.getLong("venue_mute_until", 0L),
          onScreenChanged = { page ->
            if (stationPage != page) {
              stationPage = page
              TraceAgent.setActive(page == "assistant" && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
              isAppearanceLightStatusBars = page !in setOf("home", "events", "profile")
              isAppearanceLightNavigationBars = true
            }
          },
          onMuteChanged = { station, until ->
            val key = if (station == "校园驿站") "campus_mute_until" else "venue_mute_until"
            preferences.edit().putLong(key, until).apply()
          }
        )
        }
      }
      }
    }
  }

  private fun initData() {
    appConfig = ESIotAppConfig().apply {
      context = applicationContext
    }
    MegaIotKit.start(appConfig)
    SampleAppEvent.addListener {
      when (it) {
        is SampleAppEvent.IntoDeviceSettings -> intoDeviceSettings(it.deviceSn)
        is SampleAppEvent.IntoPairing -> intoPairing(it.devicePn)
        is SampleAppEvent.ExitApp -> {
          // 先发送Idle清除replay缓存，避免下次打开APP时重放ExitApp事件
          SampleAppEvent.emit(SampleAppEvent.Idle)
          finish()
        }
        else -> {}
      }
    }
  }

  private fun intoDeviceSettings(deviceSn: String) {
  }

  private fun intoPairing(devicePn: String) {
  }
}
