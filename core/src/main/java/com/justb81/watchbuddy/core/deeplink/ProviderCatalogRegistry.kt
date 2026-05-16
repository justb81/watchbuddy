package com.justb81.watchbuddy.core.deeplink

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.CatalogAndroidPackages
import com.justb81.watchbuddy.core.model.CatalogProviderEntry
import com.justb81.watchbuddy.core.model.ProviderCatalogSnapshot

/**
 * A single streaming provider entry that pairs a TMDB [providerId] with an Android [packageName].
 * One [CatalogProviderEntry] can expand to multiple [ProviderEntry] objects when a provider has
 * multiple TMDB IDs or multiple TV packages.
 */
data class ProviderEntry(
    val providerId: Int,
    val packageName: String,
)

/**
 * Single source of truth for provider catalog lookups — owns one snapshot and
 * exposes both TMDB provider_id → package name and JustWatch technicalName → TMDB
 * provider_id lookups.
 *
 * The canonical data comes from the backend-served provider catalog (fetched and
 * cached by `ProviderCatalogRepository` on phone and `TvProviderCatalogRepository`
 * on TV). When no live catalog is injected the object falls back to [BUNDLED_SNAPSHOT].
 */
object ProviderCatalogRegistry {

    private const val TAG = "ProviderCatalogRegistry"

    @Volatile private var snapshot: ProviderCatalogSnapshot? = null

    fun updateFromSnapshot(catalog: ProviderCatalogSnapshot) {
        snapshot = catalog
    }

    // ── TMDB provider_id → ProviderEntry ─────────────────────────────────────

    val entries: List<ProviderEntry>
        get() = snapshot?.let { s ->
            s.providers.flatMap { p ->
                p.tmdbProviderIds.flatMap { id ->
                    p.androidPackages.tv.map { pkg -> ProviderEntry(id, pkg) }
                }
            }
        } ?: bundledEntries

    fun entryById(providerId: Int): ProviderEntry? = entries.firstOrNull { it.providerId == providerId }

    val knownPackageNames: Set<String>
        get() = entries.mapTo(mutableSetOf()) { it.packageName }

    // ── Package name → Set<providerId> ───────────────────────────────────────

    fun packagesByProviderId(providerId: Int): Set<String> =
        entries.filter { it.providerId == providerId }.mapTo(mutableSetOf()) { it.packageName }

    // ── JustWatch technicalName → TMDB provider_id ───────────────────────────

    val technicalNameToProviderId: Map<String, Int>
        get() = snapshot?.let { s ->
            buildMap {
                for (provider in s.providers) {
                    val canonicalId = provider.tmdbProviderIds.first()
                    for (name in provider.justWatchTechnicalNames) {
                        put(name.lowercase(), canonicalId)
                    }
                }
            }
        } ?: bundledTechnicalNameToProviderId

    fun providerIdByJustWatchName(technicalName: String): Int? {
        val key = technicalName.lowercase()
        val id = technicalNameToProviderId[key]
        if (id == null) {
            DiagnosticLog.warn(TAG, "unmapped technicalName: $key")
        }
        return id
    }

    // ── Bundled fallback ──────────────────────────────────────────────────────

    val BUNDLED_SNAPSHOT: ProviderCatalogSnapshot = ProviderCatalogSnapshot(
        version = 0,
        lastUpdated = "bundled",
        providers = listOf(
            CatalogProviderEntry(
                tmdbProviderIds = listOf(8),
                name = "Netflix",
                regions = listOf("*"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("com.netflix.ninja"),
                    phone = listOf("com.netflix.mediaclient"),
                ),
                justWatchTechnicalNames = listOf("netflix", "netflixbasicwithads"),
            ),
            CatalogProviderEntry(
                tmdbProviderIds = listOf(119, 9),
                name = "Amazon Prime Video",
                regions = listOf("*"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("com.amazon.amazonvideo.livingroom"),
                    phone = listOf("com.amazon.avod.thirdpartyclient"),
                ),
                justWatchTechnicalNames = listOf("amazonprime", "amazonprimevideowithads"),
            ),
            CatalogProviderEntry(
                tmdbProviderIds = listOf(337),
                name = "Disney+",
                regions = listOf("*"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("com.disney.disneyplus"),
                    phone = listOf("com.disney.disneyplus"),
                ),
                justWatchTechnicalNames = listOf("disneyplus"),
            ),
            CatalogProviderEntry(
                tmdbProviderIds = listOf(350),
                name = "Apple TV+",
                regions = listOf("*"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("com.apple.atve.androidtv.appletv"),
                    phone = listOf("com.apple.atve.androidtv.appletv"),
                ),
                justWatchTechnicalNames = listOf("appletvplus"),
            ),
            CatalogProviderEntry(
                tmdbProviderIds = listOf(531),
                name = "Paramount+",
                regions = listOf("*"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("com.cbs.app"),
                    phone = listOf("com.cbs.app"),
                ),
                justWatchTechnicalNames = listOf("paramountplus"),
            ),
            CatalogProviderEntry(
                tmdbProviderIds = listOf(1899),
                name = "Max",
                regions = listOf("*"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("com.hbo.hbonow"),
                    phone = listOf("com.hbo.hbonow"),
                ),
                justWatchTechnicalNames = listOf("max"),
            ),
            CatalogProviderEntry(
                tmdbProviderIds = listOf(1825),
                name = "Max Amazon Channel",
                regions = listOf("*"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("com.amazon.amazonvideo.livingroom"),
                    phone = listOf("com.amazon.avod.thirdpartyclient"),
                ),
                justWatchTechnicalNames = listOf("amazonhbomax"),
            ),
            CatalogProviderEntry(
                tmdbProviderIds = listOf(2187),
                name = "WaipuTV",
                regions = listOf("DE", "AT"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("de.exaring.waipu"),
                    phone = listOf("de.exaring.waipu"),
                ),
                justWatchTechnicalNames = emptyList(),
            ),
            CatalogProviderEntry(
                tmdbProviderIds = listOf(2184),
                name = "Joyn",
                regions = listOf("DE", "AT"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("de.prosiebensat1.joyn.tv"),
                    phone = listOf("de.prosiebensat1.joyn"),
                ),
                justWatchTechnicalNames = listOf("joynde"),
            ),
            CatalogProviderEntry(
                tmdbProviderIds = listOf(195),
                name = "ARD Mediathek",
                regions = listOf("DE", "AT", "CH"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("de.swr.avp.ard.tv"),
                    phone = listOf("de.swr.avp.ard"),
                ),
                justWatchTechnicalNames = listOf("daserstemediathek", "ardplus"),
            ),
            CatalogProviderEntry(
                tmdbProviderIds = listOf(231),
                name = "ZDF Mediathek",
                regions = listOf("DE", "AT", "CH"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("com.zdf.android.mediathek"),
                    phone = listOf("com.zdf.android.mediathek"),
                ),
                justWatchTechnicalNames = listOf("zdf"),
            ),
            CatalogProviderEntry(
                tmdbProviderIds = listOf(192),
                name = "YouTube",
                regions = listOf("*"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("com.google.android.youtube.tv"),
                    phone = listOf("com.google.android.youtube"),
                ),
                justWatchTechnicalNames = listOf("youtubered", "yti", "yot", "ytv"),
            ),
            CatalogProviderEntry(
                tmdbProviderIds = listOf(35),
                name = "Rakuten TV",
                regions = listOf("*"),
                androidPackages = CatalogAndroidPackages(
                    tv = listOf("tv.wuaki.apptv"),
                    phone = listOf("tv.wuaki"),
                ),
                justWatchTechnicalNames = emptyList(),
            ),
        ),
    )

    private val bundledEntries: List<ProviderEntry> by lazy {
        BUNDLED_SNAPSHOT.providers.flatMap { p ->
            p.tmdbProviderIds.flatMap { id ->
                p.androidPackages.tv.map { pkg -> ProviderEntry(id, pkg) }
            }
        }
    }

    private val bundledTechnicalNameToProviderId: Map<String, Int> by lazy {
        buildMap {
            for (provider in BUNDLED_SNAPSHOT.providers) {
                val canonicalId = provider.tmdbProviderIds.first()
                for (name in provider.justWatchTechnicalNames) {
                    put(name.lowercase(), canonicalId)
                }
            }
        }
    }
}
