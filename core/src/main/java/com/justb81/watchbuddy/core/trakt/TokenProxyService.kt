package com.justb81.watchbuddy.core.trakt

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit-Interface für den WatchBuddy Token-Proxy-Backend.
 *
 * Das Backend liegt unter [TOKEN_BACKEND_URL] (BuildConfig) und tauscht
 * den Trakt device_code serverseitig gegen Access-/Refresh-Tokens aus,
 * ohne dass der client_secret jemals in die APK gelangen muss.
 *
 * Endpunkte (siehe backend/src/index.js):
 *   POST /trakt/token         — device_code → access_token + refresh_token
 *   POST /trakt/token/refresh — refresh_token → neue Tokens
 */
interface TokenProxyService {

    @POST("trakt/token")
    suspend fun exchangeDeviceCode(@Body body: ProxyTokenRequest): ProxyTokenResponse

    @POST("trakt/token/refresh")
    suspend fun refreshToken(@Body body: ProxyRefreshRequest): ProxyTokenResponse
}

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable
data class ProxyTokenRequest(val code: String)

@Serializable
data class ProxyRefreshRequest(val refresh_token: String)

@Serializable
data class ProxyTokenResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Int,
    val token_type: String,
    val scope: String
)
