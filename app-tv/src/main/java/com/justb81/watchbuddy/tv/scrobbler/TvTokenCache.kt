package com.justb81.watchbuddy.tv.scrobbler

import android.util.Log
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches the Trakt access tokens obtained from companion phone apps.
 * The TV app itself has no Trakt login — it delegates auth to phones via
 * the companion HTTP API (GET /auth/token).
 *
 * Tokens are cached per phone for [CACHE_TTL] to avoid excessive network calls.
 *
 * - [getToken] returns the best phone's token (for single-user operations like search).
 * - [getAllTokens] returns tokens for all available phones (for multi-user scrobbling).
 */
@Singleton
class TvTokenCache @Inject constructor(
    private val phoneApiClientFactory: PhoneApiClientFactory,
    private val phoneDiscovery: PhoneDiscoveryManager
) {
    companion object {
        private const val TAG = "TvTokenCache"
        const val CACHE_TTL = 30 * 60 * 1000L // 30 minutes
    }

    /** A token paired with the phone's device ID for identification. */
    data class PhoneToken(val phoneId: String, val token: String)

    private data class CachedEntry(val token: String, val timestamp: Long)

    /** Per-phone token cache; key = phone baseUrl. */
    private val tokenCache = ConcurrentHashMap<String, CachedEntry>()

    /**
     * Returns the access token for the best available phone.
     * Used for Trakt API calls that only need a single token (e.g., show search).
     */
    suspend fun getToken(): String? {
        val phone = phoneDiscovery.getBestPhone() ?: return null
        return fetchOrGetCachedToken(phone)
    }

    /**
     * Returns access tokens for all currently available phones.
     * Used for multi-user scrobbling so every connected user's watch history
     * is recorded independently on their own Trakt account.
     *
     * Phones that fail to return a token are silently skipped so one
     * unreachable device does not block scrobbling for the others.
     */
    suspend fun getAllTokens(): List<PhoneToken> {
        val phones = phoneDiscovery.discoveredPhones.value
            .filter { it.capability?.isAvailable == true }
        if (phones.isEmpty()) return emptyList()

        return coroutineScope {
            phones.map { phone ->
                async {
                    fetchOrGetCachedToken(phone)?.let { token ->
                        PhoneToken(
                            phoneId = phone.capability?.deviceId ?: phone.baseUrl,
                            token = token
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun fetchOrGetCachedToken(phone: PhoneDiscoveryManager.DiscoveredPhone): String? {
        val now = System.currentTimeMillis()
        tokenCache[phone.baseUrl]?.let { entry ->
            if (now - entry.timestamp < CACHE_TTL) return entry.token
        }

        return try {
            val client = phoneApiClientFactory.createClient(phone.baseUrl)
            val response = client.getAccessToken()
            tokenCache[phone.baseUrl] = CachedEntry(response.accessToken, System.currentTimeMillis())
            response.accessToken
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch token from phone at ${phone.baseUrl}", e)
            null
        }
    }

    fun invalidate() {
        tokenCache.clear()
    }
}
