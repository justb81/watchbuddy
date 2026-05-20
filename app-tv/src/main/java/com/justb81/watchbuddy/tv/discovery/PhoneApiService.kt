package com.justb81.watchbuddy.tv.discovery

import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
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
data class WatchedToggleRequest(
    val showIds: TraktIds,
    val season: Int,
    val episode: Int,
)
