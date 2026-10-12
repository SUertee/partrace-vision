package com.anker.esiotkit.sample.ui

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class LocaleHookProbeTest {

    @Test
    fun `forceEnglishLocale sets Locale to US`() {
        forceEnglishLocale()
        assertEquals(Locale.US, Locale.getDefault())
    }

    @Test
    fun `forceZhLocale sets Locale to SIMPLIFIED_CHINESE`() {
        forceZhLocale()
        assertEquals(Locale.SIMPLIFIED_CHINESE, Locale.getDefault())
    }
}
