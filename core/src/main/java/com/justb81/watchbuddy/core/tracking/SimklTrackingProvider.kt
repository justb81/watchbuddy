package com.justb81.watchbuddy.core.tracking

import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.simkl.SimklApiService
import com.justb81.watchbuddy.core.simkl.SimklIds
import com.justb81.watchbuddy.core.simkl.SimklSyncBody
import com.justb81.watchbuddy.core.simkl.SimklSyncEpisode
import com.justb81.watchbuddy.core.simkl.SimklSyncSeason
import com.justb81.watchbuddy.core.simkl.SimklSyncShowItem
import com.justb81.watchbuddy.core.simkl.toTraktSearchResult
import com.justb81.watchbuddy.core.simkl.toTraktSeasonsWithEpisodes
import com.justb81.watchbuddy.core.simkl.toTraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.SyncHistorySeasonItem
import com.justb81.watchbuddy.core.trakt.TraktSearchResult

/**
 * [TrackingProvider] implementation backed by the SIMKL API.
 *
 * Wraps [SimklApiService] and [com.justb81.watchbuddy.core.simkl.SimklMappers]
 * to adapt SIMKL responses into [Trakt*] model shapes so that no downstream
 * component (TV HTTP contract, progress calculator, UI) requires changes.
 *
 * [clientIdProvider] is a lambda that reads the user-supplied SIMKL Client ID
 * from [com.justb81.watchbuddy.phone.settings.SettingsRepository] at runtime.
 * It must be called per request (not cached in this class) so that a settings
 * change takes effect immediately without recreating singletons.
 */
class SimklTrackingProvider(
    private val simklApi: SimklApiService,
    private val clientIdProvider: () -> String
) : TrackingProvider {

    override val backend: TrackingBackend = TrackingBackend.SIMKL

    override suspend fun getWatchedAndWatchlistShows(bearer: String): List<TraktWatchedEntry> {
        val items = simklApi.getAllShows(bearer)
        return items.shows.map { it.toTraktWatchedEntry() }
    }

    /**
     * Returns season and episode structure for [showId].
     *
     * SIMKL does not have a dedicated per-show seasons endpoint accessible by
     * SIMKL ID alone in the same manner as Trakt's `shows/{id}/seasons`. Instead,
     * we derive the structure from the already-fetched all-items entry for the show.
     * [showId] is the SIMKL numeric ID string.
     */
    override suspend fun getSeasonsWithEpisodes(
        bearer: String,
        showId: String
    ): List<TraktSeasonWithEpisodes> {
        val simklId = showId.toIntOrNull() ?: return emptyList()
        val items = simklApi.getAllShows(bearer)
        val entry = items.shows.firstOrNull { it.show.ids.canonicalSimklId == simklId }
            ?: return emptyList()
        return entry.toTraktSeasonsWithEpisodes()
    }

    override suspend fun markWatched(
        bearer: String,
        ids: TraktIds,
        seasons: List<SyncHistorySeasonItem>
    ): Result<Unit> = runCatching {
        val body = SimklSyncBody(
            shows = listOf(
                SimklSyncShowItem(
                    ids = ids.toSimklIds(),
                    seasons = seasons.map { season ->
                        SimklSyncSeason(
                            number = season.number,
                            episodes = season.episodes.map { ep ->
                                SimklSyncEpisode(number = ep.number)
                            }
                        )
                    }
                )
            )
        )
        simklApi.addToHistory(bearer, body)
        Unit
    }

    override suspend fun markUnwatched(
        bearer: String,
        ids: TraktIds,
        season: Int,
        episode: Int
    ): Result<Unit> = runCatching {
        val body = SimklSyncBody(
            shows = listOf(
                SimklSyncShowItem(
                    ids = ids.toSimklIds(),
                    seasons = listOf(
                        SimklSyncSeason(
                            number = season,
                            episodes = listOf(SimklSyncEpisode(number = episode))
                        )
                    )
                )
            )
        )
        simklApi.removeFromHistory(bearer, body)
        Unit
    }

    override suspend fun search(bearer: String, query: String): List<TraktSearchResult> {
        val clientId = clientIdProvider()
        return simklApi.searchShow(query = query, clientId = clientId)
            .mapNotNull { it.toTraktSearchResult() }
    }

    override suspend fun addToWatchlist(bearer: String, show: TraktShow) {
        val body = SimklSyncBody(
            shows = listOf(SimklSyncShowItem(ids = show.ids.toSimklIds()))
        )
        simklApi.addToWatchlist(bearer, body)
    }

    override suspend fun getProfile(bearer: String): TrackingProfile {
        val settings = simklApi.getProfile(bearer)
        val user = settings.user
        return TrackingProfile(
            username = user?.name ?: user?.username ?: "user",
            avatarUrl = user?.avatar
        )
    }

    /** Converts a [TraktIds] (SIMKL item) back to [SimklIds] for sync requests. */
    private fun TraktIds.toSimklIds(): SimklIds = SimklIds(
        simkl = simkl,
        tmdb = tmdb,
        imdb = imdb,
        tvdb = tvdb,
        slug = slug
    )
}
