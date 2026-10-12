package com.anker.esiotkit.sample.main.mega.login.business

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anker.esiotkit.ESIotKit
import com.anker.esiotkit.core.config.ESIotAppConfig
import com.anker.esiotkit.core.config.ESIotUserConfig
import com.anker.esiotkit.core.config.MegaAppDomain
import com.anker.esiotkit.mega.category.Category
import com.anker.esiotkit.sample.base.BaseScreenModel
import com.anker.esiotkit.sample.main.mega.login.onSampleHttpFailed
import com.anker.esiotkit.sample.support.applicationContext
import com.anker.esiotkit.sample.support.cache.BusinessInfoCache
import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.sample.support.log.SampleLogger
import com.anker.esiotkit.sample.support.model.LoginType
import com.anker.esiotkit.sample.support.perf.SdkPerfTracker
import com.anker.esiotkit.sample.support.utils.domain.DomainProfileDefaults
import korlibs.crypto.sha256

class BusinessLoginViewModel : BaseScreenModel() {
  var environment by mutableStateOf(BusinessLoginConfig.ENV_PR)

  var clientId by mutableStateOf(
    if (environment == BusinessLoginConfig.ENV_PR)
      BusinessInfoCache.prClientId.ifEmpty { BusinessLoginConfig.EnvPR.CLIENT_ID }
    else
      BusinessInfoCache.clientId.ifEmpty { BusinessLoginConfig.EnvQA.CLIENT_ID }
  )
  var appName by mutableStateOf(
    if (environment == BusinessLoginConfig.ENV_PR)
      BusinessInfoCache.prAppName.ifEmpty { BusinessLoginConfig.EnvPR.APP_NAME }
    else
      BusinessInfoCache.appName.ifEmpty { BusinessLoginConfig.EnvQA.APP_NAME }
  )
  var userId by mutableStateOf(
    if (environment == BusinessLoginConfig.ENV_PR)
      BusinessInfoCache.prUserId.ifEmpty { BusinessLoginConfig.EnvPR.USER_ID }
    else
      BusinessInfoCache.userId.ifEmpty { BusinessLoginConfig.EnvQA.USER_ID }
  )
  var token by mutableStateOf(
    if (environment == BusinessLoginConfig.ENV_PR)
      BusinessInfoCache.prToken.ifEmpty { BusinessLoginConfig.EnvPR.TOKEN }
    else
      BusinessInfoCache.token.ifEmpty { BusinessLoginConfig.EnvQA.TOKEN }
  )

  val host
    get() = if (environment == BusinessLoginConfig.ENV_QA)
      BusinessLoginConfig.EnvQA.APP_DOMAIN
    else
      BusinessLoginConfig.EnvPR.APP_DOMAIN

  val presetKey
    get() = if (environment == BusinessLoginConfig.ENV_QA)
      DomainProfileDefaults.QA
    else
      DomainProfileDefaults.US

  val deviceDomain
    get() = if (environment == BusinessLoginConfig.ENV_QA)
      BusinessLoginConfig.EnvQA.DEVICE_DOMAIN
    else
      BusinessLoginConfig.EnvPR.DEVICE_DOMAIN

  val clientSecret
    get() = if (environment == BusinessLoginConfig.ENV_QA)
      BusinessLoginConfig.EnvQA.CLIENT_SECRET
    else
      BusinessLoginConfig.EnvPR.CLIENT_SECRET

  fun switchToPR() {
    if (environment == BusinessLoginConfig.ENV_PR) return
    saveToCache()
    environment = BusinessLoginConfig.ENV_PR
    loadEnvironmentValues()
  }

  fun switchToQA() {
    if (environment == BusinessLoginConfig.ENV_QA) return
    saveToCache()
    environment = BusinessLoginConfig.ENV_QA
    loadEnvironmentValues()
  }

  private fun loadEnvironmentValues() {
    if (environment == BusinessLoginConfig.ENV_PR) {
      token = BusinessInfoCache.prToken.ifEmpty { BusinessLoginConfig.EnvPR.TOKEN }
      userId = BusinessInfoCache.prUserId.ifEmpty { BusinessLoginConfig.EnvPR.USER_ID }
      clientId = BusinessInfoCache.prClientId.ifEmpty { BusinessLoginConfig.EnvPR.CLIENT_ID }
      appName = BusinessInfoCache.prAppName.ifEmpty { BusinessLoginConfig.EnvPR.APP_NAME }
    } else {
      token = BusinessInfoCache.token.ifEmpty { BusinessLoginConfig.EnvQA.TOKEN }
      userId = BusinessInfoCache.userId.ifEmpty { BusinessLoginConfig.EnvQA.USER_ID }
      clientId = BusinessInfoCache.clientId.ifEmpty { BusinessLoginConfig.EnvQA.CLIENT_ID }
      appName = BusinessInfoCache.appName.ifEmpty { BusinessLoginConfig.EnvQA.APP_NAME }
    }
  }

  private fun saveToCache() {
    if (environment == BusinessLoginConfig.ENV_PR) {
      BusinessInfoCache.prToken = token
      BusinessInfoCache.prUserId = userId
      BusinessInfoCache.prClientId = clientId
      BusinessInfoCache.prAppName = appName
    } else {
      BusinessInfoCache.token = token
      BusinessInfoCache.userId = userId
      BusinessInfoCache.clientId = clientId
      BusinessInfoCache.appName = appName
    }
  }

  fun login() {
    val appConfig = ESIotAppConfig()
    appConfig.clientId = clientId
    appConfig.appName = appName
    appConfig.logger = SampleLogger.iotLogger
    appConfig.context = applicationContext
    SdkPerfTracker.measure("[BusinessLogin] ESIotKit.start()") {
      ESIotKit.start(appConfig)
    }
    val userConfig = ESIotUserConfig()
    userConfig.presetKey = presetKey
    userConfig.userId = userId
    userConfig.appDomain = MegaAppDomain().apply {
      setMegaDomain(host)
      setDomainBy(Category.Security, host, presetKey)
    }
    userConfig.deviceDomain = deviceDomain
    userConfig.authorization = token
    userConfig.onHttpFailed = onSampleHttpFailed
    ESIotKit.setUserConfig(userConfig)

    saveToCache()
    BusinessInfoCache.environment = environment
    InfoCache.loginType = LoginType.BUSINESS
    InfoCache.userConfig = userConfig.toString()
    InfoCache.tokenValid = true
  }

  private fun buildCredential(ts: String, once: String): String {
    val credential = clientId + ts + once + clientSecret
    return credential.encodeToByteArray().sha256().hex
  }

  private fun buildSignature(body: String) {
  }
}
