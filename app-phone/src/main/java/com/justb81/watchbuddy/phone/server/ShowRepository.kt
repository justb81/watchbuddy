package com.justb81.watchbuddy.phone.server

import android.util.Log
import com.justb81.watchbuddy.core.cache.TimedCachedResource
import com.justb81.watchbuddy.core.locale.LocaleHelper
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import com.justb81.watchbuddy.core.progress.ShowProgressCalculator
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.tracking.TrackingProvider
import com.justb81.watchbuddy.core.trakt.TraktSearchResult
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

private const val TAG = "ShowRepository"

/**
 * Single source of truth for the user's watched shows on the phone side.
 * Wraps Trakt's watched list and enriches each entry with a [TmdbProgressHint]
 * and a poster path fetched from TMDB. The fan-out is parallel and tolerates
 * per-show failures so one unreachable show does not break the whole list.
 *
 * Consumed by:
 *   • [CompanionHttpServer] for the `/shows` HTTP endpoint (via [getShows])
 *   • Phone `HomeViewModel` for rendering the home list (via [shows] flow)
 *
 * The in-memory list is exposed reactively through [shows] so UI consumers see
 * watched-state changes made via [updateLocalWatched] without needing a round
 * trip to Trakt.
 */
@Singleton
class ShowRepository @Inject constructor(
    private val trackingProvider: TrackingProvider,
    private val tokenRefreshManager: TokenRefreshManager,
    private val tmdbApiService: TmdbApiService,
    private val settingsRepository: SettingsRepository
) {
    private val _shows = MutableStateFlow<List<EnrichedShowEntry>>(emptyList())
    val shows: StateFlow<List<EnrichedShowEntry>> = _shows.asStateFlow()

    private val cache = TimedCachedResource<Unit, List<EnrichedShowEntry>>(
        ttlMillis = 5.minutes.inWholeMilliseconds,
        fetcher = { fetchFromProvider() },
    )

    private val showComparator = compareByDescending<EnrichedShowEntry> {
        ShowProgressCalculator.latestWatchedInstant(it.entry)
    }.thenBy { it.entry.show.title.lowercase() }

    suspend fun invalidateCache() {
        cache.invalidate(Unit)
    }

    suspend fun getShows(): List<EnrichedShowEntry> {
        return try {
            val enriched = cache.get(Unit)
            _shows.value = enriched
            enriched
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch shows; serving ${_shows.value.size} cached entries", e)
            _shows.value
        }
    }

    suspend fun searchShows(bearer: String, query: String): List<TraktSearchResult> {
        return trackingProvider.search(bearer, query)
    }

    suspend fun addShowToWatchlist(bearer: String, show: TraktShow) {
        trackingProvider.addToWatchlist(bearer, show)
        val stableKey = stableKey(show.ids)
        if (stableKey != null) {
            _shows.update { current ->
                if (current.any { stableKey(it.entry.show.ids) == stableKey }) {
                    current
                } else {
                    (current + EnrichedShowEntry(entry = TraktWatchedEntry(show = show)))
                        .sortedWith(showComparator)
                }
            }
        }
        cache.invalidate(Unit)
    }

    private suspend fun fetchFromProvider(): List<EnrichedShowEntry> {
        val token = tokenRefreshManager.getValidAccessToken()
            ?: error("No access token available")
        val bearer = "Bearer $token"
        val allShows = trackingProvider.getWatchedAndWatchlistShows(bearer)
        return enrich(allShows).sortedWith(showComparator)
    }

    /**
     * Mutates the in-memory cache so Home counters update instantly after a
     * per-episode toggle on the detail screen. Network sync is the caller's
     * responsibility (see `EpisodeRepository.markEpisode{Watched,Unwatched}`).
     *
     * [showIds] identifies the show using the stable key: [TraktIds.trakt] for Trakt
     * items, [TraktIds.simkl] for SIMKL items, [TraktIds.tmdb] as a fallback.
     */
    fun updateLocalWatched(
        showIds: com.justb81.watchbuddy.core.model.TraktIds,
        season: Int,
        episode: Int,
        watched: Boolean
    ) {
        val targetKey = stableKey(showIds) ?: return
        _shows.update { current ->
            val index = current.indexOfFirst { stableKey(it.entry.show.ids) == targetKey }
            if (index < 0) return@update current
            val existing = current[index]
            val updatedSeasons = if (watched) {
                addEpisode(existing.entry.seasons, season, episode)
            } else {
                removeEpisode(existing.entry.seasons, season, episode)
            }
            if (updatedSeasons === existing.entry.seasons) return@update current
            val updatedEntry = existing.copy(entry = existing.entry.copy(seasons = updatedSeasons))
            current.toMutableList()
                .also { it[index] = updatedEntry }
                .sortedWith(showComparator)
        }
    }

    private fun addEpisode(
        seasons: List<TraktWatchedSeason>,
        season: Int,
        episode: Int
    ): List<TraktWatchedSeason> {
        val now = Instant.now().toString()
        val existing = seasons.find { it.number == season }
        return if (existing == null) {
            (seasons + TraktWatchedSeason(
                number = season,
                episodes = listOf(TraktWatchedEpisode(number = episode, last_watched_at = now))
            )).sortedBy { it.number }
        } else {
            if (existing.episodes.any { it.number == episode }) return seasons
            seasons.map { s ->
                if (s.number != season) s
                else s.copy(
                    episodes = (s.episodes + TraktWatchedEpisode(number = episode, last_watched_at = now))
                        .sortedBy { it.number }
                )
            }
        }
    }

    private fun removeEpisode(
        seasons: List<TraktWatchedSeason>,
        season: Int,
        episode: Int
    ): List<TraktWatchedSeason> {
        val target = seasons.find { it.number == season } ?: return seasons
        if (target.episodes.none { it.number == episode }) return seasons
        return seasons.mapNotNull { s ->
            if (s.number != season) s
            else {
                val remaining = s.episodes.filter { it.number != episode }
                if (remaining.isEmpty()) null else s.copy(episodes = remaining)
            }
        }
    }

    private suspend fun enrich(entries: List<TraktWatchedEntry>): List<EnrichedShowEntry> {
        val apiKey = runCatching { settingsRepository.getTmdbApiKey().first() }.getOrDefault("")
        if (apiKey.isBlank()) {
            return entries.map { EnrichedShowEntry(entry = it) }
        }
        val language = LocaleHelper.getTmdbLanguage()
        return coroutineScope {
            entries.map { entry ->
                async {
                    val tmdbId = entry.show.ids.tmdb
                    if (tmdbId == null) {
                        EnrichedShowEntry(entry = entry)
                    } else {
                        val tmdb = runCatching {
                            tmdbApiService.getShow(tmdbId, apiKey, language)
                        }.onFailure {
                            Log.w(TAG, "TMDB enrichment failed for show ${entry.show.title}", it)
                        }.getOrNull()
                        EnrichedShowEntry(
                            entry = entry,
                            tmdb = tmdb?.toProgressHint(),
                            posterPath = tmdb?.poster_path
                        )
                    }
                }
            }.awaitAll()
        }
    }
}

/**
 * Returns a stable numeric key for a show regardless of the active tracking backend.
 * Trakt items use the Trakt ID; SIMKL items use the SIMKL ID; TMDB ID is a fallback
 * when neither is populated.
 */
private fun stableKey(ids: com.justb81.watchbuddy.core.model.TraktIds): Int? =
    ids.trakt ?: ids.simkl ?: ids.tmdb

private fun TmdbShow.toProgressHint(): TmdbProgressHint = TmdbProgressHint(
    status = status,
    lastAired = last_episode_to_air,
    nextAired = next_episode_to_air,
    seasons = seasons,
    overview = overview
)
