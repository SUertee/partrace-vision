package com.anker.esiotkit.sample.ui

import com.anker.esiotkit.sample.support.i18n.AppLanguage
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class LocaleTestRule(private val language: AppLanguage) : TestWatcher() {

    override fun starting(description: Description?) {
        when (language) {
            AppLanguage.EN -> forceEnglishLocale()
            AppLanguage.ZH -> forceZhLocale()
            AppLanguage.SYSTEM -> {}
        }
    }
}
