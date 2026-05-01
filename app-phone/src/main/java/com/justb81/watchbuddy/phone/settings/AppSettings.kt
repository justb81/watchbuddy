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
    val customAvatarVersion: Long = 0L,
    /**
     * When true, [com.justb81.watchbuddy.phone.llm.LlmProviderFactory] records
     * every on-device LLM invocation (prompt + response) into
     * [com.justb81.watchbuddy.phone.llm.LlmEventLog] so the Diagnostics screen
     * can surface them. Off-by-default would hide the feature; on-by-default
     * mirrors how the rest of the Diagnostics data is always captured.
     */
    val llmActivityLoggingEnabled: Boolean = true,
    /**
     * Two-letter ISO 3166-1 alpha-2 country code chosen by the user in Settings → Advanced.
     * Blank string means "Auto" — fall back to [java.util.Locale.getDefault] country.
     * Projected into `/capability` by [com.justb81.watchbuddy.phone.server.DeviceCapabilityProvider].
     */
    val countryOverride: String = ""
)
