package com.justb81.watchbuddy.phone

import com.justb81.watchbuddy.core.model.*

object TestFixtures {

    fun traktIds(trakt: Int? = 1, slug: String? = "test-show", tmdb: Int? = 100) =
        TraktIds(trakt = trakt, slug = slug, tmdb = tmdb)

    fun traktShow(title: String = "Test Show", year: Int? = 2024, ids: TraktIds = traktIds()) =
        TraktShow(title = title, year = year, ids = ids)

    fun traktEpisode(season: Int = 1, number: Int = 1, title: String? = "Pilot") =
        TraktEpisode(season = season, number = number, title = title)

    fun traktWatchedEntry(show: TraktShow = traktShow()) =
        TraktWatchedEntry(show = show)

    fun tmdbShow(id: Int = 100, name: String = "Test Show", overview: String? = "Overview.") =
        TmdbShow(id = id, name = name, overview = overview)

    fun tmdbEpisode(
        id: Int = 1,
        name: String = "Pilot",
        overview: String? = "The first episode.",
        stillPath: String? = "/still.jpg",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1
    ) = TmdbEpisode(
        id = id, name = name, overview = overview,
        still_path = stillPath, season_number = seasonNumber,
        episode_number = episodeNumber
    )

    fun deviceCapability(
        deviceId: String = "device-1",
        userName: String = "testuser",
        deviceName: String = "Pixel 8",
        llmBackend: LlmBackend = LlmBackend.LITERT,
        modelQuality: Int = 75,
        freeRamMb: Int = 4000,
        isAvailable: Boolean = true,
        tmdbConfigured: Boolean = false
    ) = DeviceCapability(
        deviceId = deviceId, userName = userName, deviceName = deviceName,
        llmBackend = llmBackend, modelQuality = modelQuality,
        freeRamMb = freeRamMb, isAvailable = isAvailable,
        tmdbConfigured = tmdbConfigured
    )
}
