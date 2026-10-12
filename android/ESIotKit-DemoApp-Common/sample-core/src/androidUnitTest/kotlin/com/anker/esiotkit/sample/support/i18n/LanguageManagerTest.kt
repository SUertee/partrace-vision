package com.anker.esiotkit.sample.support.i18n

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals

class LanguageManagerTest {

    private val originalProvider = LanguageManager.systemLanguageProvider

    @After
    fun restoreProvider() {
        LanguageManager.systemLanguageProvider = originalProvider
    }

    // region fromPersistedValue

    @Test
    fun `fromPersistedValue null returns SYSTEM`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromPersistedValue(null))
    }

    @Test
    fun `fromPersistedValue empty returns SYSTEM`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromPersistedValue(""))
    }

    @Test
    fun `fromPersistedValue unknown returns SYSTEM`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromPersistedValue("fr"))
    }

    @Test
    fun `fromPersistedValue en returns EN`() {
        assertEquals(AppLanguage.EN, AppLanguage.fromPersistedValue("en"))
    }

    @Test
    fun `fromPersistedValue zh returns ZH`() {
        assertEquals(AppLanguage.ZH, AppLanguage.fromPersistedValue("zh"))
    }

    @Test
    fun `fromPersistedValue system returns SYSTEM`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromPersistedValue("system"))
    }

    @Test
    fun `fromPersistedValue case insensitive`() {
        assertEquals(AppLanguage.EN, AppLanguage.fromPersistedValue("EN"))
        assertEquals(AppLanguage.ZH, AppLanguage.fromPersistedValue("ZH"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromPersistedValue("SYSTEM"))
    }

    // endregion

    // region resolveEffectiveLanguage

    @Test
    fun `resolveEffectiveLanguage EN mode returns en`() {
        try { LanguageManager.setLanguage(AppLanguage.EN) } catch (_: Throwable) {}
        assertEquals("en", LanguageManager.resolveEffectiveLanguage())
    }

    @Test
    fun `resolveEffectiveLanguage ZH mode returns zh`() {
        try { LanguageManager.setLanguage(AppLanguage.ZH) } catch (_: Throwable) {}
        assertEquals("zh", LanguageManager.resolveEffectiveLanguage())
    }

    @Test
    fun `resolveEffectiveLanguage SYSTEM with fr provider returns en`() {
        LanguageManager.systemLanguageProvider = { "fr" }
        try { LanguageManager.setLanguage(AppLanguage.SYSTEM) } catch (_: Throwable) {}
        assertEquals("en", LanguageManager.resolveEffectiveLanguage())
    }

    @Test
    fun `resolveEffectiveLanguage SYSTEM with zh provider returns zh`() {
        LanguageManager.systemLanguageProvider = { "zh" }
        try { LanguageManager.setLanguage(AppLanguage.SYSTEM) } catch (_: Throwable) {}
        assertEquals("zh", LanguageManager.resolveEffectiveLanguage())
    }

    @Test
    fun `resolveEffectiveLanguage SYSTEM with en provider returns en`() {
        LanguageManager.systemLanguageProvider = { "en" }
        try { LanguageManager.setLanguage(AppLanguage.SYSTEM) } catch (_: Throwable) {}
        assertEquals("en", LanguageManager.resolveEffectiveLanguage())
    }

    // endregion

    // region currentMode

    @Test
    fun `currentMode default is SYSTEM`() {
        try { LanguageManager.setLanguage(AppLanguage.SYSTEM) } catch (_: Throwable) {}
        assertEquals(AppLanguage.SYSTEM, LanguageManager.currentMode.value)
    }

    @Test
    fun `currentMode after set EN is EN`() {
        try { LanguageManager.setLanguage(AppLanguage.EN) } catch (_: Throwable) {}
        assertEquals(AppLanguage.EN, LanguageManager.currentMode.value)
    }

    @Test
    fun `currentMode after set ZH is ZH`() {
        try { LanguageManager.setLanguage(AppLanguage.ZH) } catch (_: Throwable) {}
        assertEquals(AppLanguage.ZH, LanguageManager.currentMode.value)
    }

    // endregion
}
