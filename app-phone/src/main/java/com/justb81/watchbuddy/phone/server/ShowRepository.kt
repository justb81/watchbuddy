package com.justb81.watchbuddy.phone.server

import android.util.Log
import com.justb81.watchbuddy.core.locale.LocaleHelper
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import com.justb81.watchbuddy.core.progress.ShowProgressCalculator
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

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
    private val traktApi: TraktApiService,
    private val tokenRefreshManager: TokenRefreshManager,
    private val tmdbApiService: TmdbApiService,
    private val settingsRepository: SettingsRepository
) {
    private val _shows = MutableStateFlow<List<EnrichedShowEntry>>(emptyList())
    val shows: StateFlow<List<EnrichedShowEntry>> = _shows.asStateFlow()

    private var lastFetch: Long = 0L

    private val showComparator = compareByDescending<EnrichedShowEntry> {
        ShowProgressCalculator.latestWatchedInstant(it.entry)
    }.thenBy { it.entry.show.title.lowercase() }

    suspend fun getShows(): List<EnrichedShowEntry> {
        val now = System.currentTimeMillis()
        val cached = _shows.value
        if (now - lastFetch > CACHE_TTL || cached.isEmpty()) {
            val token = tokenRefreshManager.getValidAccessToken()
                ?: return cached
            try {
                val trakt = traktApi.getWatchedShows("Bearer $token")
                _shows.value = enrich(trakt).sortedWith(showComparator)
                lastFetch = now
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch shows from Trakt; serving ${cached.size} cached entries", e)
                // Do not update lastFetch so the next call retries the API.
            }
        }
        return _shows.value
    }

    /**
     * Mutates the in-memory cache so Home counters update instantly after a
     * per-episode toggle on the detail screen. Network sync is the caller's
     * responsibility (see `EpisodeRepository.markEpisode{Watched,Unwatched}`).
     */
    fun updateLocalWatched(
        traktShowId: Int,
        season: Int,
        episode: Int,
        watched: Boolean
    ) {
        val current = _shows.value
        val index = current.indexOfFirst { it.entry.show.ids.trakt == traktShowId }
        if (index < 0) return
        val existing = current[index]
        val updatedSeasons = if (watched) {
            addEpisode(existing.entry.seasons, season, episode)
        } else {
            removeEpisode(existing.entry.seasons, season, episode)
        }
        if (updatedSeasons === existing.entry.seasons) return
        val updatedEntry = existing.copy(entry = existing.entry.copy(seasons = updatedSeasons))
        _shows.value = current.toMutableList()
            .also { it[index] = updatedEntry }
            .sortedWith(showComparator)
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

    private companion object {
        const val CACHE_TTL = 5 * 60 * 1000L // 5 minutes
    }
}

private fun TmdbShow.toProgressHint(): TmdbProgressHint = TmdbProgressHint(
    status = status,
    lastAired = last_episode_to_air,
    nextAired = next_episode_to_air,
    seasons = seasons
)
