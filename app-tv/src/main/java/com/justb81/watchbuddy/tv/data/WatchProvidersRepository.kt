package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.deeplink.ProviderCatalogRegistry
import com.justb81.watchbuddy.core.model.ResolvedProvider
import com.justb81.watchbuddy.core.model.WatchProviderEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.tv.discovery.InstalledAppsProbe
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

/**
 * Fetches and caches TMDB `/tv/{id}/watch/providers` per-show per-region, then
 * composes the final ordered list by merging:
 *   1. Last-used provider for this show (from [LastUsedProviderRepository])
 *   2. Installed providers (from [InstalledAppsProbe] × [ProviderCatalogRegistry])
 *   3. Remaining providers (only when [showNonInstalled] is true)
 *
 * Providers are deduplicated; the last-used entry is always first when present.
 * TMDB flatrate + ads + free results are merged and deduplicated by provider_id.
 */
@Singleton
class WatchProvidersRepository @Inject constructor(
    private val tmdbApi: TmdbApiService,
    private val installedAppsProbe: InstalledAppsProbe,
    private val lastUsedRepo: LastUsedProviderRepository,
) {
    private data class CacheEntry(
        val providers: List<WatchProviderEntry>,
        val pageUrl: String?,
        val fetchedAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Returns ordered, filtered [ResolvedProvider] list for [tmdbId] in [countryCode].
     *
     * @throws Exception on network failure (callers catch and surface error state)
     */
    suspend fun getResolvedProviders(
        tmdbId: Int,
        countryCode: String,
        apiKey: String,
        showNonInstalled: Boolean,
    ): Pair<List<ResolvedProvider>, String?> {
        val cacheKey = "$tmdbId:$countryCode"
        val now = System.currentTimeMillis()
        val entry = cache[cacheKey]

        val (rawProviders, pageUrl) = if (entry != null && now - entry.fetchedAtMs < CACHE_TTL_MS) {
            entry.providers to entry.pageUrl
        } else {
            val response = tmdbApi.getWatchProviders(tmdbId, apiKey)
            val result = response.results[countryCode]
            val merged = mergeAndDedup(
                result?.flatrate.orEmpty(),
                result?.ads.orEmpty(),
                result?.free.orEmpty(),
            )
            val url = result?.link
            cache[cacheKey] = CacheEntry(merged, url, now)
            merged to url
        }

        val lastUsedId = lastUsedRepo.getLastUsedProviderId(tmdbId)
        val installed = installedAppsProbe.getInstalledPackages()

        return resolve(rawProviders, lastUsedId, installed, showNonInstalled, pageUrl) to pageUrl
    }

    private fun mergeAndDedup(vararg lists: List<WatchProviderEntry>): List<WatchProviderEntry> {
        val seen = mutableSetOf<Int>()
        return lists.flatMap { it }.filter { seen.add(it.providerId) }
            .sortedBy { it.displayPriority }
    }

    private fun resolve(
        raw: List<WatchProviderEntry>,
        lastUsedId: Int?,
        installed: Set<String>,
        showNonInstalled: Boolean,
        pageUrl: String?,
    ): List<ResolvedProvider> {
        val resolved = raw.map { entry ->
            val pkgName = ProviderCatalogRegistry.entryById(entry.providerId)?.packageName
            val isInstalled = pkgName != null && pkgName in installed
            ResolvedProvider(
                providerId = entry.providerId,
                name = entry.providerName,
                logoPath = TmdbImageHelper.logo(entry.logoPath),
                packageName = pkgName,
                isInstalled = isInstalled,
                isLastUsed = entry.providerId == lastUsedId,
                tmdbPageUrl = pageUrl,
            )
        }

        val lastUsed = resolved.filter { it.isLastUsed }
        val installedRest = resolved.filter { !it.isLastUsed && it.isInstalled }
        val notInstalled = if (showNonInstalled) resolved.filter { !it.isLastUsed && !it.isInstalled } else emptyList()

        return (lastUsed + installedRest + notInstalled).distinctBy { it.providerId }
    }
}
