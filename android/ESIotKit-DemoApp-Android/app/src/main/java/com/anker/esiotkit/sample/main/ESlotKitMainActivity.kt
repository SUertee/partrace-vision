package com.anker.esiotkit.sample.main

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.anker.esiotkit.core.config.ESIotAppConfig
import com.anker.esiotkit.mega.MegaIotKit
import com.anker.esiotkit.sample.support.i18n.LanguageManager
import com.anker.esiotkit.support.appConfig

/**
@date: 2023/7/18
@desc:
 */
class ESlotKitMainActivity : AppCompatActivity() {

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
    setContent {
      SampleApp()
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
