package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvShowCache @Inject constructor() {
    private var cachedShows: List<TraktWatchedEntry> = emptyList()

    fun updateShows(shows: List<TraktWatchedEntry>) {
        cachedShows = shows
    }

    fun getCachedShows(): List<TraktWatchedEntry> = cachedShows
}
