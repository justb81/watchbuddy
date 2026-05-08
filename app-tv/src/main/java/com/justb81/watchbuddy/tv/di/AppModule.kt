package com.justb81.watchbuddy.tv.di

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.justb81.watchbuddy.core.scrobbler.MetadataEnricher
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntentProvider
import com.justb81.watchbuddy.core.scrobbler.ScrobbleDispatcher
import com.justb81.watchbuddy.core.scrobbler.ScrobbleTuning
import com.justb81.watchbuddy.core.scrobbler.TitleExtractor
import com.justb81.watchbuddy.core.scrobbler.WatchedShowSource
import com.justb81.watchbuddy.tv.data.ProviderCatalogCacheDao
import com.justb81.watchbuddy.tv.data.TvProviderCatalogRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.discovery.PhoneTitleExtractionClient
import com.justb81.watchbuddy.tv.scrobbler.NotificationMetadataSource
import com.justb81.watchbuddy.tv.scrobbler.PlaybackIntentRegistry
import com.justb81.watchbuddy.tv.scrobbler.TvScrobbleDispatcher
import com.justb81.watchbuddy.tv.scrobbler.TvWatchedShowSource
import com.justb81.watchbuddy.tv.scrobbler.WatchNextMetadataSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindWatchedShowSource(impl: TvWatchedShowSource): WatchedShowSource

    @Binds
    abstract fun bindScrobbleDispatcher(impl: TvScrobbleDispatcher): ScrobbleDispatcher

    @Binds
    abstract fun bindTitleExtractor(impl: PhoneTitleExtractionClient): TitleExtractor

    @Binds
    abstract fun bindPlaybackIntentProvider(impl: PlaybackIntentRegistry): PlaybackIntentProvider

    companion object {

        /**
         * Token proxy backend URL — the TV app uses no token proxy, so this is blank.
         * Satisfies NetworkModule's constructor requirement; no token requests are made from TV.
         */
        @Suppress("FunctionOnlyReturningConstant")
        @Provides
        @Singleton
        fun provideTokenBackendUrl(): String = ""

        /**
         * Trakt Client ID — the TV app never calls the Trakt API directly
         * (all Trakt operations go through the phone proxy), so this is blank.
         * Satisfies NetworkModule's constructor requirement; no `trakt-api-key` header is attached.
         */
        @Suppress("FunctionOnlyReturningConstant")
        @Provides
        @Singleton
        @Named("traktClientId")
        fun provideTraktClientId(): String = ""

        /** Ordered enricher list: WatchNext first, then media notifications. */
        @Provides
        @Singleton
        fun provideMetadataEnrichers(
            watchNext: WatchNextMetadataSource,
            notification: NotificationMetadataSource,
        ): List<MetadataEnricher> = listOf(watchNext, notification)

        /**
         * Production scrobble-tuning constants. Inject [ScrobbleTuning] rather than
         * reading [ScrobbleTuning.DEFAULT] directly so tests can substitute a custom
         * instance without subclassing [MediaSessionScrobbler].
         */
        @Provides
        @Singleton
        fun provideScrobbleTuning(): ScrobbleTuning = ScrobbleTuning.DEFAULT

        /**
         * Application-level [CoroutineScope] backed by a [SupervisorJob] so that
         * individual child failures do not cancel the whole scope. Used by
         * [com.justb81.watchbuddy.tv.boot.BootReceiver] to outlive the broadcast
         * dispatch window via [android.content.BroadcastReceiver.goAsync].
         */
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides
        @Singleton
        fun provideProcessLifecycleOwner(): LifecycleOwner = ProcessLifecycleOwner.get()

        /**
         * TV provider catalog repository — fetches catalog from the best-connected phone,
         * persists in Room, and applies to the [ProviderCatalog] and [JustWatchPackageMap]
         * façades. Falls back to the bundled asset when no phone is reachable.
         */
        @Provides
        @Singleton
        fun provideTvProviderCatalogRepository(
            @ApplicationContext context: Context,
            dao: ProviderCatalogCacheDao,
            discoveryManager: PhoneDiscoveryManager,
        ): TvProviderCatalogRepository = TvProviderCatalogRepository(context, dao, discoveryManager)
    }
}
