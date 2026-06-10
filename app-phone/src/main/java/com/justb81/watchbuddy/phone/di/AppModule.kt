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
import com.justb81.watchbuddy.core.tracking.SimklTrackingProvider
import com.justb81.watchbuddy.core.tracking.TrackingBackend
import com.justb81.watchbuddy.core.tracking.TrackingProvider
import com.justb81.watchbuddy.core.tracking.TraktTrackingProvider
import com.justb81.watchbuddy.phone.data.ProviderCatalogRepository
import com.justb81.watchbuddy.phone.network.WifiStateProvider
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
     * relays to TV via CompanionHttpServer. Falls back to in-code BUNDLED_ENTRIES /
     * BUNDLED_MAP constants when no live catalog has been fetched yet.
     */
    @Provides
    @Singleton
    fun provideProviderCatalogRepository(
        dataStore: DataStore<Preferences>,
        workManager: WorkManager,
    ): ProviderCatalogRepository = ProviderCatalogRepository(
        dataStore = dataStore,
        workManager = workManager,
        backendUrl = BuildConfig.TOKEN_BACKEND_URL,
    )

    /**
     * Provides the user-supplied SIMKL Client ID as a lambda so that
     * [SimklTrackingProvider] always reads the latest value from [SettingsRepository]
     * without requiring a singleton re-creation when the user updates the setting.
     *
     * The lambda is called on a coroutine-dispatched background thread inside
     * [SimklTrackingProvider]; calling [runBlocking] here would deadlock the main
     * thread, so the lambda is kept lazy and only evaluated by the provider during
     * an API call where a coroutine context is already available.
     */
    @Provides
    @Singleton
    @Named("simklClientIdProvider")
    fun provideSimklClientIdProvider(
        settingsRepository: SettingsRepository
    ): @JvmSuppressWildcards () -> String = {
        runBlocking { settingsRepository.settings.first().simklClientId }
    }

    /**
     * Provides the active [TrackingProvider] based on the user's configured
     * tracking backend. Reads [SettingsRepository] each time to pick the correct
     * implementation so that a backend switch (Trakt ↔ SIMKL) takes effect on
     * the next API call without any singleton restart.
     */
    @Provides
    @Singleton
    fun provideTrackingProvider(
        traktProvider: TraktTrackingProvider,
        simklProvider: SimklTrackingProvider,
        settingsRepository: SettingsRepository,
    ): TrackingProvider = object : TrackingProvider {

        private fun delegate(): TrackingProvider {
            val backend = runBlocking { settingsRepository.settings.first().trackingBackend }
            return if (backend == TrackingBackend.SIMKL) simklProvider else traktProvider
        }

        override val backend get() = delegate().backend

        override suspend fun getWatchedAndWatchlistShows(bearer: String) =
            delegate().getWatchedAndWatchlistShows(bearer)

        override suspend fun getSeasonsWithEpisodes(bearer: String, showId: String) =
            delegate().getSeasonsWithEpisodes(bearer, showId)

        override suspend fun markWatched(bearer: String, ids: com.justb81.watchbuddy.core.model.TraktIds, seasons: List<com.justb81.watchbuddy.core.trakt.SyncHistorySeasonItem>) =
            delegate().markWatched(bearer, ids, seasons)

        override suspend fun markUnwatched(bearer: String, ids: com.justb81.watchbuddy.core.model.TraktIds, season: Int, episode: Int) =
            delegate().markUnwatched(bearer, ids, season, episode)

        override suspend fun search(bearer: String, query: String) =
            delegate().search(bearer, query)

        override suspend fun addToWatchlist(bearer: String, show: com.justb81.watchbuddy.core.model.TraktShow) =
            delegate().addToWatchlist(bearer, show)

        override suspend fun getProfile(bearer: String) =
            delegate().getProfile(bearer)
    }
}
