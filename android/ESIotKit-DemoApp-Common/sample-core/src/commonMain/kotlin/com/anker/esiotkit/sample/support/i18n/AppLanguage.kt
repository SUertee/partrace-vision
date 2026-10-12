package com.anker.esiotkit.sample.support.i18n

/**
 * App 语言模式枚举。
 *
 * @param localeTag BCP-47 语言标签，用于 AppCompatDelegate.setApplicationLocales()
 *   SYSTEM 模式对应 null tag（传递空 LocaleList 跟随系统）。
 * @param persistedValue InfoCache 持久化值：system/en/zh。
 */
enum class AppLanguage(val localeTag: String?, val persistedValue: String) {
    SYSTEM(null, "system"),
    EN("en", "en"),
    ZH("zh", "zh");

    companion object {

        /** 支持的语言标签集合，用于 SYSTEM 模式下判断系统语言是否在支持范围内。 */
        val SUPPORTED_TAGS: Set<String> = setOf(EN.localeTag!!, ZH.localeTag!!)

        /**
         * 从 InfoCache 持久化值解析。
         * 合法值: "system" / "en" / "zh"。
         * 非法值（含 null、空串、未知字符串）回退 SYSTEM。
         */
        fun fromPersistedValue(value: String?): AppLanguage = when (value?.lowercase()) {
            "system" -> SYSTEM
            "en" -> EN
            "zh" -> ZH
            else -> SYSTEM
        }
    }
}
