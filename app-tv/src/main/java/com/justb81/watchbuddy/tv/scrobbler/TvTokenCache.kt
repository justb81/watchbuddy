package com.justb81.watchbuddy.tv.scrobbler

import android.util.Log
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache for the Trakt access token obtained from the phone companion.
 * The TV itself has no Trakt login — it fetches the token via the phone's HTTP API.
 */
@Singleton
class TvTokenCache @Inject constructor(
    private val phoneApiClientFactory: PhoneApiClientFactory,
    private val phoneDiscovery: PhoneDiscoveryManager
) {
    companion object {
        private const val TAG = "TvTokenCache"
        private const val DEFAULT_TTL_MS = 3_600_000L  // 1 hour fallback
    }

    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0L

    suspend fun getToken(): String? {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiry) {
            return cachedToken
        }

        val phone = phoneDiscovery.getBestPhone() ?: run {
            Log.w(TAG, "No companion phone discovered")
            return null
        }
        val baseUrl = "http://${phone.serviceInfo.host.hostAddress}:${phone.serviceInfo.port}"
        val client = phoneApiClientFactory.createClient(baseUrl)

        return try {
            val response = client.getAccessToken()
            cachedToken = response.accessToken
            tokenExpiry = System.currentTimeMillis() + (response.expiresIn * 1_000L)
                .coerceAtMost(DEFAULT_TTL_MS)
            cachedToken
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch token from phone: ${e.message}")
            null
        }
    }

    fun invalidate() {
        cachedToken = null
        tokenExpiry = 0L
    }
}
