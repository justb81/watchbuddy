package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of the user's Trakt watched shows.
 * Populated by [TvHomeViewModel] when shows are loaded from a phone.
 * Used for the progress-hint fallback that guesses the next unwatched episode when
 * the TMDB data is needed without a full phone request.
 */
@Singleton
class TvShowCache @Inject constructor() {

    @Volatile
    private var cachedShows: List<TraktWatchedEntry> = emptyList()

    @Volatile
    private var hintsByTraktId: Map<Int, TmdbProgressHint> = emptyMap()

    fun updateShows(shows: List<TraktWatchedEntry>) {
        cachedShows = shows
        hintsByTraktId = emptyMap()
    }

    fun updateEnrichedShows(shows: List<EnrichedShowEntry>) {
        cachedShows = shows.map { it.entry }
        hintsByTraktId = shows
            .mapNotNull { enriched ->
                val traktId = enriched.entry.show.ids.trakt ?: return@mapNotNull null
                val hint = enriched.tmdb ?: return@mapNotNull null
                traktId to hint
            }
            .toMap()
    }

    fun getCachedShows(): List<TraktWatchedEntry> = cachedShows

    fun getHint(ids: TraktIds): TmdbProgressHint? = ids.trakt?.let { hintsByTraktId[it] }
}
