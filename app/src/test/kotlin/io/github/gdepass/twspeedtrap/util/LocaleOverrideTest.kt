package io.github.gdepass.twspeedtrap.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The TTS voice must speak the language the alert strings resolve to. */
class LocaleOverrideTest {
    @Test
    fun `zh-TW phone gets the English voice because strings fall back to English`() {
        assertEquals(Locale.ENGLISH, LocaleOverride.speechLocale(listOf(Locale.TAIWAN)))
    }

    @Test
    fun `zh-TW phone with French as a secondary language gets French`() {
        assertEquals(Locale.FRANCE, LocaleOverride.speechLocale(listOf(Locale.TAIWAN, Locale.FRANCE)))
    }

    @Test
    fun `regional variants of a shipped language keep their accent`() {
        assertEquals(Locale.CANADA_FRENCH, LocaleOverride.speechLocale(listOf(Locale.CANADA_FRENCH)))
        assertEquals(Locale.UK, LocaleOverride.speechLocale(listOf(Locale.UK)))
    }

    @Test
    fun `no device locale at all means English`() {
        assertEquals(Locale.ENGLISH, LocaleOverride.speechLocale(emptyList()))
    }
}
