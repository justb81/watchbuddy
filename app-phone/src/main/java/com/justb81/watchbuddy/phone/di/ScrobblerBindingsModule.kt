package com.justb81.watchbuddy.phone.di

import com.justb81.watchbuddy.core.scrobbler.ScrobbleDispatcher
import com.justb81.watchbuddy.core.scrobbler.WatchedShowSource
import com.justb81.watchbuddy.phone.scrobbler.PhoneScrobbleDispatcher
import com.justb81.watchbuddy.phone.scrobbler.PhoneWatchedShowSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ScrobblerBindingsModule {

    @Binds
    abstract fun bindWatchedShowSource(impl: PhoneWatchedShowSource): WatchedShowSource

    @Binds
    abstract fun bindScrobbleDispatcher(impl: PhoneScrobbleDispatcher): ScrobbleDispatcher
}
