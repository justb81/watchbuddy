package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.justwatch.JustWatchApiService
import com.justb81.watchbuddy.core.justwatch.JustWatchGraphQlRequest
import com.justb81.watchbuddy.core.justwatch.JustWatchGraphQlResponse
import com.justb81.watchbuddy.core.justwatch.JustWatchOffer
import com.justb81.watchbuddy.core.justwatch.JustWatchPackageMap
import com.justb81.watchbuddy.core.justwatch.JustWatchTitle
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.Response
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val ALLOWED_MONETIZATION = setOf("FLATRATE", "ADS", "FREE")
private const val SEARCH_RESULT_LIMIT = 5
private const val TAG = "JustWatchRepo"
private val COUNTRY_CODE_REGEX = Regex("[A-Z]{2}")
private const val DEFAULT_MISS_WINDOW_MS = 24L * 60 * 60 * 1_000
private const val MAX_ERROR_SUMMARY_LENGTH = 200
private const val MAX_HTTP_ERROR_BODY_LENGTH = 500
private const val MAX_OUTCOME_EVENTS = 50

/**
 * A single deep-link resolution outcome recorded in [JustWatchDeepLinkRepository.outcomeEvents].
 *
 * [providerId] is -1 when the outcome is not specific to a single provider (e.g., HTTP errors
 * and search misses affect all providers for a given show). [detail] carries extra context
 * such as the unmapped technical name or HTTP status.
 */
data class JustWatchOutcomeEvent(
    val timestampMs: Long,
    val tmdbShowId: Int,
    val providerId: Int,
    val countryCode: String,
    val outcome: Outcome,
    val detail: String = "",
) {
    enum class Outcome {
        EPISODE_CACHE_HIT,
        EPISODE_API_HIT,
        SHOW_CACHE_HIT,
        SHOW_API_HIT,
        SEARCH_MISS,
        TECHNICAL_NAME_UNMAPPED,
        HTTP_ERROR,
        GRAPHQL_ERROR,
        EPISODE_NOT_IN_RESULTS,
    }
}

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

        /** The API returned a non-2xx HTTP status (typically 422 from JustWatch). */
        data class HttpError(val code: Int, val bodySummary: String) : SearchResult()
    }

    private data class FetchKey(
        val tmdbShowId: Int,
        val season: Int,
        val episode: Int,
        val countryCode: String,
    )

    private val fetchMutexMap = ConcurrentHashMap<FetchKey, Mutex>()

    // ── In-memory diagnostics ─────────────────────────────────────────────────

    @Volatile private var _lastError: String? = null
    private val missTimestamps = ArrayDeque<Long>()
    private val missLock = Any()

    private val _outcomeEvents = MutableStateFlow<List<JustWatchOutcomeEvent>>(emptyList())

    /** Bounded ring-buffer (max [MAX_OUTCOME_EVENTS]) of recent deep-link resolution outcomes. */
    val outcomeEvents: StateFlow<List<JustWatchOutcomeEvent>> = _outcomeEvents.asStateFlow()

    private val outcomeLock = Any()

    private fun recordOutcome(
        tmdbShowId: Int,
        providerId: Int,
        countryCode: String,
        outcome: JustWatchOutcomeEvent.Outcome,
        detail: String = "",
    ) = synchronized(outcomeLock) {
        val event = JustWatchOutcomeEvent(System.currentTimeMillis(), tmdbShowId, providerId, countryCode, outcome, detail)
        val current = _outcomeEvents.value
        _outcomeEvents.value = if (current.size >= MAX_OUTCOME_EVENTS) current.drop(1) + event else current + event
    }

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
    fun searchMissCount(windowMs: Long = DEFAULT_MISS_WINDOW_MS): Int = synchronized(missLock) {
        val cutoff = System.currentTimeMillis() - windowMs
        while (missTimestamps.isNotEmpty() && missTimestamps.peekFirst()!! < cutoff) {
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
        if (epCached != null && epCached.isValidCache()) {
            recordOutcome(tmdbShowId, providerId, country, JustWatchOutcomeEvent.Outcome.EPISODE_CACHE_HIT)
            return epCached.standardWebUrl
        }

        // 2. Episode-level live fetch (deduped per episode)
        val episodeKey = FetchKey(tmdbShowId, season, episode, country)
        getMutex(episodeKey).withLock {
            // Re-check cache after acquiring lock — another coroutine may have populated it
            val refreshed = dao.get(tmdbShowId, season, episode, providerId, country)
            if (refreshed != null && refreshed.isValidCache()) {
                recordOutcome(tmdbShowId, providerId, country, JustWatchOutcomeEvent.Outcome.EPISODE_CACHE_HIT)
                return refreshed.standardWebUrl
            }
            fetchAndCacheEpisodeOffers(tmdbShowId, season, episode, country, showTitle)
        }

        // Re-read after episode fetch
        val epResult = dao.get(tmdbShowId, season, episode, providerId, country)
        if (epResult != null && epResult.isValidCache()) {
            recordOutcome(tmdbShowId, providerId, country, JustWatchOutcomeEvent.Outcome.EPISODE_API_HIT)
            return epResult.standardWebUrl
        }

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
        if (showCached != null && showCached.isValidCache()) {
            recordOutcome(tmdbShowId, providerId, countryCode, JustWatchOutcomeEvent.Outcome.SHOW_CACHE_HIT)
            return showCached.standardWebUrl
        }

        // 4. Show-level live fetch (deduped)
        val showKey = FetchKey(tmdbShowId, 0, 0, countryCode)
        getMutex(showKey).withLock {
            val refreshed = dao.get(tmdbShowId, 0, 0, providerId, countryCode)
            if (refreshed != null && refreshed.isValidCache()) {
                recordOutcome(tmdbShowId, providerId, countryCode, JustWatchOutcomeEvent.Outcome.SHOW_CACHE_HIT)
                return refreshed.standardWebUrl
            }
            fetchAndCacheShowOffers(tmdbShowId, countryCode, showTitle)
        }

        val showResult = dao.get(tmdbShowId, 0, 0, providerId, countryCode)
        if (showResult?.standardWebUrl != null) {
            recordOutcome(tmdbShowId, providerId, countryCode, JustWatchOutcomeEvent.Outcome.SHOW_API_HIT)
        }
        return showResult?.standardWebUrl
    }

    private fun JustWatchDeepLink.isValidCache(): Boolean =
        standardWebUrl != null || !isNegativeExpired(this)

    private fun getMutex(key: FetchKey): Mutex = fetchMutexMap.computeIfAbsent(key) { Mutex() }

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
                is SearchResult.HttpError -> {
                    val msg = "HTTP ${result.code} searching '$showTitle' (tmdb=$tmdbShowId): ${result.bodySummary}"
                    DiagnosticLog.error(TAG, msg)
                    recordError(msg)
                    recordOutcome(tmdbShowId, -1, countryCode, JustWatchOutcomeEvent.Outcome.HTTP_ERROR, "HTTP ${result.code}")
                    return
                }
                is SearchResult.GraphQlError -> {
                    val msg = "GraphQL error searching '$showTitle' (tmdb=$tmdbShowId): ${result.summary}"
                    DiagnosticLog.error(TAG, msg)
                    recordError(msg)
                    recordOutcome(tmdbShowId, -1, countryCode, JustWatchOutcomeEvent.Outcome.GRAPHQL_ERROR, result.summary)
                    return
                }
                is SearchResult.SearchMiss -> {
                    DiagnosticLog.warn(TAG, "No JustWatch node matched tmdbId=$tmdbShowId title='$showTitle'")
                    recordMiss()
                    recordOutcome(tmdbShowId, -1, countryCode, JustWatchOutcomeEvent.Outcome.SEARCH_MISS)
                    return
                }
                is SearchResult.Found ->
                    fetchEpisodeOffersForNode(result.title, tmdbShowId, season, episode, countryCode, language)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            DiagnosticLog.error(TAG, "Episode fetch failed for tmdbId=$tmdbShowId S${season}E$episode: $msg", e)
            recordError(msg)
            // Network/parse failure: do not cache negatives so the next attempt retries
        }
    }

    private suspend fun fetchEpisodeOffersForNode(
        jwNode: JustWatchTitle,
        tmdbShowId: Int,
        season: Int,
        episode: Int,
        countryCode: String,
        language: String,
    ) {
        // Cache show-level offers while we have the show node
        cacheOffers(jwNode.offers, tmdbShowId, 0, 0, countryCode)

        // Fetch seasons
        val seasonsResp = api.query(
            JustWatchGraphQlRequest(
                query = JustWatchApiService.SEASONS_QUERY,
                variables = buildJsonObject {
                    put("nodeId", jwNode.id)
                },
            )
        )
        val seasonsBody = unwrapBodyOrLog(
            seasonsResp,
            queryName = "seasons",
            context = "tmdbId=$tmdbShowId",
        ) ?: return
        val jwSeasons = seasonsBody.data?.node?.seasons
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
        val episodesBody = unwrapBodyOrLog(
            episodesResp,
            queryName = "episodes",
            context = "tmdbId=$tmdbShowId S$season",
        ) ?: return
        val jwEpisodes = episodesBody.data?.node?.episodes
        if (jwEpisodes.isNullOrEmpty()) {
            DiagnosticLog.warn(TAG, "Empty episodes list for tmdbId=$tmdbShowId S$season")
            return
        }

        val jwEp = jwEpisodes.find { it.content?.seasonNumber == season && it.content?.episodeNumber == episode }
        if (jwEp != null) {
            cacheOffers(jwEp.offers, tmdbShowId, season, episode, countryCode)
        } else {
            DiagnosticLog.warn(TAG, "Episode S${season}E$episode not found in JustWatch for tmdbId=$tmdbShowId")
            recordOutcome(tmdbShowId, -1, countryCode, JustWatchOutcomeEvent.Outcome.EPISODE_NOT_IN_RESULTS, "S${season}E$episode")
        }
    }

    /**
     * Inspects a [Response] for a follow-up GraphQL query (seasons / episodes).
     *
     * Logs and records the error for non-2xx HTTP responses (e.g. 422), null bodies,
     * and top-level GraphQL `errors[]` arrays. Returns the response body on success,
     * or null when the caller should bail out (the negative-cache policy is unchanged
     * — these errors do not produce negative entries, so the next visit retries).
     */
    private fun unwrapBodyOrLog(
        response: Response<JustWatchGraphQlResponse>,
        queryName: String,
        context: String,
    ): JustWatchGraphQlResponse? {
        if (!response.isSuccessful) {
            val body = readErrorBody(response)
            val msg = "HTTP ${response.code()} from JustWatch $queryName query ($context): $body"
            DiagnosticLog.error(TAG, msg)
            recordError(msg)
            return null
        }
        val body = response.body()
        if (body == null) {
            val msg = "Empty $queryName response body from JustWatch ($context)"
            DiagnosticLog.error(TAG, msg)
            recordError(msg)
            return null
        }
        if (!body.errors.isNullOrEmpty()) {
            val summary = body.errors.toString().take(MAX_ERROR_SUMMARY_LENGTH)
            val msg = "GraphQL errors in $queryName query ($context): $summary"
            DiagnosticLog.error(TAG, msg)
            recordError(msg)
            return null
        }
        return body
    }

    private suspend fun fetchAndCacheShowOffers(
        tmdbShowId: Int,
        countryCode: String,
        showTitle: String,
    ) {
        try {
            when (val result = searchShowByTmdbId(tmdbShowId, showTitle, countryCode, "en")) {
                is SearchResult.HttpError -> {
                    val msg = "HTTP ${result.code} searching '$showTitle' (tmdb=$tmdbShowId, show-level): ${result.bodySummary}"
                    DiagnosticLog.error(TAG, msg)
                    recordError(msg)
                }
                is SearchResult.GraphQlError -> {
                    val msg = "GraphQL error searching '$showTitle' (tmdb=$tmdbShowId): ${result.summary}"
                    DiagnosticLog.error(TAG, msg)
                    recordError(msg)
                }
                is SearchResult.SearchMiss -> {
                    DiagnosticLog.warn(TAG, "No JustWatch node matched tmdbId=$tmdbShowId title='$showTitle' (show-level)")
                    // No miss recorded here — the episode-level fetch already counted it
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
        if (!response.isSuccessful) {
            return SearchResult.HttpError(response.code(), readErrorBody(response))
        }
        val body = response.body() ?: return SearchResult.HttpError(response.code(), "Empty response body")
        if (!body.errors.isNullOrEmpty()) {
            val summary = body.errors.toString().take(MAX_ERROR_SUMMARY_LENGTH)
            return SearchResult.GraphQlError(summary)
        }
        val match = body.data?.popularTitles?.edges
            ?.map { it.node }
            ?.firstOrNull { node -> node.content?.externalIds?.tmdbId == tmdbShowId.toString() }
        return if (match != null) SearchResult.Found(match) else SearchResult.SearchMiss
    }

    /** Reads up to [MAX_HTTP_ERROR_BODY_LENGTH] chars from a Retrofit error body. */
    private fun readErrorBody(response: Response<*>): String =
        try {
            response.errorBody()?.string()?.take(MAX_HTTP_ERROR_BODY_LENGTH).orEmpty()
        } catch (e: Exception) {
            "<failed to read error body: ${e.javaClass.simpleName}>"
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
                val resolvedId = JustWatchPackageMap.resolveProviderId(technicalName)
                if (resolvedId == null) {
                    DiagnosticLog.warn(TAG, "technicalNameUnmapped: '$technicalName' (tmdbShowId=$tmdbShowId)")
                    recordOutcome(tmdbShowId, -1, countryCode, JustWatchOutcomeEvent.Outcome.TECHNICAL_NAME_UNMAPPED, technicalName)
                    return@forEach
                }
                val url = offer.standardWebURL ?: return@forEach
                providerUrls.putIfAbsent(resolvedId, url)
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
