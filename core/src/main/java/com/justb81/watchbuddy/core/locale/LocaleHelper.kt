package com.justb81.watchbuddy.core.locale

import java.util.Locale

/**
 * Resolves the display language name for LLM prompt instructions based on the device locale.
 * Uses the system locale to produce the English name of any language.
 */
object LocaleHelper {

    /**
     * Returns the English name of the device's current language (e.g. "Chinese", "Japanese", "Arabic").
     */
    fun getLlmResponseLanguage(locale: Locale = Locale.getDefault()): String {
        return locale.getDisplayLanguage(Locale.ENGLISH)
    }

    /**
     * Returns a TMDB-compatible BCP 47 language tag for the given locale,
     * e.g. "en-US", "de-DE", "fr-FR". Falls back to "en-US" for empty locales.
     *
     * Used to request locale-appropriate show/episode metadata from the TMDB API.
     */
    fun getTmdbLanguage(locale: Locale = Locale.getDefault()): String {
        val language = locale.language.takeIf { it.isNotEmpty() } ?: return "en-US"
        val country = locale.country.takeIf { it.isNotEmpty() }
        return if (country != null) "$language-$country" else language
    }
}
