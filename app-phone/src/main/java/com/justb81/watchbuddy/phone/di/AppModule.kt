package com.justb81.watchbuddy.phone.di

import android.content.Context
import androidx.work.WorkManager
import com.justb81.watchbuddy.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * App-specific Hilt bindings.
 *
 * Exposes BuildConfig values as named strings in the Hilt graph so that core
 * modules (NetworkModule) can access them without directly depending on
 * app-phone's BuildConfig.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Trakt Client ID from BuildConfig (set via app-phone/build.gradle.kts). */
    @Provides
    @Singleton
    @Named("traktClientId")
    fun provideTraktClientId(): String = BuildConfig.TRAKT_CLIENT_ID

    /**
     * URL of the WatchBuddy token proxy backend from BuildConfig.
     * Empty string → no proxy, Trakt login disabled.
     */
    @Provides
    @Singleton
    fun provideTokenBackendUrl(): String = BuildConfig.TOKEN_BACKEND_URL

    /**
     * Default TMDB API v3 key from BuildConfig (set via TMDB_API_KEY env var at build time).
     * Empty string → no built-in key, users must supply their own in Settings.
     */
    @Provides
    @Singleton
    @Named("defaultTmdbApiKey")
    fun provideDefaultTmdbApiKey(): String = BuildConfig.DEFAULT_TMDB_API_KEY

    /** Debug flag for NetworkModule (controls HTTP logging level). */
    @Provides
    @Singleton
    @Named("isDebugBuild")
    fun provideIsDebugBuild(): Boolean = BuildConfig.DEBUG

    /**
     * WorkManager singleton for injection into ViewModels.
     * Injecting rather than calling getInstance() directly keeps ViewModels testable.
     */
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
