package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.justwatch.JustWatchApiService
import com.justb81.watchbuddy.core.justwatch.JustWatchGraphQlRequest
import com.justb81.watchbuddy.core.justwatch.JustWatchOffer
import com.justb81.watchbuddy.core.justwatch.JustWatchPackageMap
import com.justb81.watchbuddy.core.justwatch.JustWatchTitle
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

private val ALLOWED_MONETIZATION = setOf("FLATRATE", "ADS", "FREE")
private const val SEARCH_RESULT_LIMIT = 5
private const val TAG = "JustWatchRepo"
private val COUNTRY_CODE_REGEX = Regex("[A-Z]{2}")

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
 * Negatives are only cached when JustWatch confirmed no offer (i.e. the full
 * seasons/episodes graph was fetched but the provider had no match). Hard errors
 * (network exception, GraphQL errors, search miss) do NOT write negatives so the
 * next call can retry.
 *
 * Batch dedup: a Mutex-protected map prevents duplicate in-flight JustWatch API calls
 * for the same (showId, season, episode, countryCode) key.
 */
@Singleton
class JustWatchDeepLinkRepository @Inject constructor(
    private val dao: JustWatchDeepLinkDao,
    private val api: JustWatchApiService,
) {

    private sealed class SearchResult {
        data class Found(val title: JustWatchTitle) : SearchResult()
        /** The API returned a valid response but no node matched the TMDB ID. */
        data object SearchMiss : SearchResult()
        /** The API returned a top-level GraphQL errors array. */
        data class GraphQlError(val summary: String) : SearchResult()
    }

    private data class FetchKey(
        val tmdbShowId: Int,
        val season: Int,
        val episode: Int,
        val countryCode: String,
    )

    private val fetchMutexMap = mutableMapOf<FetchKey, Mutex>()
    private val mapLock = Mutex()

    // ── In-memory diagnostics ─────────────────────────────────────────────────

    @Volatile private var _lastError: String? = null
    private val missTimestamps = ArrayDeque<Long>()
    private val missLock = Any()

    private fun recordMiss() = synchronized(missLock) {
        missTimestamps.addLast(System.currentTimeMillis())
    }

    private fun recordError(message: String) {
        _lastError = message
    }

    /** Returns the last error message produced by a fetch, or null if no error has occurred. */
    fun lastFetchError(): String? = _lastError

    /**
     * Returns the number of JustWatch search misses in the given time window.
     * A miss is counted when the API returned successfully but no JustWatch node
     * matched the requested TMDB show ID.
     */
    fun searchMissCount(windowMs: Long = 24 * 60 * 60 * 1000L): Int = synchronized(missLock) {
        val cutoff = System.currentTimeMillis() - windowMs
        while (missTimestamps.isNotEmpty() && missTimestamps.peekFirst() < cutoff) {
            missTimestamps.pollFirst()
        }
        missTimestamps.size
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun resolveDeepLink(
        tmdbShowId: Int,
        season: Int,
        episode: Int,
        providerId: Int,
        countryCode: String,
        showTitle: String,
    ): String? {
        val country = sanitizeCountryCode(countryCode)

        // 1. Episode-level cache
        val epCached = dao.get(tmdbShowId, season, episode, providerId, country)
        if (epCached != null && epCached.isValidCache()) return epCached.standardWebUrl

        // 2. Episode-level live fetch (deduped per episode)
        val episodeKey = FetchKey(tmdbShowId, season, episode, country)
        getMutex(episodeKey).withLock {
            // Re-check cache after acquiring lock — another coroutine may have populated it
            val refreshed = dao.get(tmdbShowId, season, episode, providerId, country)
            if (refreshed != null && refreshed.isValidCache()) return refreshed.standardWebUrl
            fetchAndCacheEpisodeOffers(tmdbShowId, season, episode, country, showTitle)
        }

        // Re-read after episode fetch
        val epResult = dao.get(tmdbShowId, season, episode, providerId, country)
        if (epResult != null && epResult.isValidCache()) return epResult.standardWebUrl

        return resolveShowLevel(tmdbShowId, providerId, country, showTitle)
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

    /**
     * Validates and sanitizes a country code. Returns the uppercased code when it is
     * exactly two ASCII letters, otherwise logs a warning and returns "US".
     */
    private fun sanitizeCountryCode(raw: String): String {
        val upper = raw.uppercase()
        if (upper.matches(COUNTRY_CODE_REGEX)) return upper
        DiagnosticLog.warn(TAG, "Unsupported country code '$raw', falling back to US")
        recordError("Unsupported country code '$raw'")
        return "US"
    }

    private suspend fun fetchAndCacheEpisodeOffers(
        tmdbShowId: Int,
        season: Int,
        episode: Int,
        countryCode: String,
        showTitle: String,
    ) {
        try {
            val language = "en"

            when (val result = searchShowByTmdbId(tmdbShowId, showTitle, countryCode, language)) {
                is SearchResult.GraphQlError -> {
                    val msg = "GraphQL error searching '$showTitle' (tmdb=$tmdbShowId): ${result.summary}"
                    DiagnosticLog.error(TAG, msg)
                    recordError(msg)
                    return
                }
                is SearchResult.SearchMiss -> {
                    DiagnosticLog.warn(TAG, "No JustWatch node matched tmdbId=$tmdbShowId title='$showTitle'")
                    recordMiss()
                    return
                }
                is SearchResult.Found -> {
                    val jwNode = result.title

                    // Cache show-level offers while we have the show node
                    cacheOffers(jwNode.offers, tmdbShowId, 0, 0, countryCode)

                    // Fetch seasons
                    val seasonsResp = api.query(
                        JustWatchGraphQlRequest(
                            query = JustWatchApiService.SEASONS_QUERY,
                            variables = buildJsonObject {
                                put("nodeId", jwNode.id)
                                put("country", countryCode)
                                put("language", language)
                            },
                        )
                    )
                    if (!seasonsResp.errors.isNullOrEmpty()) {
                        val msg = "GraphQL errors in seasons query for tmdbId=$tmdbShowId"
                        DiagnosticLog.error(TAG, msg)
                        recordError(msg)
                        return
                    }
                    val jwSeasons = seasonsResp.data?.node?.seasons
                    if (jwSeasons.isNullOrEmpty()) {
                        DiagnosticLog.warn(TAG, "Empty seasons list for tmdbId=$tmdbShowId")
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
                                put("country", countryCode)
                                put("language", language)
                            },
                        )
                    )
                    if (!episodesResp.errors.isNullOrEmpty()) {
                        val msg = "GraphQL errors in episodes query for tmdbId=$tmdbShowId S${season}"
                        DiagnosticLog.error(TAG, msg)
                        recordError(msg)
                        return
                    }
                    val jwEpisodes = episodesResp.data?.node?.episodes
                    if (jwEpisodes.isNullOrEmpty()) {
                        DiagnosticLog.warn(TAG, "Empty episodes list for tmdbId=$tmdbShowId S${season}")
                        return
                    }

                    val jwEp = jwEpisodes.find { it.seasonNumber == season && it.episodeNumber == episode }
                    if (jwEp != null) {
                        cacheOffers(jwEp.offers, tmdbShowId, season, episode, countryCode)
                    } else {
                        DiagnosticLog.warn(TAG, "Episode S${season}E${episode} not found in JustWatch for tmdbId=$tmdbShowId")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            DiagnosticLog.error(TAG, "Episode fetch failed for tmdbId=$tmdbShowId S${season}E${episode}: $msg", e)
            recordError(msg)
            // Network/parse failure: do not cache negatives so the next attempt retries
        }
    }

    private suspend fun fetchAndCacheShowOffers(
        tmdbShowId: Int,
        countryCode: String,
        showTitle: String,
    ) {
        try {
            when (val result = searchShowByTmdbId(tmdbShowId, showTitle, countryCode, "en")) {
                is SearchResult.GraphQlError -> {
                    val msg = "GraphQL error searching '$showTitle' (tmdb=$tmdbShowId): ${result.summary}"
                    DiagnosticLog.error(TAG, msg)
                    recordError(msg)
                }
                is SearchResult.SearchMiss -> {
                    DiagnosticLog.warn(TAG, "No JustWatch node matched tmdbId=$tmdbShowId title='$showTitle'")
                    recordMiss()
                }
                is SearchResult.Found -> {
                    cacheOffers(result.title.offers, tmdbShowId, 0, 0, countryCode)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            DiagnosticLog.error(TAG, "Show-level fetch failed for tmdbId=$tmdbShowId: $msg", e)
            recordError(msg)
            // Network/parse failure: do not cache negatives
        }
    }

    /** Searches JustWatch for a show by title and verifies the TMDB ID matches. */
    private suspend fun searchShowByTmdbId(
        tmdbShowId: Int,
        showTitle: String,
        countryCode: String,
        language: String,
    ): SearchResult {
        val response = api.query(
            JustWatchGraphQlRequest(
                query = JustWatchApiService.SEARCH_QUERY,
                variables = buildJsonObject {
                    put("searchQuery", showTitle)
                    put("country", countryCode)
                    put("language", language)
                    put("first", SEARCH_RESULT_LIMIT)
                },
            )
        )
        if (!response.errors.isNullOrEmpty()) {
            val summary = response.errors.toString().take(200)
            return SearchResult.GraphQlError(summary)
        }
        val match = response.data?.popularTitles?.edges
            ?.map { it.node }
            ?.firstOrNull { node -> node.content?.externalIds?.tmdbId == tmdbShowId.toString() }
        return if (match != null) SearchResult.Found(match) else SearchResult.SearchMiss
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
        // Cache negatives for known providers confirmed to have no offer for this title
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
}
