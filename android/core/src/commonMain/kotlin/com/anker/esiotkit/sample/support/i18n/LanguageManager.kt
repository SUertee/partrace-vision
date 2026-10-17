package com.anker.esiotkit.sample.support.i18n

import kotlinx.coroutines.flow.StateFlow

/**
 * 语言管理器（KMP expect/actual）。
 * 职责：维护当前语言模式（StateFlow 驱动 UI）、持久化偏好到 InfoCache、向平台层下发 locale。
 */
expect object LanguageManager {
    /** 当前语言模式，UI 层 collect 此 Flow 驱动选中态更新 */
    val currentMode: StateFlow<AppLanguage>
    /** 初始化：从 InfoCache 恢复已存模式并应用。Application.onCreate 调用 */
    fun initialize()
    /** 设置语言模式：更新状态 + 持久化 + 下发平台 locale */
    fun setLanguage(mode: AppLanguage)
    /** 有效语言解析："en"/"zh"。SYSTEM 模式下系统语言属支持集则用之，否则 "en" */
    fun resolveEffectiveLanguage(): String
}
