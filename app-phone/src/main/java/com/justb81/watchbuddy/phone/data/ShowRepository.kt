package com.justb81.watchbuddy.phone.data

import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShowRepository @Inject constructor(
    private val traktApi: TraktApiService,
    private val tokenRepository: TokenRepository
) {
    private var cachedShows: List<TraktWatchedEntry> = emptyList()
    private var lastFetch: Long = 0L

    suspend fun getShows(forceRefresh: Boolean = false): List<TraktWatchedEntry> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - lastFetch < CACHE_TTL && cachedShows.isNotEmpty()) {
            return cachedShows
        }
        val token = tokenRepository.getAccessToken() ?: return cachedShows
        cachedShows = traktApi.getWatchedShows("Bearer $token")
        lastFetch = now
        return cachedShows
    }

    fun getCachedShows(): List<TraktWatchedEntry> = cachedShows

    fun invalidateCache() {
        cachedShows = emptyList()
        lastFetch = 0L
    }

    companion object {
        private const val CACHE_TTL = 5 * 60 * 1000L // 5 minutes
    }
}
