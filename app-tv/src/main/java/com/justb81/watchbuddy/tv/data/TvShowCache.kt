package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of the user's Trakt watched shows.
 * Populated by [TvHomeViewModel] when shows are loaded from a phone.
 * Used by [MediaSessionScrobbler] for local fuzzy-matching before hitting the Trakt API.
 */
@Singleton
class TvShowCache @Inject constructor() {

    @Volatile
    private var cachedShows: List<TraktWatchedEntry> = emptyList()

    fun updateShows(shows: List<TraktWatchedEntry>) {
        cachedShows = shows
    }

    fun getCachedShows(): List<TraktWatchedEntry> = cachedShows
}
