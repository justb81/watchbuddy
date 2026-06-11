package com.justb81.watchbuddy.core.tracking

/**
 * Identifies the active watch-tracking backend. Trakt and SIMKL are mutually
 * exclusive selections in v1 — no simultaneous dual-sync.
 */
enum class TrackingBackend { TRAKT, SIMKL }

/**
 * Minimal profile information returned from the active tracking provider.
 * Used by [com.justb81.watchbuddy.phone.server.DeviceCapabilityProvider] to
 * populate the `/capability` endpoint's `userName` and `userAvatarUrl`.
 */
data class TrackingProfile(
    val username: String,
    val avatarUrl: String?
)
