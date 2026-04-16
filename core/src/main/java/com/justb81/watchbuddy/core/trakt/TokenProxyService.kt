package com.justb81.watchbuddy.core.trakt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
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

// ── Error types ───────────────────────────────────────────────────────────────

sealed class TokenExchangeError : Exception() {
    data object ServerMisconfigured : TokenExchangeError()
}

/**
 * Returns true when the HTTP 503 response body contains
 * `{ "error": "server_misconfigured" }`, signalling that the backend
 * proxy is missing its Trakt credentials — not a user error.
 */
fun HttpException.isServerMisconfigured(): Boolean {
    if (code() != 503) return false
    val body = try {
        response()?.errorBody()?.string()
    } catch (_: Exception) { null } ?: return false
    return try {
        Json.parseToJsonElement(body)
            .jsonObject["error"]
            ?.jsonPrimitive?.content == "server_misconfigured"
    } catch (_: Exception) { false }
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
