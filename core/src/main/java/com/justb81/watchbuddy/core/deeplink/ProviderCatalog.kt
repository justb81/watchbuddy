package com.justb81.watchbuddy.core.deeplink

/**
 * Central mapping from TMDB [providerId] to the streaming app's package name and
 * deep-link template. Keyed by the TMDB provider_id integer so it can be
 * cross-referenced against [WatchProviderResponse] results without any manual
 * service-selection UI.
 *
 * Templates support three placeholders substituted at runtime:
 *   {tmdb_id}  — TMDB series ID (Int)
 *   {slug}     — Trakt slug (or title-derived fallback)
 *   {id}       — alias for {tmdb_id} (legacy compat)
 *
 * Services not present here are still shown from TMDB data (with name + logo),
 * but launch falls back to the TMDB watch-providers page URL.
 */
data class ProviderEntry(
    val providerId: Int,
    val name: String,
    val packageName: String,
    val deepLinkTemplate: String,
)

object ProviderCatalog {

    val entries: List<ProviderEntry> = listOf(
        ProviderEntry(8,    "Netflix",        "com.netflix.ninja",                    "https://www.netflix.com/title/{tmdb_id}"),
        ProviderEntry(9,    "Amazon Prime",   "com.amazon.amazonvideo.livingroom",     "https://www.primevideo.com/search?phrase={slug}"),
        ProviderEntry(119,  "Prime Video",    "com.amazon.amazonvideo.livingroom",     "https://www.primevideo.com/search?phrase={slug}"),
        ProviderEntry(337,  "Disney+",        "com.disney.disneyplus",                "https://www.disneyplus.com/series/{slug}/{tmdb_id}"),
        ProviderEntry(350,  "Apple TV+",      "com.apple.atve.androidtv.appletv",     "https://tv.apple.com/show/{tmdb_id}"),
        ProviderEntry(531,  "Paramount+",     "com.cbs.app",                          "https://www.paramountplus.com/shows/{slug}/"),
        ProviderEntry(1899, "Max",            "com.hbo.hbonow",                       "https://play.max.com/show/{tmdb_id}"),
        ProviderEntry(2187, "WaipuTV",        "tv.waipu.app",                         "waipu://tv"),
        ProviderEntry(2184, "Joyn",           "de.prosiebensat1digital.android.joyn", "https://www.joyn.de/serien/{slug}"),
        ProviderEntry(195,  "ARD Mediathek",  "de.swr.avp.ard.phone",                 "https://www.ardmediathek.de/video/{id}"),
        ProviderEntry(231,  "ZDF Mediathek",  "de.zdf.android.app",                   "https://www.zdf.de/serien/{slug}"),
    )

    /** Fast lookup by provider_id. */
    val byId: Map<Int, ProviderEntry> = entries.associateBy { it.providerId }

    /** Set of all known package names (for <queries> and PackageManager checks). */
    val knownPackageNames: Set<String> = entries.map { it.packageName }.toSet()
}
