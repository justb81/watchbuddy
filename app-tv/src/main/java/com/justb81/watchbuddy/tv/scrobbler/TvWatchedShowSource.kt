package com.justb81.watchbuddy.tv.scrobbler

import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.scrobbler.WatchedShowSource
import com.justb81.watchbuddy.tv.data.TvShowCache
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvWatchedShowSource @Inject constructor(
    private val tvShowCache: TvShowCache,
    private val phoneDiscovery: PhoneDiscoveryManager
) : WatchedShowSource {

    override suspend fun getCachedShows(): List<TraktWatchedEntry> = tvShowCache.getCachedShows()

    override suspend fun getTmdbApiKey(): String? =
        phoneDiscovery.getBestPhone()?.capability?.tmdbApiKey
}
