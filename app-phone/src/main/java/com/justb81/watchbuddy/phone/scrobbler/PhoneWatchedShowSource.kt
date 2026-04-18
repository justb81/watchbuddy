package com.justb81.watchbuddy.phone.scrobbler

import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.scrobbler.WatchedShowSource
import com.justb81.watchbuddy.phone.server.ShowRepository
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneWatchedShowSource @Inject constructor(
    private val showRepository: ShowRepository,
    private val settingsRepository: SettingsRepository
) : WatchedShowSource {

    override suspend fun getCachedShows(): List<TraktWatchedEntry> =
        showRepository.getShows().map { it.entry }

    override suspend fun getTmdbApiKey(): String? =
        runCatching { settingsRepository.getTmdbApiKey().first() }
            .getOrDefault("")
            .takeIf { it.isNotBlank() }
}
