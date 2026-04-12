package com.justb81.watchbuddy.tv.di

import com.justb81.watchbuddy.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** HTTP-Logging: BODY in Debug, NONE in Release (verhindert Token-Leaks). */
    @Provides
    @Singleton
    @Named("httpLoggingLevel")
    fun provideHttpLoggingLevel(): HttpLoggingInterceptor.Level =
        if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
        else HttpLoggingInterceptor.Level.NONE
}
