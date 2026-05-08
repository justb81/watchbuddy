package com.justb81.watchbuddy.phone.di

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.core.scrobbler.MetadataEnricher
import com.justb81.watchbuddy.core.scrobbler.NoOpPlaybackIntentProvider
import com.justb81.watchbuddy.core.scrobbler.NoOpTitleExtractor
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntentProvider
import com.justb81.watchbuddy.core.scrobbler.ScrobbleTuning
import com.justb81.watchbuddy.core.scrobbler.TitleExtractor
import com.justb81.watchbuddy.phone.data.ProviderCatalogRepository
import com.justb81.watchbuddy.phone.network.WifiStateProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

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

    /**
     * Provides the app's singleton [DataStore] for settings persistence.
     * Declared here so that [com.justb81.watchbuddy.phone.settings.SettingsRepository]
     * receives the store as an injected dependency and can be tested with a
     * substitute store in unit tests.
     */
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

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

    /**
     * The phone has no Watch-Now UI surface today, so there is nothing to capture
     * for Phase 0. Bind the no-op to keep the Hilt graph consistent with the TV app.
     */
    @Provides
    @Singleton
    fun providePlaybackIntentProvider(): PlaybackIntentProvider = NoOpPlaybackIntentProvider()

    /**
     * Production scrobble-tuning constants. Inject [ScrobbleTuning] rather than
     * reading [ScrobbleTuning.DEFAULT] directly so tests can substitute a custom
     * instance without subclassing [MediaSessionScrobbler].
     */
    @Provides
    @Singleton
    fun provideScrobbleTuning(): ScrobbleTuning = ScrobbleTuning.DEFAULT

    /**
     * Singleton [WifiStateProvider] wired to the process lifecycle so its
     * [ConnectivityManager.NetworkCallback] is automatically unregistered when
     * the process ends (#529). The observer is added on the main thread because
     * [androidx.lifecycle.Lifecycle.addObserver] requires it; the @Provides method
     * itself is invoked from Application.onCreate(), which runs on the main thread.
     */
    @Provides
    @Singleton
    fun provideWifiStateProvider(@ApplicationContext context: Context): WifiStateProvider {
        val provider = WifiStateProvider(context)
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(provider)
        }
        return provider
    }

    /**
     * Provider catalog repository — fetches versioned provider/JustWatch mapping JSON from
     * the backend (when [BuildConfig.TOKEN_BACKEND_URL] is set), caches in DataStore, and
     * relays to TV via CompanionHttpServer. Falls back to the bundled asset on first run
     * or when the backend is unavailable.
     */
    @Provides
    @Singleton
    fun provideProviderCatalogRepository(
        @ApplicationContext context: Context,
        dataStore: DataStore<Preferences>,
        workManager: WorkManager,
    ): ProviderCatalogRepository = ProviderCatalogRepository(
        context = context,
        dataStore = dataStore,
        workManager = workManager,
        backendUrl = BuildConfig.TOKEN_BACKEND_URL,
    )
}
