package com.justb81.watchbuddy.core.network

import com.justb81.watchbuddy.core.justwatch.JustWatchApiService
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.trakt.NoOpTokenProxyService
import com.justb81.watchbuddy.core.trakt.TokenProxyService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

private const val JUSTWATCH_TIMEOUT_SECONDS = 5L

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @Named("traktClientId") traktClientId: String,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .addHeader("Content-Type", "application/json")
                .addHeader("trakt-api-version", "2")
            if (traktClientId.isNotBlank()) {
                builder.addHeader("trakt-api-key", traktClientId)
            }
            chain.proceed(builder.build())
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (com.justb81.watchbuddy.core.BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    /**
     * Plain OkHttpClient for large file downloads (e.g. LLM models from Hugging Face).
     *
     * Intentionally omits all API-specific configuration: no logging interceptor
     * (which would buffer the entire response body in memory, causing OOM on
     * multi-GB downloads), no certificate pinning (not needed for CDN hosts),
     * and no Trakt headers.
     */
    @Provides
    @Singleton
    @Named("download")
    fun provideDownloadClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @Named("trakt")
    fun provideTraktRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.trakt.tv/")
        .client(client)
        .addConverterFactory(WatchBuddyJson.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    @Named("tmdb")
    fun provideTmdbRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(client)
        .addConverterFactory(WatchBuddyJson.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideTraktApiService(@Named("trakt") retrofit: Retrofit): TraktApiService =
        retrofit.create(TraktApiService::class.java)

    @Provides
    @Singleton
    fun provideTmdbApiService(@Named("tmdb") retrofit: Retrofit): TmdbApiService =
        retrofit.create(TmdbApiService::class.java)

    /**
     * Dedicated OkHttpClient for JustWatch GraphQL calls.
     *
     * TV-direct — the phone is never involved. Short timeout keeps the detail
     * screen from hanging on slow connections; cache absorbs most latency after
     * the first visit.
     */
    @Provides
    @Singleton
    @Named("justwatch")
    fun provideJustWatchOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(JUSTWATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(JUSTWATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @Named("justwatch")
    fun provideJustWatchRetrofit(@Named("justwatch") client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://apis.justwatch.com/")
        .client(client)
        .addConverterFactory(WatchBuddyJson.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideJustWatchApiService(@Named("justwatch") retrofit: Retrofit): JustWatchApiService =
        retrofit.create(JustWatchApiService::class.java)

    /**
     * TokenProxyService for the WatchBuddy managed token proxy backend.
     *
     * Returns a real Retrofit-backed implementation when [backendUrl] is non-blank
     * (i.e. TOKEN_BACKEND_URL is set in app-phone/build.gradle.kts). Returns a
     * [NoOpTokenProxyService] when the URL is blank — callers must check the
     * `@Named("managedBackendAvailable")` flag before invoking proxy methods.
     *
     * Note: The OkHttpClient is intentionally used without Trakt-specific
     * headers — the proxy does not require a 'trakt-api-version' header.
     */
    @Provides
    @Singleton
    fun provideTokenProxyService(
        backendUrl: @JvmSuppressWildcards String,
        client: OkHttpClient
    ): TokenProxyService {
        if (backendUrl.isBlank()) return NoOpTokenProxyService()
        val url = if (backendUrl.endsWith("/")) backendUrl else "$backendUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(WatchBuddyJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TokenProxyService::class.java)
    }
}
