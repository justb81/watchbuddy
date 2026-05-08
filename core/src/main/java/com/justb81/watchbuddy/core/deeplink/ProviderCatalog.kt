package com.justb81.watchbuddy.core.deeplink

import com.justb81.watchbuddy.core.model.ProviderCatalogSnapshot

/**
 * Thin façade for TMDB provider_id → TV package name lookups.
 *
 * The canonical data comes from the backend-served provider catalog (fetched and
 * cached by [com.justb81.watchbuddy.phone.data.ProviderCatalogRepository] on phone
 * and [com.justb81.watchbuddy.tv.data.TvProviderCatalogRepository] on TV).
 * When no live catalog is injected the object falls back to the bundled snapshot
 * baked in via [ProviderCatalogSnapshot].
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
                p.androidPackages.tv.map { pkg -> ProviderEntry(p.tmdbProviderId, pkg) }
            }
        } ?: BUNDLED_ENTRIES

    val byId: Map<Int, ProviderEntry>
        get() = entries.associateBy { it.providerId }

    val knownPackageNames: Set<String>
        get() = entries.map { it.packageName }.toSet()

    private val BUNDLED_ENTRIES: List<ProviderEntry> = listOf(
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
}
