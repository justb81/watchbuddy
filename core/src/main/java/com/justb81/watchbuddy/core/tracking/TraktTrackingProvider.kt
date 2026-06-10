package com.justb81.watchbuddy.core.tracking

import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.SyncHistoryBody
import com.justb81.watchbuddy.core.trakt.SyncHistorySeasonItem
import com.justb81.watchbuddy.core.trakt.SyncHistoryShowItem
import com.justb81.watchbuddy.core.trakt.SyncWatchlistBody
import com.justb81.watchbuddy.core.trakt.SyncWatchlistShowItem
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.core.trakt.TraktSearchResult

/**
 * [TrackingProvider] implementation backed by the Trakt API.
 *
 * Wraps [TraktApiService] and centralises the [SyncHistoryBody] /
 * [SyncWatchlistBody] construction that was previously inlined in various
 * repositories and route handlers.
 */
class TraktTrackingProvider(
    private val traktApi: TraktApiService
) : TrackingProvider {

    override val backend: TrackingBackend = TrackingBackend.TRAKT

    override suspend fun getWatchedAndWatchlistShows(bearer: String): List<TraktWatchedEntry> {
        val watched = traktApi.getWatchedShows(bearer)
        val watchlist = runCatching { traktApi.getWatchlistShows(bearer) }.getOrDefault(emptyList())
        val watchedIds = watched.mapNotNull { it.show.ids.trakt }.toSet()
        val watchlistOnly = watchlist
            .filter { it.show.ids.trakt == null || it.show.ids.trakt !in watchedIds }
            .map { TraktWatchedEntry(show = it.show, seasons = emptyList()) }
        return watched + watchlistOnly
    }

    override suspend fun getSeasonsWithEpisodes(
        bearer: String,
        showId: String
    ): List<TraktSeasonWithEpisodes> =
        traktApi.getShowSeasons(bearer, showId)

    override suspend fun markWatched(
        bearer: String,
        ids: TraktIds,
        seasons: List<SyncHistorySeasonItem>
    ): Result<Unit> = runCatching {
        val body = SyncHistoryBody(
            shows = listOf(SyncHistoryShowItem(ids = ids, seasons = seasons))
        )
        traktApi.addToHistory(bearer, body)
        Unit
    }

    override suspend fun markUnwatched(
        bearer: String,
        ids: TraktIds,
        season: Int,
        episode: Int
    ): Result<Unit> = runCatching {
        val body = SyncHistoryBody(
            shows = listOf(
                SyncHistoryShowItem(
                    ids = ids,
                    seasons = listOf(
                        com.justb81.watchbuddy.core.trakt.SyncHistorySeasonItem(
                            number = season,
                            episodes = listOf(
                                com.justb81.watchbuddy.core.trakt.SyncHistoryEpisodeItem(number = episode)
                            )
                        )
                    )
                )
            )
        )
        traktApi.removeFromHistory(bearer, body)
        Unit
    }

    override suspend fun search(bearer: String, query: String): List<TraktSearchResult> =
        traktApi.searchShow(bearer, query)

    override suspend fun addToWatchlist(bearer: String, show: TraktShow) {
        traktApi.addToWatchlist(
            bearer,
            SyncWatchlistBody(shows = listOf(SyncWatchlistShowItem(ids = show.ids)))
        )
    }

    override suspend fun getProfile(bearer: String): TrackingProfile {
        val profile = traktApi.getProfile(bearer)
        return TrackingProfile(
            username = profile.username,
            avatarUrl = profile.images?.avatar?.full
        )
    }
}
