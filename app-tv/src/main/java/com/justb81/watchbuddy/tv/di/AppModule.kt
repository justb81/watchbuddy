package com.justb81.watchbuddy.tv.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.justb81.watchbuddy.core.scrobbler.ScrobbleDispatcher
import com.justb81.watchbuddy.core.scrobbler.WatchedShowSource
import com.justb81.watchbuddy.tv.data.getStreamingDataStore
import com.justb81.watchbuddy.tv.scrobbler.TvScrobbleDispatcher
import com.justb81.watchbuddy.tv.scrobbler.TvWatchedShowSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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

    companion object {

        @Provides
        @Singleton
        fun provideStreamingDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            context.getStreamingDataStore()

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
    }
}
