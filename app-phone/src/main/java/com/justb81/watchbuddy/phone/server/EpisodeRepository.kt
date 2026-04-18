package com.justb81.watchbuddy.phone.server

import android.util.Log
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.trakt.SyncHistoryBody
import com.justb81.watchbuddy.core.trakt.SyncHistoryEpisodeItem
import com.justb81.watchbuddy.core.trakt.SyncHistorySeasonItem
import com.justb81.watchbuddy.core.trakt.SyncHistoryShowItem
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EpisodeRepository"

/**
 * Fetches full season + episode structure for a single show via Trakt
 * `shows/{id}/seasons?extended=episodes`, and forwards per-episode
 * watched/unwatched writes through the `sync/history` add/remove endpoints.
 *
 * Structural data is cached per show id for [CACHE_TTL_MS] (10 minutes) — the
 * list changes only when Trakt ingests a new episode, so a short miss is fine.
 * Writes are not cached; they always hit Trakt.
 */
@Singleton
class EpisodeRepository @Inject constructor(
    private val traktApi: TraktApiService,
    private val tokenRefreshManager: TokenRefreshManager
) {
    private data class Cached(val fetchedAt: Long, val seasons: List<TraktSeasonWithEpisodes>)

    private val cache = mutableMapOf<String, Cached>()
    private val mutex = Mutex()

    suspend fun getSeasonsWithEpisodes(showId: String): List<TraktSeasonWithEpisodes> {
        val now = System.currentTimeMillis()
        mutex.withLock {
            cache[showId]?.let { hit ->
                if (now - hit.fetchedAt <= CACHE_TTL_MS) return hit.seasons
            }
        }
        val token = tokenRefreshManager.getValidAccessToken()
            ?: throw IllegalStateException("No access token available")
        val fresh = traktApi.getShowSeasons("Bearer $token", showId)
        mutex.withLock {
            cache[showId] = Cached(fetchedAt = now, seasons = fresh)
        }
        return fresh
    }

    suspend fun markEpisodeWatched(
        ids: TraktIds,
        season: Int,
        episode: Int
    ): Result<Unit> = runCatching {
        val body = buildBody(ids, season, episode)
        val token = tokenRefreshManager.getValidAccessToken()
            ?: throw IllegalStateException("No access token available")
        traktApi.addToHistory("Bearer $token", body)
        Unit
    }.onFailure { Log.w(TAG, "markEpisodeWatched S${season}E${episode} failed", it) }

    suspend fun markEpisodeUnwatched(
        ids: TraktIds,
        season: Int,
        episode: Int
    ): Result<Unit> = runCatching {
        val body = buildBody(ids, season, episode)
        val token = tokenRefreshManager.getValidAccessToken()
            ?: throw IllegalStateException("No access token available")
        traktApi.removeFromHistory("Bearer $token", body)
        Unit
    }.onFailure { Log.w(TAG, "markEpisodeUnwatched S${season}E${episode} failed", it) }

    private fun buildBody(ids: TraktIds, season: Int, episode: Int): SyncHistoryBody =
        SyncHistoryBody(
            shows = listOf(
                SyncHistoryShowItem(
                    ids = ids,
                    seasons = listOf(
                        SyncHistorySeasonItem(
                            number = season,
                            episodes = listOf(SyncHistoryEpisodeItem(number = episode))
                        )
                    )
                )
            )
        )

    private companion object {
        const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
