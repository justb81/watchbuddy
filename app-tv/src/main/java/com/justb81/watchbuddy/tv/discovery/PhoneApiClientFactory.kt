package com.justb81.watchbuddy.tv.discovery

import androidx.annotation.VisibleForTesting
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneApiClientFactory @Inject constructor(
    httpClient: OkHttpClient
) {
    // LAN calls: fast connect; read timeout matches phone-side 75 s LLM cap with margin.
    private val timeoutClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(95, TimeUnit.SECONDS)
        .build()

    private val cache = mutableMapOf<String, PhoneApiService>()

    fun createClient(baseUrl: String): PhoneApiService =
        cache.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(timeoutClient)
                .addConverterFactory(WatchBuddyJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(PhoneApiService::class.java)
        }

    @VisibleForTesting
    internal fun cacheSize(): Int = cache.size
}
