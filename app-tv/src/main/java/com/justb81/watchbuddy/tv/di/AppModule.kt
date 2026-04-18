package com.justb81.watchbuddy.tv.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Token proxy backend URL — the TV app uses no token proxy, so this is blank.
     * NetworkModule.provideTokenProxyRetrofit() returns null when blank.
     */
    @Provides
    @Singleton
    fun provideTokenBackendUrl(): String = ""

    /**
     * Trakt Client ID — the TV app never calls the Trakt API directly
     * (all Trakt operations go through the phone proxy), so this is blank.
     * Required by NetworkModule.provideOkHttpClient(); when blank, no
     * `trakt-api-key` header is attached.
     */
    @Provides
    @Singleton
    @Named("traktClientId")
    fun provideTraktClientId(): String = ""
}
