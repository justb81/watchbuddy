package com.justb81.watchbuddy.core.tracking

import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.SyncHistorySeasonItem
import com.justb81.watchbuddy.core.trakt.TraktSearchResult

/**
 * Abstraction over watch-tracking backends (Trakt, SIMKL).
 *
 * All methods operate in terms of [Trakt*] model shapes so that:
 * - [com.justb81.watchbuddy.phone.server.ShowRepository]
 * - [com.justb81.watchbuddy.phone.server.EpisodeRepository]
 * - route handlers
 *
 * remain backend-agnostic. SIMKL responses are adapted to these shapes in
 * [com.justb81.watchbuddy.core.simkl.SimklMappers] before crossing this boundary.
 *
 * The [bearer] parameter must be a complete `Authorization` header value,
 * e.g. `"Bearer {access_token}"`.
 */
interface TrackingProvider {

    val backend: TrackingBackend

    /**
     * Returns the user's combined library: watched shows + watchlist-only entries.
     * Watchlist-only items carry `seasons = emptyList()`.
     */
    suspend fun getWatchedAndWatchlistShows(bearer: String): List<TraktWatchedEntry>

    /**
     * Returns season and episode structure for [showId].
     * [showId] is the provider-native ID: a Trakt slug/ID string for Trakt,
     * or the SIMKL numeric ID string for SIMKL.
     */
    suspend fun getSeasonsWithEpisodes(bearer: String, showId: String): List<TraktSeasonWithEpisodes>

    /**
     * Marks the given [seasons] as watched for the show identified by [ids].
     */
    suspend fun markWatched(
        bearer: String,
        ids: TraktIds,
        seasons: List<SyncHistorySeasonItem>
    ): Result<Unit>

    /**
     * Removes the single episode `S[season]E[episode]` from watch history.
     */
    suspend fun markUnwatched(
        bearer: String,
        ids: TraktIds,
        season: Int,
        episode: Int
    ): Result<Unit>

    /**
     * Searches for shows matching [query].
     */
    suspend fun search(bearer: String, query: String): List<TraktSearchResult>

    /**
     * Adds [show] to the user's watchlist (plantowatch).
     */
    suspend fun addToWatchlist(bearer: String, show: TraktShow)

    /**
     * Returns the authenticated user's profile.
     */
    suspend fun getProfile(bearer: String): TrackingProfile
}
