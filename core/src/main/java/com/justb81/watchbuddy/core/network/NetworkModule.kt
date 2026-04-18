package com.justb81.watchbuddy.core.network

import com.justb81.watchbuddy.core.tmdb.TmdbApiService
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

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @Named("isDebugBuild") isDebug: Boolean,
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
            level = if (isDebug) HttpLoggingInterceptor.Level.BODY
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
     * Retrofit instance for the token proxy backend.
     *
     * Only provided when [backendUrl] is non-blank — i.e. when
     * BuildConfig.TOKEN_BACKEND_URL is set in app-phone/build.gradle.kts.
     * Otherwise [TokenProxyService] is unavailable in the Hilt graph and
     * the onboarding flow hides the Trakt sign-in option.
     *
     * Note: The OkHttpClient is intentionally used without Trakt-specific
     * headers — the proxy does not require a 'trakt-api-version' header.
     */
    @Provides
    @Singleton
    @Named("tokenProxy")
    fun provideTokenProxyRetrofit(
        backendUrl: @JvmSuppressWildcards String,
        client: OkHttpClient
    ): Retrofit? {
        if (backendUrl.isBlank()) return null
        val url = if (backendUrl.endsWith("/")) backendUrl else "$backendUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(WatchBuddyJson.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideTokenProxyService(
        @Named("tokenProxy") retrofit: Retrofit?
    ): TokenProxyService? = retrofit?.create(TokenProxyService::class.java)
}
