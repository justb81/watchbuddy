package com.justb81.watchbuddy.core.deeplink

/**
 * Central mapping from TMDB [providerId] to the streaming app's package name.
 *
 * The static deep-link template catalog has been removed — per-episode deep links
 * are now sourced live from JustWatch (see `JustWatchDeepLinkRepository`).
 *
 * [byId] and [knownPackageNames] are still used to:
 *   - Drive [InstalledAppsProbe]'s PackageManager lookups.
 *   - Populate the `<queries>` block in `app-tv/AndroidManifest.xml`.
 */
data class ProviderEntry(
    val providerId: Int,
    val packageName: String,
)

object ProviderCatalog {

    val entries: List<ProviderEntry> = listOf(
        ProviderEntry(8, "com.netflix.ninja"),
        ProviderEntry(9, "com.amazon.amazonvideo.livingroom"),
        ProviderEntry(119, "com.amazon.amazonvideo.livingroom"),
        ProviderEntry(337, "com.disney.disneyplus"),
        ProviderEntry(350, "com.apple.atve.androidtv.appletv"),
        ProviderEntry(531, "com.cbs.app"),
        ProviderEntry(1899, "com.hbo.hbonow"),
        ProviderEntry(2187, "de.exaring.waipu"),
        ProviderEntry(2184, "de.prosiebensat1digital.seventv"),
        ProviderEntry(195, "de.swr.avp.ard.tv"),
        ProviderEntry(231, "com.zdf.android.mediathek"),
        ProviderEntry(192, "com.google.android.youtube.tv"),
        ProviderEntry(35, "tv.wuaki.apptv"),
    )

    /** Fast lookup by provider_id. */
    val byId: Map<Int, ProviderEntry> = entries.associateBy { it.providerId }

    /** Set of all known package names (for <queries> and PackageManager checks). */
    val knownPackageNames: Set<String> = entries.map { it.packageName }.toSet()
}
