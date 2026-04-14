package com.justb81.watchbuddy.core.network

import com.justb81.watchbuddy.core.trakt.TokenProxyService
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating [TokenProxyService] instances from arbitrary base URLs.
 *
 * Used when the user configures a self-hosted token proxy via Settings
 * instead of relying on the build-time TOKEN_BACKEND_URL.
 */
@Singleton
class TokenProxyServiceFactory @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun create(baseUrl: String): TokenProxyService {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TokenProxyService::class.java)
    }
}
