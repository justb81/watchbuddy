package com.justb81.watchbuddy.phone.server

import android.util.Log
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShowRepository"

@Singleton
class ShowRepository @Inject constructor(
    private val traktApi: TraktApiService,
    private val tokenRepository: TokenRepository
) {
    private var cachedShows: List<TraktWatchedEntry> = emptyList()
    private var lastFetch: Long = 0L

    suspend fun getShows(): List<TraktWatchedEntry> {
        val now = System.currentTimeMillis()
        if (now - lastFetch > CACHE_TTL || cachedShows.isEmpty()) {
            val token = tokenRepository.getAccessToken()
                ?: return emptyList()
            try {
                cachedShows = traktApi.getWatchedShows("Bearer $token")
                lastFetch = now
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch shows from Trakt; serving ${cachedShows.size} cached entries", e)
                // Do not update lastFetch so the next call retries the API.
            }
        }
        return cachedShows
    }

    private companion object {
        const val CACHE_TTL = 5 * 60 * 1000L // 5 minutes
    }
}
