package com.justb81.watchbuddy.tv.network

import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

interface PhoneApiService {
    @GET("/shows")
    suspend fun getShows(): List<TraktWatchedEntry>

    @GET("/auth/token")
    suspend fun getAccessToken(): TokenResponse

    @POST("/recap/{traktShowId}")
    suspend fun getRecap(@Path("traktShowId") showId: Int): RecapResponse
}

@Serializable
data class TokenResponse(
    val access_token: String
)

@Serializable
data class RecapResponse(
    val html: String
)

@Singleton
class PhoneApiClientFactory @Inject constructor(
    private val httpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun createClient(baseUrl: String): PhoneApiService {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PhoneApiService::class.java)
    }
}
