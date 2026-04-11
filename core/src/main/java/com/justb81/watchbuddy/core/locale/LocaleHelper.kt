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
}
