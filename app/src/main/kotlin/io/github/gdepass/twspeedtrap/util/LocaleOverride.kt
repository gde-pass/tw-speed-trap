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

    fun resolve(
        context: Context,
        languageTag: String,
    ): Locale =
        if (languageTag == SYSTEM || languageTag.isBlank()) {
            context.resources.configuration.locales[0]
        } else {
            Locale.forLanguageTag(languageTag)
        }
}
