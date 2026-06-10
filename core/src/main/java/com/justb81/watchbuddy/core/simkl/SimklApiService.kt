package com.justb81.watchbuddy.core.simkl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the SIMKL REST API (base URL: https://api.simkl.com/).
 *
 * Authentication: every request requires `simkl-api-key: {client_id}` injected by
 * the dedicated [com.justb81.watchbuddy.core.network.NetworkQualifiers.SimklClient]
 * OkHttpClient interceptor. User endpoints additionally require
 * `Authorization: Bearer {access_token}` passed per-call.
 *
 * SIMKL access tokens do not expire and have no refresh token. The PIN flow returns
 * the access_token directly from the poll endpoint keyed on `client_id` only;
 * `client_secret` is stored in the Keystore for future-proofing but is not required
 * for the PIN exchange itself.
 */
interface SimklApiService {

    // ── OAuth PIN flow ────────────────────────────────────────────────────────

    /**
     * Initiates the device PIN flow. Returns a [SimklPinResponse] containing
     * the user_code to display and the verification_url to instruct the user to visit.
     */
    @GET("oauth/pin")
    suspend fun requestPinCode(
        @Query("client_id") clientId: String
    ): SimklPinResponse

    /**
     * Polls for PIN completion. Returns [SimklPinPoll] with `result = "OK"` and
     * `access_token` once the user has completed authorization.
     */
    @GET("oauth/pin/{userCode}")
    suspend fun pollPin(
        @Path("userCode") userCode: String,
        @Query("client_id") clientId: String
    ): SimklPinPoll

    // ── User profile ──────────────────────────────────────────────────────────

    @GET("users/settings")
    suspend fun getProfile(
        @Header("Authorization") bearer: String
    ): SimklUserSettings

    // ── Library (watched + watchlist) ─────────────────────────────────────────

    /**
     * Returns the user's complete library: watched shows and plantowatch (watchlist).
     * Passing `extended=full` includes season and episode data in one call.
     */
    @GET("sync/all-items/shows")
    suspend fun getAllShows(
        @Header("Authorization") bearer: String,
        @Query("extended") extended: String = "full"
    ): SimklAllItems

    // ── Sync history ─────────────────────────────────────────────────────────

    @POST("sync/history")
    suspend fun addToHistory(
        @Header("Authorization") bearer: String,
        @Body body: SimklSyncBody
    ): SimklSyncResult

    @POST("sync/history/remove")
    suspend fun removeFromHistory(
        @Header("Authorization") bearer: String,
        @Body body: SimklSyncBody
    ): SimklSyncResult

    // ── Watchlist ─────────────────────────────────────────────────────────────

    @POST("sync/watchlist")
    suspend fun addToWatchlist(
        @Header("Authorization") bearer: String,
        @Body body: SimklSyncBody
    ): SimklSyncResult

    // ── Search ────────────────────────────────────────────────────────────────

    @GET("search/tv")
    suspend fun searchShow(
        @Query("q") query: String,
        @Query("client_id") clientId: String
    ): List<SimklSearchResult>
}

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable
data class SimklPinResponse(
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int
)

@Serializable
data class SimklPinPoll(
    val result: String,
    @SerialName("access_token") val accessToken: String? = null
)

@Serializable
data class SimklUserSettings(
    val user: SimklUser? = null
)

@Serializable
data class SimklUser(
    val name: String? = null,
    val username: String? = null,
    val avatar: String? = null
)

@Serializable
data class SimklAllItems(
    val shows: List<SimklShowItem> = emptyList()
)

@Serializable
data class SimklShowItem(
    val show: SimklShow,
    val status: String? = null,
    val seasons: List<SimklSeason> = emptyList(),
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    @SerialName("watched_episodes_count") val watchedEpisodesCount: Int = 0
)

@Serializable
data class SimklShow(
    val title: String,
    val year: Int? = null,
    val ids: SimklIds
)

@Serializable
data class SimklIds(
    val simkl: Int? = null,
    @SerialName("simkl_id") val simklId: Int? = null,
    val tmdb: Int? = null,
    val imdb: String? = null,
    val tvdb: Int? = null,
    val slug: String? = null
) {
    /** Returns the canonical SIMKL numeric ID: prefers [simkl], falls back to [simklId]. */
    val canonicalSimklId: Int? get() = simkl ?: simklId
}

@Serializable
data class SimklSeason(
    val number: Int,
    val episodes: List<SimklEpisode> = emptyList()
)

@Serializable
data class SimklEpisode(
    val number: Int,
    @SerialName("watched_at") val watchedAt: String? = null
)

@Serializable
data class SimklSyncBody(
    val shows: List<SimklSyncShowItem>
)

@Serializable
data class SimklSyncShowItem(
    val ids: SimklIds,
    val seasons: List<SimklSyncSeason> = emptyList()
)

@Serializable
data class SimklSyncSeason(
    val number: Int,
    val episodes: List<SimklSyncEpisode>
)

@Serializable
data class SimklSyncEpisode(
    val number: Int
)

@Serializable
data class SimklSyncResult(
    val added: SimklSyncCount? = null,
    val deleted: SimklSyncCount? = null,
    @SerialName("not_found") val notFound: SimklNotFound? = null
)

@Serializable
data class SimklSyncCount(
    val episodes: Int = 0,
    val shows: Int = 0
)

@Serializable
data class SimklNotFound(
    val shows: List<SimklNotFoundItem> = emptyList()
)

@Serializable
data class SimklNotFoundItem(
    val ids: SimklIds? = null
)

@Serializable
data class SimklSearchResult(
    val title: String? = null,
    val year: Int? = null,
    val ids: SimklIds? = null,
    val type: String? = null,
    val scores: SimklSearchScores? = null
)

@Serializable
data class SimklSearchScores(
    val best: Float? = null
)
