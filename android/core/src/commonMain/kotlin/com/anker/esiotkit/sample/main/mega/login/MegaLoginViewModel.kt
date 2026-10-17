package com.anker.esiotkit.sample.main.mega.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anker.esiotkit.ESIotKit
import com.anker.esiotkit.core.config.ESIotAppConfig
import com.anker.esiotkit.core.config.ESIotUserConfig
import com.anker.esiotkit.core.config.IotKitConfig
import com.anker.esiotkit.mega.MegaIotKit
import com.anker.esiotkit.core.config.IotDebugConfig
import com.anker.esiotkit.compat.ESIotCompatSwitch
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.main.SampleAppEvent
import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.sample.support.applicationContext
import com.anker.esiotkit.sample.support.cache.BusinessInfoCache
import com.anker.esiotkit.sample.main.mega.login.business.BusinessLoginConfig
import com.anker.esiotkit.sample.support.perf.SdkPerfTracker
import com.anker.esiotkit.sample.support.log.SampleLogger
import com.anker.esiotkit.sample.support.model.LoginType
import com.anker.esiotkit.support.log.LogLevel
import com.anker.esiotkit.support.log.appLogger
import com.anker.esiotkit.support.log.httpLogger
import com.anker.esiotkit.support.utils.whenTrue

/**
 * @version 2024/5/1
 */
class MegaLoginViewModel : BaseScreenModel() {

  init {
    appLogger.i("MegaLoginViewModel/init/start: loginType=${InfoCache.loginType}, tokenValid=${InfoCache.tokenValid}, useNewApi=${InfoCache.useNewApi}")
    IotKitConfig.getParamsOnRefresh = true
    IotKitConfig.logLevel = LogLevel.INFO
    val appConfig = ESIotAppConfig()
    appConfig.logger = SampleLogger.iotLogger
    appConfig.appName = "eufy_mega"
    appConfig.context = applicationContext
    appConfig.iosP2PInner = true
    appConfig.logRequestWhenHappened = false
    appConfig.appVersion = "EufyDemoApp-1.2.0"
    // IotDebugConfig.debugThingModel = true
    IotDebugConfig.printParams = false
    IotDebugConfig.enableDetailDebugLog = false

    appLogger.i("MegaLoginViewModel/init/MegaIotKit.start/start")
    runCatching {
      SdkPerfTracker.measure("[MegaLogin] MegaIotKit.start()") {
        MegaIotKit.start(appConfig)
      }
    }.onSuccess {
      appLogger.i("MegaLoginViewModel/init/MegaIotKit.start/success")
    }.onFailure { e ->
      appLogger.e("MegaLoginViewModel/init/MegaIotKit.start/failure: ${e::class.simpleName}, msg=${e.message}")
    }
    appLogger.i("MegaLoginViewModel/init/ESIotKit.start/start")
    runCatching { ESIotKit.start(appConfig) }
      .onSuccess {
        appLogger.i("MegaLoginViewModel/init/ESIotKit.start/success")
      }
      .onFailure { e ->
        appLogger.e("MegaLoginViewModel/init/ESIotKit.start/failure: ${e::class.simpleName}, msg=${e.message}")
      }
    if (InfoCache.useNewApi) {
      ESIotCompatSwitch.enableNewApiCompat()
    } else {
      ESIotCompatSwitch.enableLegacyApiCompat()
    }
    if (InfoCache.tokenValid) {
      appLogger.i("MegaLoginViewModel/init/autoLogin: loginType=${InfoCache.loginType}")
      when (InfoCache.loginType) {
        LoginType.BUSINESS -> {
          InfoCache.userConfig.isNotEmpty().whenTrue {
            val userConfig = ESIotUserConfig.fromJson(InfoCache.userConfig)
            val appConfig = ESIotAppConfig()
            if (BusinessInfoCache.environment == BusinessLoginConfig.ENV_PR) {
              appConfig.clientId = BusinessInfoCache.prClientId
              appConfig.appName = BusinessInfoCache.prAppName
            } else {
              appConfig.clientId = BusinessInfoCache.clientId
              appConfig.appName = BusinessInfoCache.appName
            }
            appConfig.logger = SampleLogger.iotLogger
            appConfig.context = applicationContext
            userConfig.onHttpFailed = onSampleHttpFailed
            SdkPerfTracker.measure("[MegaLogin/Business] ESIotKit.start()") {
              ESIotKit.start(appConfig)
            }
            ESIotKit.setUserConfig(userConfig)
            SampleAppEvent.emit(SampleAppEvent.IntoMainScreen)
          }
        }
      }
    }
  }

  companion object {
    const val ENVIRONMENT_US_PR = "US-PR"
  }
}

internal val onSampleHttpFailed: (code: Int, url: String, body: String) -> Unit = { code, url, body ->
  if (code == 401) {
    appLogger.w("MegaLoginViewModel/httpFailed/401: url=$url, triggering auto logout")
    SampleAppEvent.emit(SampleAppEvent.Logout)
    SampleAppEvent.emit(SampleAppEvent.Idle)
    InfoCache.tokenValid = false
  }
  httpLogger.w("MegaLoginViewModel/httpFailed, code: $code, url: $url")
}
