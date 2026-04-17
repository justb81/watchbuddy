package com.justb81.watchbuddy.core.tmdb

import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.model.TmdbTvSearchResponse
import retrofit2.http.*

interface TmdbApiService {

    @GET("tv/{series_id}")
    suspend fun getShow(
        @Path("series_id") id: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        /**
         * TMDB returns `status`, `last_episode_to_air`, `next_episode_to_air`, and `seasons`
         * on the base tv-show resource without an explicit `append_to_response` — the parameter
         * is still accepted here in case callers want to request extra bundles in the future.
         */
        @Query("append_to_response") appendToResponse: String? = null
    ): TmdbShow

    @GET("tv/{series_id}/season/{season_number}/episode/{episode_number}")
    suspend fun getEpisode(
        @Path("series_id") seriesId: Int,
        @Path("season_number") seasonNumber: Int,
        @Path("episode_number") episodeNumber: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US"
    ): TmdbEpisode

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbTvSearchResponse
}

// Image URL helper
object TmdbImageHelper {
    private const val BASE_URL = "https://image.tmdb.org/t/p/"
    fun still(path: String?, width: Int = 300) = path?.let { "${BASE_URL}w${width}${it}" }
    fun poster(path: String?, width: Int = 500) = path?.let { "${BASE_URL}w${width}${it}" }
    fun backdrop(path: String?, width: Int = 1280) = path?.let { "${BASE_URL}w${width}${it}" }
}
