package com.justb81.watchbuddy.phone.settings

import com.justb81.watchbuddy.core.model.AvatarSource
import com.justb81.watchbuddy.phone.ui.settings.AuthMode

data class AppSettings(
    val authMode: AuthMode = AuthMode.MANAGED,
    val backendUrl: String = "",
    val directClientId: String = "",
    val companionEnabled: Boolean = false,
    val modelDownloadUrl: String = "",
    val tmdbApiKey: String = "",
    /** True when a default TMDB API key was baked in at build time. */
    val defaultTmdbApiKeyAvailable: Boolean = false,
    /**
     * User-chosen display name that overrides the Trakt username on `/capability`.
     * Blank → fall back to the Trakt username.
     */
    val displayNameOverride: String = "",
    /** Which image the TV should show for this user — see [AvatarSource]. */
    val avatarSource: AvatarSource = AvatarSource.TRAKT,
    /**
     * Monotonic counter bumped each time the user picks a new custom photo.
     * Used as the `?v=N` query arg + ETag on `/avatar` so Coil on the TV
     * revalidates cheaply across updates.
     */
    val customAvatarVersion: Long = 0L
)
