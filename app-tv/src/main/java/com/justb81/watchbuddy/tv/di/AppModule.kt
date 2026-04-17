package com.justb81.watchbuddy.tv.di

import com.justb81.watchbuddy.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Debug-Flag für NetworkModule (steuert HTTP-Logging-Level). */
    @Provides
    @Singleton
    @Named("isDebugBuild")
    fun provideIsDebugBuild(): Boolean = BuildConfig.DEBUG

    /**
     * URL des Token-Proxy-Backends — die TV-App nutzt keinen Token-Proxy,
     * daher Leerstring. Wird von NetworkModule.provideTokenProxyRetrofit()
     * benötigt; bei leerem String wird null zurückgegeben.
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
