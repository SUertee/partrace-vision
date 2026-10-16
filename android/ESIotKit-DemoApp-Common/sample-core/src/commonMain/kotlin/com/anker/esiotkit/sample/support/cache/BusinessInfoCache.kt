package com.anker.esiotkit.sample.support.cache

import com.anker.esiotkit.sample.main.mega.login.business.BusinessLoginConfig
import com.anker.esiotkit.support.kv.KVSaverOwner
import com.anker.esiotkit.support.kv.stringValue

object BusinessInfoCache : KVSaverOwner("business_info_cache") {
  var environment by stringValue(BusinessLoginConfig.DEFAULT_ENVIRONMENT)

  var token by stringValue(BusinessLoginConfig.EnvQA.TOKEN)
  var userId by stringValue(BusinessLoginConfig.EnvQA.USER_ID)
  var clientId by stringValue(BusinessLoginConfig.EnvQA.CLIENT_ID)
  var appName by stringValue(BusinessLoginConfig.EnvQA.APP_NAME)

  var prToken by stringValue(BusinessLoginConfig.EnvPR.TOKEN)
  var prUserId by stringValue(BusinessLoginConfig.EnvPR.USER_ID)
  var prClientId by stringValue(BusinessLoginConfig.EnvPR.CLIENT_ID)
  var prAppName by stringValue(BusinessLoginConfig.EnvPR.APP_NAME)
}