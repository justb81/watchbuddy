package com.justb81.watchbuddy.core.trakt

import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import kotlinx.serialization.Serializable
import retrofit2.http.*

interface TraktApiService {

    // ── Auth ──────────────────────────────────────────────────────────────────

    @POST("oauth/device/code")
    suspend fun requestDeviceCode(@Body body: DeviceCodeRequest): DeviceCodeResponse

    @POST("oauth/device/token")
    suspend fun pollDeviceToken(@Body body: DeviceTokenRequest): DeviceTokenResponse

    @POST("oauth/token")
    suspend fun refreshToken(@Body body: RefreshTokenRequest): DeviceTokenResponse

    // ── User ──────────────────────────────────────────────────────────────────

    @GET("users/me")
    suspend fun getProfile(@Header("Authorization") bearer: String): TraktUserProfile

    // ── Watched History ───────────────────────────────────────────────────────

    @GET("sync/watched/shows")
    suspend fun getWatchedShows(@Header("Authorization") bearer: String): List<TraktWatchedEntry>

    @GET("users/{username}/watched/shows")
    suspend fun getUserWatchedShows(
        @Header("Authorization") bearer: String,
        @Path("username") username: String
    ): List<TraktWatchedEntry>

    // ── Scrobble ──────────────────────────────────────────────────────────────

    @POST("scrobble/start")
    suspend fun scrobbleStart(
        @Header("Authorization") bearer: String,
        @Body body: ScrobbleBody
    ): ScrobbleResponse

    @POST("scrobble/pause")
    suspend fun scrobblePause(
        @Header("Authorization") bearer: String,
        @Body body: ScrobbleBody
    ): ScrobbleResponse

    @POST("scrobble/stop")
    suspend fun scrobbleStop(
        @Header("Authorization") bearer: String,
        @Body body: ScrobbleBody
    ): ScrobbleResponse

    // ── Search ────────────────────────────────────────────────────────────────

    @GET("search/show")
    suspend fun searchShow(
        @Header("Authorization") bearer: String,
        @Query("query") query: String,
        @Query("limit") limit: Int = 5
    ): List<TraktSearchResult>
}

// ── Request/Response DTOs ─────────────────────────────────────────────────────

@Serializable data class DeviceCodeRequest(val client_id: String)

@Serializable data class DeviceCodeResponse(
    val device_code: String,
    val user_code: String,
    val verification_url: String,
    val expires_in: Int,
    val interval: Int
)

@Serializable data class DeviceTokenRequest(
    val code: String,
    val client_id: String,
    val client_secret: String   // injected at runtime — never hardcoded
)

@Serializable data class DeviceTokenResponse(
    val access_token: String,
    val token_type: String,
    val expires_in: Int,
    val refresh_token: String,
    val scope: String
)

@Serializable data class RefreshTokenRequest(
    val refresh_token: String,
    val client_id: String,
    val client_secret: String,
    val grant_type: String = "refresh_token"
)

@Serializable data class TraktUserProfile(
    val username: String,
    val name: String? = null,
    val vip: Boolean = false
)

@Serializable data class ScrobbleBody(
    val show: TraktShow,
    val episode: TraktEpisode,
    val progress: Float     // 0.0–100.0
)

@Serializable data class ScrobbleResponse(
    val id: Long? = null,
    val action: String,
    val progress: Float,
    val show: TraktShow,
    val episode: TraktEpisode
)

@Serializable data class TraktSearchResult(
    val type: String,
    val score: Float? = null,
    val show: TraktShow? = null
)
