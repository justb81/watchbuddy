package com.justb81.watchbuddy.phone.server

import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShowRepository @Inject constructor(
    private val traktApi: TraktApiService,
    private val tokenRefreshManager: TokenRefreshManager
) {
    private var cachedShows: List<TraktWatchedEntry> = emptyList()
    private var lastFetch: Long = 0L

    suspend fun getShows(): List<TraktWatchedEntry> {
        val now = System.currentTimeMillis()
        if (now - lastFetch > CACHE_TTL || cachedShows.isEmpty()) {
            val token = tokenRefreshManager.getValidAccessToken()
                ?: return emptyList()
            cachedShows = traktApi.getWatchedShows("Bearer $token")
            lastFetch = now
        }
        return cachedShows
    }

    private companion object {
        const val CACHE_TTL = 5 * 60 * 1000L // 5 minutes
    }
}
