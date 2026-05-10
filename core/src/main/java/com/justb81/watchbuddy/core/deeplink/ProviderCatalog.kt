package com.justb81.watchbuddy.core.deeplink

import com.justb81.watchbuddy.core.model.ProviderCatalogSnapshot

/**
 * Thin façade for TMDB provider_id → TV package name lookups.
 *
 * The canonical data comes from the backend-served provider catalog (fetched and
 * cached by [com.justb81.watchbuddy.phone.data.ProviderCatalogRepository] on phone
 * and [com.justb81.watchbuddy.tv.data.TvProviderCatalogRepository] on TV).
 * When no live catalog is injected the object falls back to the in-code
 * [BUNDLED_ENTRIES] constant.
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

    @Volatile private var snapshot: ProviderCatalogSnapshot? = null

    fun updateFromSnapshot(catalog: ProviderCatalogSnapshot) {
        snapshot = catalog
    }

    val entries: List<ProviderEntry>
        get() = snapshot?.let { s ->
            s.providers.flatMap { p ->
                p.tmdbProviderIds.flatMap { id ->
                    p.androidPackages.tv.map { pkg -> ProviderEntry(id, pkg) }
                }
            }
        } ?: BUNDLED_ENTRIES

    val byId: Map<Int, ProviderEntry>
        get() = entries.associateBy { it.providerId }

    val knownPackageNames: Set<String>
        get() = entries.map { it.packageName }.toSet()

    private val BUNDLED_ENTRIES: List<ProviderEntry> = listOf(
        listOf(8) to "com.netflix.ninja",
        listOf(119, 9) to "com.amazon.amazonvideo.livingroom",
        listOf(337) to "com.disney.disneyplus",
        listOf(350) to "com.apple.atve.androidtv.appletv",
        listOf(531) to "com.cbs.app",
        listOf(1899) to "com.hbo.hbonow",
        listOf(2187) to "de.exaring.waipu",
        listOf(2184) to "de.prosiebensat1.joyn.tv",
        listOf(195) to "de.swr.avp.ard.tv",
        listOf(231) to "com.zdf.android.mediathek",
        listOf(192) to "com.google.android.youtube.tv",
        listOf(35) to "tv.wuaki.apptv",
    ).flatMap { (ids, pkg) -> ids.map { ProviderEntry(it, pkg) } }
}
