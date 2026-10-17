package com.anker.esiotkit.sample.support.i18n

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.anker.esiotkit.sample.support.cache.InfoCache
import com.anker.esiotkit.sample.support.sampleApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

actual object LanguageManager {

    private val _currentMode = MutableStateFlow(AppLanguage.SYSTEM)
    actual val currentMode: StateFlow<AppLanguage> = _currentMode

    private var currentActivity: Activity? = null

    val currentLocale: Locale get() = Locale.forLanguageTag(resolveEffectiveLanguage())

    internal var systemLanguageProvider: () -> String = {
        try {
            android.content.res.Resources.getSystem().configuration.locales.get(0)?.language ?: "en"
        } catch (_: Exception) {
            "en"
        }
    }

    actual fun initialize() {
        sampleApplication?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
                refreshActivityLocale(activity)
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivity == activity) currentActivity = null
            }
        })

        @Suppress("UNUSED_EXPRESSION")
        InfoCache.i18nEntryEnabled
        val stored = InfoCache.language
        val mode = AppLanguage.fromPersistedValue(stored)
        _currentMode.value = mode
        applyLocale(mode)
    }

    actual fun setLanguage(mode: AppLanguage) {
        _currentMode.value = mode
        InfoCache.language = mode.persistedValue
        applyLocale(mode)
    }

    actual fun resolveEffectiveLanguage(): String {
        return when (_currentMode.value) {
            AppLanguage.EN -> "en"
            AppLanguage.ZH -> "zh"
            AppLanguage.SYSTEM -> {
                val sysLang = systemLanguageProvider()
                if (sysLang in AppLanguage.SUPPORTED_TAGS) sysLang else "en"
            }
        }
    }

    private fun systemLanguageTag(): String = systemLanguageProvider()

    @Suppress("DEPRECATION")
    private fun refreshActivityLocale(activity: Activity) {
        val targetLocale = targetLocaleForMode(_currentMode.value)
        Locale.setDefault(targetLocale)
        val config = Configuration(activity.resources.configuration)
        config.setLocale(targetLocale)
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
    }

    @Suppress("DEPRECATION")
    private fun applyLocale(mode: AppLanguage) {
        val targetLocale = targetLocaleForMode(mode)

        Locale.setDefault(targetLocale)

        currentActivity?.let { activity ->
            val config = Configuration(activity.resources.configuration)
            config.setLocale(targetLocale)
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        }

        sampleApplication?.let { app ->
            val config = Configuration(app.resources.configuration)
            config.setLocale(targetLocale)
            app.resources.updateConfiguration(config, app.resources.displayMetrics)
        }

        when (mode) {
            AppLanguage.SYSTEM -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            else -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(mode.localeTag!!))
        }
    }

    private fun targetLocaleForMode(mode: AppLanguage): Locale = when (mode) {
        AppLanguage.SYSTEM -> {
            val sysLang = systemLanguageTag()
            if (sysLang in AppLanguage.SUPPORTED_TAGS) Locale.forLanguageTag(sysLang) else Locale.ENGLISH
        }
        else -> Locale.forLanguageTag(mode.localeTag!!)
    }
}
