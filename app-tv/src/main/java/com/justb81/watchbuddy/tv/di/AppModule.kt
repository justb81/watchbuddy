package com.justb81.watchbuddy.tv.di

import com.justb81.watchbuddy.core.scrobbler.MetadataEnricher
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntentProvider
import com.justb81.watchbuddy.core.scrobbler.ScrobbleDispatcher
import com.justb81.watchbuddy.core.scrobbler.TitleExtractor
import com.justb81.watchbuddy.core.scrobbler.WatchedShowSource
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
import dagger.hilt.components.SingletonComponent
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
        @Provides
        @Singleton
        fun provideTokenBackendUrl(): String = ""

        /**
         * Trakt Client ID — the TV app never calls the Trakt API directly
         * (all Trakt operations go through the phone proxy), so this is blank.
         * Satisfies NetworkModule's constructor requirement; no `trakt-api-key` header is attached.
         */
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
    }
}
