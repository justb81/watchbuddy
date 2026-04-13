package com.justb81.watchbuddy.tv

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

    fun scrobbleCandidate(
        packageName: String = "com.netflix.ninja",
        mediaTitle: String = "Breaking Bad S01E01",
        confidence: Float = 0.95f,
        matchedShow: TraktShow? = traktShow(title = "Breaking Bad"),
        matchedEpisode: TraktEpisode? = traktEpisode()
    ) = ScrobbleCandidate(
        packageName = packageName, mediaTitle = mediaTitle,
        confidence = confidence, matchedShow = matchedShow,
        matchedEpisode = matchedEpisode
    )

    fun streamingService(
        id: String = "netflix",
        name: String = "Netflix",
        packageName: String = "com.netflix.ninja",
        deepLinkTemplate: String = "https://www.netflix.com/title/{tmdb_id}"
    ) = StreamingService(id = id, name = name, packageName = packageName, deepLinkTemplate = deepLinkTemplate)
}
