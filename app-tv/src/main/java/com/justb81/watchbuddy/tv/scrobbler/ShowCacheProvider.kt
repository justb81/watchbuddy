package com.justb81.watchbuddy.tv.scrobbler

import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of the user's watched shows.
 * Fed by TvHomeViewModel when shows are loaded; read by MediaSessionScrobbler
 * to avoid unnecessary Trakt API calls during fuzzy matching.
 */
@Singleton
class ShowCacheProvider @Inject constructor() {

    @Volatile
    private var cachedShows: List<TraktWatchedEntry> = emptyList()

    fun updateShows(shows: List<TraktWatchedEntry>) {
        cachedShows = shows
    }

    fun getCachedShows(): List<TraktWatchedEntry> = cachedShows
}
