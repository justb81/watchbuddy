package com.justb81.watchbuddy.tv.discovery

import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface PhoneApiService {

    @GET("/shows")
    suspend fun getShows(): List<TraktWatchedEntry>

    @POST("/recap/{traktShowId}")
    suspend fun getRecap(@Path("traktShowId") showId: Int): RecapResponse

    @GET("/auth/token")
    suspend fun getAccessToken(): TokenResponse
}

@Serializable
data class TokenResponse(val accessToken: String)

@Serializable
data class RecapResponse(val html: String)
