package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.cache.TimedCachedResource
import com.justb81.watchbuddy.core.deeplink.ProviderCatalog
import com.justb81.watchbuddy.core.model.ResolvedProvider
import com.justb81.watchbuddy.core.model.WatchProviderEntry
import com.justb81.watchbuddy.core.model.WatchProviderResponse
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.tv.discovery.InstalledAppsProbe
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours

/**
 * Fetches and caches TMDB `/tv/{id}/watch/providers` per-show per-region, then
 * composes the final ordered list by merging:
 *   1. Last-used provider for this show (from [LastUsedProviderRepository])
 *   2. Installed providers (from [InstalledAppsProbe] × [ProviderCatalog])
 *   3. Remaining providers (only when [showNonInstalled] is true)
 *
 * Providers are deduplicated; the last-used entry is always first when present.
 * TMDB flatrate + ads + free results are merged and deduplicated by provider_id.
 * Responses are cached for 24 hours via [TimedCachedResource].
 */
@Singleton
class WatchProvidersRepository @Inject constructor(
    private val tmdbApi: TmdbApiService,
    private val installedAppsProbe: InstalledAppsProbe,
    private val lastUsedRepo: LastUsedProviderRepository,
) {
    private data class CacheKey(val tmdbId: Int, val countryCode: String, val apiKey: String)

    private val cache = TimedCachedResource<CacheKey, WatchProviderResponse>(
        ttlMillis = 24.hours.inWholeMilliseconds,
        fetcher = { key -> tmdbApi.getWatchProviders(key.tmdbId, key.apiKey) },
    )

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
        val response = cache.get(CacheKey(tmdbId, countryCode, apiKey))
        val result = response.results[countryCode]

        val rawProviders = mergeAndDedup(
            result?.flatrate.orEmpty(),
            result?.ads.orEmpty(),
            result?.free.orEmpty(),
        )
        val pageUrl = result?.link

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
            val catalog = ProviderCatalog.byId[entry.providerId]
            val pkgName = catalog?.packageName
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
