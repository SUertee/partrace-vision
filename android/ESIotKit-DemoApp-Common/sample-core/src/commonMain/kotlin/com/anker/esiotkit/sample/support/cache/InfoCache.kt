package com.anker.esiotkit.sample.support.cache

import com.anker.esiotkit.sample.main.mega.login.MegaLoginViewModel
import com.anker.esiotkit.sample.support.log.SampleLogLevel
import com.anker.esiotkit.sample.support.model.LoginType
import com.anker.esiotkit.support.kv.KVSaverOwner
import com.anker.esiotkit.support.kv.booleanValue
import com.anker.esiotkit.support.kv.intValue
import com.anker.esiotkit.support.kv.stringValue

/**
 * @version 2024/6/13
 */
object InfoCache : KVSaverOwner("sample_info_cache") {
  var soundCoreToken by stringValue("")
  var soundCoreUserId by stringValue("")
  var tokenValid by booleanValue(false)
  var environment by stringValue(MegaLoginViewModel.ENVIRONMENT_US_PR)

  var loginType by intValue(LoginType.BUSINESS)
  var userConfig by stringValue("{}")

  var logLevel by intValue(SampleLogLevel.INFO)

  var pairingSsid by stringValue("")
  var pairingPassword by stringValue("")
  var selectedTestDeviceSn by stringValue("")

  /**
   * API 调用模式开关：true = 使用二次封装新 API，false = 使用旧 ThingProperty 直调
   * 可在侧边栏实时切换，用于对比验证新旧 API 行为一致性
   */
  var useNewApi by booleanValue(true)

  /**
   * 本地替换 JS 开关：true = 从 LocalJs/ 目录加载 JS 文件替代服务端下发的 JS
   */
  var localJsReplaceEnabled by booleanValue(false)

  /**
   * IJKPlayer 日志静默开关：true = 关闭 IJKPlayer native 日志打印（默认开启）
   * 开关语义：打开 = 禁止 IJKPlayer 输出 ffp/ffmpeg 日志
   */
  var ijkLogSilent by booleanValue(true)

  /** App 语言偏好：system / en / zh（多语言功能） */
  var language by stringValue("system")

  /** 多语言切换入口 Feature Flag：控制侧边栏 Language 条目可见性 */
  var i18nEntryEnabled by booleanValue(true)
}