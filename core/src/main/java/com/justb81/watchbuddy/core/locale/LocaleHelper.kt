package com.justb81.watchbuddy.core.locale

import java.util.Locale

/**
 * Resolves the display language name for LLM prompt instructions based on the device locale.
 * Supported locales: en, de, fr, es. Falls back to English for unsupported locales.
 */
object LocaleHelper {

    private val SUPPORTED_LANGUAGES = mapOf(
        "en" to "English",
        "de" to "German",
        "fr" to "French",
        "es" to "Spanish"
    )

    /**
     * Returns the English name of the device's current language if supported,
     * otherwise returns "English" as fallback.
     */
    fun getLlmResponseLanguage(locale: Locale = Locale.getDefault()): String {
        return SUPPORTED_LANGUAGES[locale.language] ?: "English"
    }
}
