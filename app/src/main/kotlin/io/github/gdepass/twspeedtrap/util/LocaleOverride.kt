package io.github.gdepass.twspeedtrap.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * App-language override without appcompat: wraps a Context so its resources
 * (and therefore getString + TTS phrasing) resolve in the chosen language.
 * "system" keeps the device locale. Used by both the activity and the
 * detection service, so spoken alerts follow the app language, not the
 * system one.
 */
object LocaleOverride {
    const val SYSTEM = "system"

    fun wrap(
        context: Context,
        languageTag: String,
    ): Context {
        if (languageTag == SYSTEM || languageTag.isBlank()) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(languageTag))
        return context.createConfigurationContext(configuration)
    }

    /** Wraps with the cached app language — for notifications posted from
     * contexts (receiver, bubble) that have no settings read at hand. */
    fun wrapCached(context: Context): Context =
        wrap(
            context,
            io.github.gdepass.twspeedtrap.data.SettingsRepository
                .peekLanguageTag(context) ?: SYSTEM,
        )

    /**
     * The locale the TTS voice must speak. With "system" this is not simply
     * the device locale: strings only ship in [SUPPORTED_LANGUAGES], so on a
     * zh-TW phone the alert text resolves to English and a Mandarin voice
     * would read English words. The voice follows whichever device language
     * the strings actually resolve to.
     */
    fun resolve(
        context: Context,
        languageTag: String,
    ): Locale {
        if (languageTag != SYSTEM && languageTag.isNotBlank()) return Locale.forLanguageTag(languageTag)
        val locales = context.resources.configuration.locales
        return speechLocale(List(locales.size()) { locales[it] })
    }

    /** First device locale whose language has a string bundle; English otherwise. */
    fun speechLocale(deviceLocales: List<Locale>): Locale =
        deviceLocales.firstOrNull { it.language in SUPPORTED_LANGUAGES } ?: Locale.ENGLISH

    /** Languages with a values[-xx]/strings.xml; keep in sync with res/. */
    val SUPPORTED_LANGUAGES = setOf("en", "fr")
}
