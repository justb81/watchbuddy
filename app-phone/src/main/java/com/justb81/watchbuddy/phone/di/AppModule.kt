package com.justb81.watchbuddy.phone.di

import android.content.Context
import androidx.work.WorkManager
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.core.scrobbler.MetadataEnricher
import com.justb81.watchbuddy.core.scrobbler.NoOpTitleExtractor
import com.justb81.watchbuddy.core.scrobbler.TitleExtractor
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

    /**
     * True when the managed Trakt backend is fully configured in this build
     * (both TOKEN_BACKEND_URL and TRAKT_CLIENT_ID are non-blank).
     * Used by SettingsViewModel to disable the MANAGED auth mode option and
     * auto-expand advanced settings when bundled options are unavailable.
     */
    @Provides
    @Singleton
    @Named("managedBackendAvailable")
    fun provideManagedBackendAvailable(): Boolean =
        BuildConfig.TOKEN_BACKEND_URL.isNotBlank() && BuildConfig.TRAKT_CLIENT_ID.isNotBlank()

    /**
     * WorkManager singleton for injection into ViewModels.
     * Injecting rather than calling getInstance() directly keeps ViewModels testable.
     */
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    /**
     * The phone doesn't run [com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler]
     * today, but the core module binds the scrobbler as `@Singleton @Inject`, so the
     * graph still has to resolve its [TitleExtractor] dependency. The phone binds
     * [NoOpTitleExtractor] — if phone-side scrobbling is ever enabled, the phone
     * has the Trakt library in-process and can match without the HTTP detour.
     */
    @Provides
    @Singleton
    fun provideTitleExtractor(): TitleExtractor = NoOpTitleExtractor

    /** No enrichers registered in the phone app yet; populated per-app via Hilt. */
    @Provides
    @Singleton
    fun provideMetadataEnrichers(): List<MetadataEnricher> = emptyList()
}
