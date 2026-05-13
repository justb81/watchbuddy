package com.justb81.watchbuddy.phone.server

import android.util.Log
import com.justb81.watchbuddy.core.cache.TimedCachedResource
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.trakt.SyncHistoryBody
import com.justb81.watchbuddy.core.trakt.SyncHistoryEpisodeItem
import com.justb81.watchbuddy.core.trakt.SyncHistorySeasonItem
import com.justb81.watchbuddy.core.trakt.SyncHistoryShowItem
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

private const val TAG = "EpisodeRepository"

/**
 * Fetches full season + episode structure for a single show via Trakt
 * `shows/{id}/seasons?extended=episodes`, and forwards per-episode
 * watched/unwatched writes through the `sync/history` add/remove endpoints.
 *
 * Structural data is cached per show id for 10 minutes via [TimedCachedResource] —
 * the list changes only when Trakt ingests a new episode, so a short miss is fine.
 * Writes are not cached; they always hit Trakt and invalidate the per-show cache
 * entry on success.
 */
@Singleton
class EpisodeRepository @Inject constructor(
    private val traktApi: TraktApiService,
    private val tokenRefreshManager: TokenRefreshManager
) {
    private val cache = TimedCachedResource<String, List<TraktSeasonWithEpisodes>>(
        ttlMillis = 10.minutes.inWholeMilliseconds,
        fetcher = { showId -> fetchFromTrakt(showId) },
    )

    suspend fun getSeasonsWithEpisodes(showId: String): List<TraktSeasonWithEpisodes> =
        cache.get(showId)

    suspend fun markEpisodeWatched(
        ids: TraktIds,
        season: Int,
        episode: Int
    ): Result<Unit> = runCatching {
        val body = buildBody(ids, season, episode)
        val token = tokenRefreshManager.getValidAccessToken()
            ?: error("No access token available")
        traktApi.addToHistory("Bearer $token", body)
        invalidateShowCache(ids)
        Unit
    }.onFailure { Log.w(TAG, "markEpisodeWatched S${season}E${episode} failed", it) }

    suspend fun markEpisodeUnwatched(
        ids: TraktIds,
        season: Int,
        episode: Int
    ): Result<Unit> = runCatching {
        val body = buildBody(ids, season, episode)
        val token = tokenRefreshManager.getValidAccessToken()
            ?: error("No access token available")
        traktApi.removeFromHistory("Bearer $token", body)
        invalidateShowCache(ids)
        Unit
    }.onFailure { Log.w(TAG, "markEpisodeUnwatched S${season}E${episode} failed", it) }

    /**
     * Marks all [candidates] (season, episode) pairs as watched in a single
     * `POST /sync/history` call. The caller is responsible for pre-filtering
     * the candidate list (season >= 1, not already watched, <= target position).
     *
     * Returns [Result.success] immediately when [candidates] is empty (no HTTP
     * call is made). Returns [Result.failure] if the network call fails.
     */
    suspend fun markEpisodesWatchedUpTo(
        ids: TraktIds,
        targetSeason: Int,
        targetEpisode: Int,
        candidates: List<Pair<Int, Int>>
    ): Result<Unit> = runCatching {
        if (candidates.isEmpty()) return@runCatching Unit
        val seasonsBody = candidates
            .groupBy { it.first }
            .toSortedMap()
            .map { (season, eps) ->
                SyncHistorySeasonItem(
                    number = season,
                    episodes = eps.map { SyncHistoryEpisodeItem(number = it.second) }
                )
            }
        val body = SyncHistoryBody(
            shows = listOf(SyncHistoryShowItem(ids = ids, seasons = seasonsBody))
        )
        val token = tokenRefreshManager.getValidAccessToken()
            ?: error("No access token available")
        traktApi.addToHistory("Bearer $token", body)
        invalidateShowCache(ids)
        Unit
    }.onFailure {
        Log.w(TAG, "markEpisodesWatchedUpTo S${targetSeason}E$targetEpisode (${candidates.size} eps) failed", it)
    }

    private suspend fun fetchFromTrakt(showId: String): List<TraktSeasonWithEpisodes> {
        val token = tokenRefreshManager.getValidAccessToken()
            ?: error("No access token available")
        return traktApi.getShowSeasons("Bearer $token", showId)
    }

    private suspend fun invalidateShowCache(ids: TraktIds) {
        val key = ids.trakt?.toString() ?: ids.slug ?: return
        cache.invalidate(key)
    }

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
}
