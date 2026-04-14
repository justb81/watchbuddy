package com.justb81.watchbuddy.phone.settings

import com.justb81.watchbuddy.phone.ui.settings.AuthMode

data class AppSettings(
    val authMode: AuthMode = AuthMode.MANAGED,
    val backendUrl: String = "",
    val directClientId: String = "",
    val companionEnabled: Boolean = false,
    val modelDownloadUrl: String = "",
    val tmdbApiKey: String = "",
    /** True when a default TMDB API key was baked in at build time. */
    val defaultTmdbApiKeyAvailable: Boolean = false
)
