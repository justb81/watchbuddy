package com.justb81.watchbuddy.core.network

import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.trakt.TokenProxyService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @Named("httpLoggingLevel") loggingLevel: HttpLoggingInterceptor.Level
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            // Trakt required headers
            val request = chain.request().newBuilder()
                .addHeader("Content-Type", "application/json")
                .addHeader("trakt-api-version", "2")
                // client_id is added per-request from secure storage
                .build()
            chain.proceed(request)
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = loggingLevel
        })
        .build()

    @Provides
    @Singleton
    @Named("trakt")
    fun provideTraktRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.trakt.tv/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    @Named("tmdb")
    fun provideTmdbRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
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
     * Retrofit-Instanz für den Token-Proxy-Backend.
     *
     * Wird nur bereitgestellt, wenn [backendUrl] nicht leer ist — d.h. wenn
     * BuildConfig.TOKEN_BACKEND_URL in app-phone/build.gradle.kts gesetzt ist.
     * Andernfalls ist [TokenProxyService] im Hilt-Graph nicht verfügbar und
     * der Onboarding-Flow blendet die Trakt-Anmeldung aus.
     *
     * Hinweis: Der OkHttpClient wird hier bewusst ohne die Trakt-spezifischen
     * Header verwendet — der Proxy braucht kein 'trakt-api-version'-Header.
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
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideTokenProxyService(
        @Named("tokenProxy") retrofit: Retrofit?
    ): TokenProxyService? = retrofit?.create(TokenProxyService::class.java)
}
