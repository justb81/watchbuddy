package com.justb81.watchbuddy.core

import com.justb81.watchbuddy.core.model.*

object TestFixtures {

    fun traktIds(
        trakt: Int? = 1,
        slug: String? = "test-show",
        tvdb: Int? = null,
        imdb: String? = null,
        tmdb: Int? = 100
    ) = TraktIds(trakt = trakt, slug = slug, tvdb = tvdb, imdb = imdb, tmdb = tmdb)

    fun traktShow(
        title: String = "Test Show",
        year: Int? = 2024,
        ids: TraktIds = traktIds()
    ) = TraktShow(title = title, year = year, ids = ids)

    fun traktEpisode(
        season: Int = 1,
        number: Int = 1,
        title: String? = "Pilot",
        ids: TraktIds = TraktIds()
    ) = TraktEpisode(season = season, number = number, title = title, ids = ids)

    fun traktWatchedEpisode(
        number: Int = 1,
        plays: Int = 1,
        lastWatchedAt: String? = "2024-01-01T12:00:00.000Z"
    ) = TraktWatchedEpisode(number = number, plays = plays, last_watched_at = lastWatchedAt)

    fun traktWatchedSeason(
        number: Int = 1,
        episodes: List<TraktWatchedEpisode> = listOf(traktWatchedEpisode())
    ) = TraktWatchedSeason(number = number, episodes = episodes)

    fun traktWatchedEntry(
        show: TraktShow = traktShow(),
        seasons: List<TraktWatchedSeason> = listOf(traktWatchedSeason())
    ) = TraktWatchedEntry(show = show, seasons = seasons)

    fun tmdbShow(
        id: Int = 100,
        name: String = "Test Show",
        overview: String? = "A test show overview.",
        posterPath: String? = "/poster.jpg",
        backdropPath: String? = "/backdrop.jpg",
        firstAirDate: String? = "2024-01-15"
    ) = TmdbShow(
        id = id, name = name, overview = overview,
        poster_path = posterPath, backdrop_path = backdropPath,
        first_air_date = firstAirDate
    )

    fun tmdbEpisode(
        id: Int = 1,
        name: String = "Pilot",
        overview: String? = "The first episode.",
        stillPath: String? = "/still.jpg",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        airDate: String? = "2024-01-15"
    ) = TmdbEpisode(
        id = id, name = name, overview = overview,
        still_path = stillPath, season_number = seasonNumber,
        episode_number = episodeNumber, air_date = airDate
    )

    fun deviceCapability(
        deviceId: String = "device-1",
        userName: String = "testuser",
        userAvatarUrl: String? = null,
        deviceName: String = "Pixel 8",
        llmBackend: LlmBackend = LlmBackend.LITERT,
        modelQuality: Int = 75,
        freeRamMb: Int = 4000,
        isAvailable: Boolean = true
    ) = DeviceCapability(
        deviceId = deviceId, userName = userName, userAvatarUrl = userAvatarUrl,
        deviceName = deviceName, llmBackend = llmBackend, modelQuality = modelQuality,
        freeRamMb = freeRamMb, isAvailable = isAvailable
    )

    fun scrobbleCandidate(
        packageName: String = "com.netflix.ninja",
        mediaTitle: String = "Breaking Bad S01E01",
        confidence: Float = 0.95f,
        matchedShow: TraktShow? = traktShow(title = "Breaking Bad"),
        matchedEpisode: TraktEpisode? = traktEpisode(season = 1, number = 1)
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
