package com.justb81.watchbuddy.phone.di

import com.justb81.watchbuddy.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * App-spezifische Hilt-Bindings.
 *
 * Stellt die BuildConfig-Werte als benannte Strings in den Hilt-Graph,
 * damit core-Module (NetworkModule) darauf zugreifen können, ohne direkt
 * von app-phone's BuildConfig abhängig zu sein.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Trakt Client-ID aus BuildConfig (gesetzt via app-phone/build.gradle.kts). */
    @Provides
    @Singleton
    @Named("traktClientId")
    fun provideTraktClientId(): String = BuildConfig.TRAKT_CLIENT_ID

    /**
     * URL des WatchBuddy Token-Proxy-Backends aus BuildConfig.
     * Leerstring → kein Proxy, Trakt-Login deaktiviert.
     */
    @Provides
    @Singleton
    fun provideTokenBackendUrl(): String = BuildConfig.TOKEN_BACKEND_URL
}
