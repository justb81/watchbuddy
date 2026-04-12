package com.justb81.watchbuddy.tv.scrobbler

import android.util.Log
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches the Trakt access token obtained from the phone companion app.
 * The TV app itself has no Trakt login — it delegates auth to the phone via
 * the companion HTTP API (GET /auth/token).
 *
 * Token is cached for [CACHE_TTL] to avoid excessive network calls.
 */
@Singleton
class TvTokenCache @Inject constructor(
    private val phoneApiClientFactory: PhoneApiClientFactory,
    private val phoneDiscovery: PhoneDiscoveryManager
) {
    companion object {
        private const val TAG = "TvTokenCache"
        private const val CACHE_TTL = 30 * 60 * 1000L // 30 minutes
    }

    @Volatile
    private var cachedToken: String? = null
    private var tokenTimestamp: Long = 0L

    suspend fun getToken(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { token ->
            if (now - tokenTimestamp < CACHE_TTL) return token
        }

        val phone = phoneDiscovery.getBestPhone() ?: return null
        return try {
            val client = phoneApiClientFactory.createClient(phone.baseUrl)
            val response = client.getAccessToken()
            cachedToken = response.accessToken
            tokenTimestamp = System.currentTimeMillis()
            response.accessToken
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch access token from phone", e)
            null
        }
    }

    fun invalidate() {
        cachedToken = null
        tokenTimestamp = 0L
    }
}
