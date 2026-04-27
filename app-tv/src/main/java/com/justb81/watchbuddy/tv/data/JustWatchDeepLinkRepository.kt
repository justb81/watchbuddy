package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.justwatch.JustWatchApiService
import com.justb81.watchbuddy.core.justwatch.JustWatchGraphQlRequest
import com.justb81.watchbuddy.core.justwatch.JustWatchOffer
import com.justb81.watchbuddy.core.justwatch.JustWatchPackageMap
import com.justb81.watchbuddy.core.justwatch.JustWatchTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private val ALLOWED_MONETIZATION = setOf("FLATRATE", "ADS", "FREE")
private const val SEARCH_RESULT_LIMIT = 5

/**
 * Resolves JustWatch per-episode deep links with a persistent Room cache.
 *
 * Resolution cascade for each (showId, season, episode, providerId, countryCode):
 *   1. Episode-level Room cache lookup
 *   2. Episode-level live JustWatch call on miss → caches all providers found
 *   3. Show-level Room cache lookup (season=0, episode=0)
 *   4. Show-level live JustWatch call on miss → caches all providers found
 *   5. Returns null → caller treats as Unavailable
 *
 * Positive hits are cached permanently; negative entries expire after 30 days.
 *
 * Batch dedup: a Mutex-protected map prevents duplicate in-flight JustWatch API calls
 * for the same (showId, season, episode, countryCode) key.
 */
@Singleton
class JustWatchDeepLinkRepository @Inject constructor(
    private val dao: JustWatchDeepLinkDao,
    private val api: JustWatchApiService,
) {

    private data class FetchKey(
        val tmdbShowId: Int,
        val season: Int,
        val episode: Int,
        val countryCode: String,
    )

    private val fetchMutexMap = mutableMapOf<FetchKey, Mutex>()
    private val mapLock = Mutex()

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun resolveDeepLink(
        tmdbShowId: Int,
        season: Int,
        episode: Int,
        providerId: Int,
        countryCode: String,
        showTitle: String,
    ): String? {
        // 1. Episode-level cache
        val epCached = dao.get(tmdbShowId, season, episode, providerId, countryCode)
        if (epCached != null && epCached.isValidCache()) return epCached.standardWebUrl

        // 2. Episode-level live fetch (deduped per episode)
        val episodeKey = FetchKey(tmdbShowId, season, episode, countryCode)
        getMutex(episodeKey).withLock {
            // Re-check cache after acquiring lock — another coroutine may have populated it
            val refreshed = dao.get(tmdbShowId, season, episode, providerId, countryCode)
            if (refreshed != null && refreshed.isValidCache()) return refreshed.standardWebUrl
            fetchAndCacheEpisodeOffers(tmdbShowId, season, episode, countryCode, showTitle)
        }

        // Re-read after episode fetch
        val epResult = dao.get(tmdbShowId, season, episode, providerId, countryCode)
        if (epResult != null && epResult.isValidCache()) return epResult.standardWebUrl

        return resolveShowLevel(tmdbShowId, providerId, countryCode, showTitle)
    }

    suspend fun count(): Int = dao.countPositive()

    suspend fun negativeCount(): Int = dao.countNegative()

    suspend fun lastFetchedAt(): Long? = dao.lastFetchedAt()

    suspend fun clearAll() = dao.deleteAll()

    // ── Internal helpers ──────────────────────────────────────────────────────

    private suspend fun resolveShowLevel(
        tmdbShowId: Int,
        providerId: Int,
        countryCode: String,
        showTitle: String,
    ): String? {
        // 3. Show-level cache
        val showCached = dao.get(tmdbShowId, 0, 0, providerId, countryCode)
        if (showCached != null && showCached.isValidCache()) return showCached.standardWebUrl

        // 4. Show-level live fetch (deduped)
        val showKey = FetchKey(tmdbShowId, 0, 0, countryCode)
        getMutex(showKey).withLock {
            val refreshed = dao.get(tmdbShowId, 0, 0, providerId, countryCode)
            if (refreshed != null && refreshed.isValidCache()) return refreshed.standardWebUrl
            fetchAndCacheShowOffers(tmdbShowId, countryCode, showTitle)
        }

        return dao.get(tmdbShowId, 0, 0, providerId, countryCode)?.standardWebUrl
    }

    private fun JustWatchDeepLink.isValidCache(): Boolean =
        standardWebUrl != null || !isNegativeExpired(this)

    private suspend fun getMutex(key: FetchKey): Mutex = mapLock.withLock {
        fetchMutexMap.getOrPut(key) { Mutex() }
    }

    private fun isNegativeExpired(entry: JustWatchDeepLink): Boolean =
        entry.standardWebUrl == null &&
            System.currentTimeMillis() - entry.fetchedAt >= JustWatchDeepLink.NEGATIVE_TTL_MS

    private suspend fun fetchAndCacheEpisodeOffers(
        tmdbShowId: Int,
        season: Int,
        episode: Int,
        countryCode: String,
        showTitle: String,
    ) {
        try {
            val language = "en"
            val jwNode = searchShowByTmdbId(tmdbShowId, showTitle, countryCode, language) ?: run {
                cacheNegativeForAllKnownProviders(tmdbShowId, season, episode, countryCode)
                return
            }

            // Cache show-level offers while we have the show node
            cacheOffers(jwNode.offers, tmdbShowId, 0, 0, countryCode)

            // Fetch seasons
            val seasonsResp = api.query(
                JustWatchGraphQlRequest(
                    query = JustWatchApiService.SEASONS_QUERY,
                    variables = buildJsonObject {
                        put("nodeId", jwNode.id)
                        put("country", countryCode.uppercase())
                        put("language", language)
                    },
                )
            )
            val jwSeasons = seasonsResp.data?.node?.seasons
            if (jwSeasons.isNullOrEmpty()) {
                cacheNegativeForAllKnownProviders(tmdbShowId, season, episode, countryCode)
                return
            }

            // Match season by index (season 1 → index 0); JustWatch orders from S1
            val seasonIndex = (season - 1).coerceIn(0, jwSeasons.size - 1)
            val seasonId = jwSeasons[seasonIndex].id

            // Fetch episodes for this season
            val episodesResp = api.query(
                JustWatchGraphQlRequest(
                    query = JustWatchApiService.EPISODES_QUERY,
                    variables = buildJsonObject {
                        put("nodeId", seasonId)
                        put("country", countryCode.uppercase())
                        put("language", language)
                    },
                )
            )
            val jwEpisodes = episodesResp.data?.node?.episodes
            if (jwEpisodes.isNullOrEmpty()) {
                cacheNegativeForAllKnownProviders(tmdbShowId, season, episode, countryCode)
                return
            }

            val jwEp = jwEpisodes.find { it.seasonNumber == season && it.episodeNumber == episode }
            if (jwEp != null) {
                cacheOffers(jwEp.offers, tmdbShowId, season, episode, countryCode)
            } else {
                cacheNegativeForAllKnownProviders(tmdbShowId, season, episode, countryCode)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Network/parse failure: do not cache negatives so the next attempt retries
        }
    }

    private suspend fun fetchAndCacheShowOffers(
        tmdbShowId: Int,
        countryCode: String,
        showTitle: String,
    ) {
        try {
            val jwNode = searchShowByTmdbId(tmdbShowId, showTitle, countryCode, "en") ?: run {
                cacheNegativeForAllKnownProviders(tmdbShowId, 0, 0, countryCode)
                return
            }
            cacheOffers(jwNode.offers, tmdbShowId, 0, 0, countryCode)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Network/parse failure: do not cache negatives
        }
    }

    /** Searches JustWatch for a show by title and verifies the TMDB ID matches. */
    private suspend fun searchShowByTmdbId(
        tmdbShowId: Int,
        showTitle: String,
        countryCode: String,
        language: String,
    ): JustWatchTitle? {
        val response = api.query(
            JustWatchGraphQlRequest(
                query = JustWatchApiService.SEARCH_QUERY,
                variables = buildJsonObject {
                    put("searchQuery", showTitle)
                    put("country", countryCode.uppercase())
                    put("language", language)
                    put("first", SEARCH_RESULT_LIMIT)
                },
            )
        )
        return response.data?.popularTitles?.edges
            ?.map { it.node }
            ?.firstOrNull { node ->
                node.content?.externalIds?.tmdbId == tmdbShowId.toString()
            }
    }

    /** Converts JustWatch offer list to Room entries and upserts them. */
    private suspend fun cacheOffers(
        offers: List<JustWatchOffer>,
        tmdbShowId: Int,
        season: Int,
        episode: Int,
        countryCode: String,
    ) {
        val now = System.currentTimeMillis()
        // Group by providerId to pick first valid URL per provider
        val providerUrls = mutableMapOf<Int, String>()
        offers
            .filter { it.monetizationType in ALLOWED_MONETIZATION }
            .forEach { offer ->
                val technicalName = offer.`package`?.technicalName ?: return@forEach
                val providerId = JustWatchPackageMap.resolveProviderId(technicalName) ?: return@forEach
                val url = offer.standardWebURL ?: return@forEach
                providerUrls.putIfAbsent(providerId, url)
            }
        for ((providerId, url) in providerUrls) {
            dao.upsert(JustWatchDeepLink(tmdbShowId, season, episode, providerId, countryCode, url, now))
        }
        // Cache negatives for known providers that had no offer
        val covered = providerUrls.keys
        for (providerId in JustWatchPackageMap.technicalNameToProviderId.values.toSet()) {
            if (providerId !in covered) {
                val existing = dao.get(tmdbShowId, season, episode, providerId, countryCode)
                if (existing == null) {
                    dao.upsert(JustWatchDeepLink(tmdbShowId, season, episode, providerId, countryCode, null, now))
                }
            }
        }
    }

    /** Inserts negative cache entries for all known providers. */
    private suspend fun cacheNegativeForAllKnownProviders(
        tmdbShowId: Int,
        season: Int,
        episode: Int,
        countryCode: String,
    ) {
        val now = System.currentTimeMillis()
        for (providerId in JustWatchPackageMap.technicalNameToProviderId.values.toSet()) {
            dao.upsert(JustWatchDeepLink(tmdbShowId, season, episode, providerId, countryCode, null, now))
        }
    }
}
