package com.justb81.watchbuddy.core.justwatch

/**
 * Maps JustWatch `technicalName` values to TMDB `provider_id` integers.
 *
 * Entries not present here are silently dropped — they will not produce deep links but
 * TMDB's watch-provider list still renders them with their logo and name.
 */
object JustWatchPackageMap {

    val technicalNameToProviderId: Map<String, Int> = mapOf(
        "nfx" to 8, // Netflix
        "prv" to 119, // Prime Video
        "dnp" to 337, // Disney+
        "atp" to 350, // Apple TV+
        "pmp" to 531, // Paramount+
        "hbm" to 1899, // Max (legacy HBO Max id)
        "max" to 1899, // Max
        "jyn" to 2184, // Joyn
        "wpu" to 2187, // WaipuTV
        "ard" to 195, // ARD Mediathek
        "zdf" to 231, // ZDF Mediathek
    )

    fun resolveProviderId(technicalName: String): Int? =
        technicalNameToProviderId[technicalName.lowercase()]
}
