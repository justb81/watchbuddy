package com.justb81.watchbuddy.core.justwatch

import com.justb81.watchbuddy.core.logging.DiagnosticLog

/**
 * Maps JustWatch `technicalName` values to TMDB `provider_id` integers.
 *
 * Entries not present here are silently dropped — they will not produce deep links but
 * TMDB's watch-provider list still renders them with their logo and name.
 */
object JustWatchPackageMap {

    private const val TAG = "JustWatchPackageMap"

    val technicalNameToProviderId: Map<String, Int> = mapOf(
        "netflix" to 8, // Netflix
        "netflixbasicwithads" to 8, // Netflix with Ads (same app)
        "amazonprime" to 119, // Prime Video
        "amazonprimevideowithads" to 119, // Prime Video with Ads (same app)
        "disneyplus" to 337, // Disney+
        "appletvplus" to 350, // Apple TV+
        "paramountplus" to 531, // Paramount+
        "max" to 1899, // Max (HBO Max)
        "joynde" to 2184, // Joyn
        "daserstemediathek" to 195, // ARD Mediathek (free)
        "ardplus" to 195, // ARD+ (paid tier, same Android app)
        "zdf" to 231, // ZDF Mediathek
        "youtubered" to 192, // YouTube Premium
    )

    fun resolveProviderId(technicalName: String): Int? {
        val key = technicalName.lowercase()
        val id = technicalNameToProviderId[key]
        if (id == null) {
            DiagnosticLog.warn(TAG, "unmapped technicalName: $key")
        }
        return id
    }
}
