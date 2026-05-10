package com.justb81.watchbuddy.tv.discovery

import com.justb81.watchbuddy.core.model.AmbiguousScrobbleEvent
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.PhoneAddToLibraryRequest
import com.justb81.watchbuddy.core.model.TitleExtractionRequest
import com.justb81.watchbuddy.core.model.TitleExtractionResponse
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.model.TraktShow
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PhoneApiService {

    @GET("/shows")
    suspend fun getShows(
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = PAGE_SIZE
    ): List<EnrichedShowEntry>

    companion object {
        const val PAGE_SIZE = 30
    }

    @GET("/provider-catalog")
    suspend fun getProviderCatalog(): Response<ResponseBody>

    @POST("/recap/{traktShowId}")
    suspend fun getRecap(@Path("traktShowId") showId: Int): RecapResponse

    @POST("/scrobble/start")
    suspend fun scrobbleStart(@Body body: PhoneScrobbleRequest): PhoneScrobbleActionResponse

    @POST("/scrobble/pause")
    suspend fun scrobblePause(@Body body: PhoneScrobbleRequest): PhoneScrobbleActionResponse

    @POST("/scrobble/stop")
    suspend fun scrobbleStop(@Body body: PhoneScrobbleRequest): PhoneScrobbleActionResponse

    @POST("/scrobble/extract")
    suspend fun extractTitle(@Body body: TitleExtractionRequest): TitleExtractionResponse

    /**
     * Delivers an ambiguous scrobble prompt to the phone. Returns 204 No Content
     * when accepted; the prompt is consumed via state streams on the phone side.
     */
    @POST("/scrobble/prompt")
    suspend fun scrobblePrompt(@Body body: AmbiguousScrobbleEvent): PhoneScrobbleActionResponse

    /**
     * Adds ([show], [episode]) to the phone user's Trakt history.
     * Called on overlay confirm for unknown shows (TMDB-only candidates).
     */
    @POST("/shows/add-to-library")
    suspend fun addShowToLibrary(@Body body: PhoneAddToLibraryRequest): PhoneScrobbleActionResponse

    /**
     * Returns all seasons with their episodes for the given Trakt show ID.
     * Used by the TV to display the full episode list for manual mark-watched.
     */
    @GET("/shows/{showId}/seasons")
    suspend fun getSeasons(@Path("showId") showId: String): List<TraktSeasonWithEpisodes>

    /**
     * Marks a single episode as watched in the phone user's Trakt account.
     */
    @POST("/watched")
    suspend fun markWatched(@Body body: WatchedToggleRequest): Response<Unit>

    /**
     * Removes a single episode from the phone user's Trakt history.
     * Uses DELETE with a body because the Trakt sync/history/remove endpoint
     * expects a JSON payload identifying the episode.
     */
    @HTTP(method = "DELETE", path = "/watched", hasBody = true)
    suspend fun markUnwatched(@Body body: WatchedToggleRequest): Response<Unit>
}

@Serializable
data class RecapResponse(val html: String)

@Serializable
data class PhoneScrobbleRequest(
    val show: TraktShow,
    val episode: TraktEpisode,
    val progress: Float
)

@Serializable
data class PhoneScrobbleActionResponse(val success: Boolean)

@Serializable
data class WatchedToggleRequest(
    val showIds: TraktIds,
    val season: Int,
    val episode: Int,
    val resolvesSessionKey: String? = null,
)
