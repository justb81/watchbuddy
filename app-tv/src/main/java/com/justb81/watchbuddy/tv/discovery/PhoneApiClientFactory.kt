package com.justb81.watchbuddy.tv.discovery

import com.justb81.watchbuddy.core.network.WatchBuddyJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneApiClientFactory @Inject constructor(
    private val httpClient: OkHttpClient
) {
    private val cache = mutableMapOf<String, PhoneApiService>()

    fun createClient(baseUrl: String): PhoneApiService =
        cache.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(httpClient)
                .addConverterFactory(WatchBuddyJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(PhoneApiService::class.java)
        }
}
