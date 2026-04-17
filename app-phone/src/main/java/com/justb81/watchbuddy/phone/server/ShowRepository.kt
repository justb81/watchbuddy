package com.justb81.watchbuddy.phone.server

import android.util.Log
import com.justb81.watchbuddy.core.locale.LocaleHelper
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
 *   • [CompanionHttpServer] for the `/shows` HTTP endpoint
 *   • Phone `HomeViewModel` for rendering the home list
 */
@Singleton
class ShowRepository @Inject constructor(
    private val traktApi: TraktApiService,
    private val tokenRefreshManager: TokenRefreshManager,
    private val tmdbApiService: TmdbApiService,
    private val settingsRepository: SettingsRepository
) {
    private var cachedShows: List<EnrichedShowEntry> = emptyList()
    private var lastFetch: Long = 0L

    suspend fun getShows(): List<EnrichedShowEntry> {
        val now = System.currentTimeMillis()
        if (now - lastFetch > CACHE_TTL || cachedShows.isEmpty()) {
            val token = tokenRefreshManager.getValidAccessToken()
                ?: return emptyList()
            try {
                val trakt = traktApi.getWatchedShows("Bearer $token")
                cachedShows = enrich(trakt)
                lastFetch = now
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch shows from Trakt; serving ${cachedShows.size} cached entries", e)
                // Do not update lastFetch so the next call retries the API.
            }
        }
        return cachedShows
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
