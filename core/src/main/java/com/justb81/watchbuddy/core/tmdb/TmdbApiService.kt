package com.justb81.watchbuddy.core.tmdb

import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.core.model.TmdbShow
import kotlinx.serialization.Serializable
import retrofit2.http.*

interface TmdbApiService {

    @GET("search/tv")
    suspend fun searchShow(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String = "de-DE"
    ): TmdbSearchResponse

    @GET("tv/{series_id}")
    suspend fun getShow(
        @Path("series_id") id: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "de-DE"
    ): TmdbShow

    @GET("tv/{series_id}/season/{season_number}/episode/{episode_number}")
    suspend fun getEpisode(
        @Path("series_id") seriesId: Int,
        @Path("season_number") seasonNumber: Int,
        @Path("episode_number") episodeNumber: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "de-DE"
    ): TmdbEpisode

    @GET("tv/{series_id}/season/{season_number}")
    suspend fun getSeason(
        @Path("series_id") seriesId: Int,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "de-DE"
    ): TmdbSeasonResponse
}

@Serializable
data class TmdbSearchResponse(
    val results: List<TmdbShow>,
    val total_results: Int = 0
)

@Serializable
data class TmdbSeasonResponse(
    val id: Int,
    val season_number: Int,
    val episodes: List<TmdbEpisode>
)

// Image URL helper
object TmdbImageHelper {
    private const val BASE_URL = "https://image.tmdb.org/t/p/"
    fun still(path: String?, width: Int = 300) = path?.let { "${BASE_URL}w${width}${it}" }
    fun poster(path: String?, width: Int = 500) = path?.let { "${BASE_URL}w${width}${it}" }
    fun backdrop(path: String?, width: Int = 1280) = path?.let { "${BASE_URL}w${width}${it}" }
}
