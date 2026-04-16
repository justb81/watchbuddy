package com.justb81.watchbuddy.core.model

import kotlinx.serialization.Serializable

// ── Trakt Models ─────────────────────────────────────────────────────────────

@Serializable
data class TraktShow(
    val title: String,
    val year: Int? = null,
    val ids: TraktIds
)

@Serializable
data class TraktIds(
    val trakt: Int? = null,
    val slug: String? = null,
    val tvdb: Int? = null,
    val imdb: String? = null,
    val tmdb: Int? = null
)

@Serializable
data class TraktEpisode(
    val season: Int,
    val number: Int,
    val title: String? = null,
    val ids: TraktIds = TraktIds()
)

@Serializable
data class TraktWatchedEntry(
    val show: TraktShow,
    val seasons: List<TraktWatchedSeason> = emptyList()
)

@Serializable
data class TraktWatchedSeason(
    val number: Int,
    val episodes: List<TraktWatchedEpisode> = emptyList()
)

@Serializable
data class TraktWatchedEpisode(
    val number: Int,
    val plays: Int = 1,
    val last_watched_at: String? = null
)

// ── TMDB Models ───────────────────────────────────────────────────────────────

@Serializable
data class TmdbShow(
    val id: Int,
    val name: String,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null
)

@Serializable
data class TmdbEpisode(
    val id: Int,
    val name: String,
    val overview: String? = null,
    val still_path: String? = null,
    val season_number: Int,
    val episode_number: Int,
    val air_date: String? = null
)

// ── Companion / Device Models ─────────────────────────────────────────────────

@Serializable
data class DeviceCapability(
    val deviceId: String,
    val userName: String,
    val userAvatarUrl: String? = null,
    val deviceName: String,
    val llmBackend: LlmBackend,         // AICORE, MEDIAPIPE_GPU, MEDIAPIPE_CPU, NONE
    val modelQuality: Int,              // 0–150 (see scoring docs)
    val freeRamMb: Int,
    val isAvailable: Boolean = true
)

enum class LlmBackend { AICORE, MEDIAPIPE_GPU, MEDIAPIPE_CPU, NONE }

// ── Scrobble / Session ────────────────────────────────────────────────────────

@Serializable
data class ScrobbleCandidate(
    val packageName: String,
    val mediaTitle: String,
    val confidence: Float,              // 0.0–1.0
    val matchedShow: TraktShow? = null,
    val matchedEpisode: TraktEpisode? = null
)

// ── Scrobble Display ─────────────────────────────────────────────────────────

@Serializable
data class ScrobbleDisplayEvent(
    val show: TraktShow,
    val episode: TraktEpisode,
    val action: String,
    val timestamp: Long
)

// ── Streaming Deep Links ──────────────────────────────────────────────────────

@Serializable
data class StreamingService(
    val id: String,
    val name: String,
    val packageName: String,
    val deepLinkTemplate: String        // e.g. "https://www.netflix.com/title/{tmdb_id}"
)

val KNOWN_STREAMING_SERVICES = listOf(
    StreamingService("netflix",   "Netflix",      "com.netflix.ninja",                    "https://www.netflix.com/title/{tmdb_id}"),
    StreamingService("prime",     "Prime Video",  "com.amazon.amazonvideo.livingroom",     "https://app.primevideo.com/detail?asin={asin}"),
    StreamingService("disney",    "Disney+",      "com.disney.disneyplus",                "https://www.disneyplus.com/series/{slug}/{tmdb_id}"),
    StreamingService("waipu",     "WaipuTV",      "tv.waipu.app",                         "waipu://tv"),
    StreamingService("joyn",      "Joyn",         "de.prosiebensat1digital.android.joyn", "https://www.joyn.de/serien/{slug}"),
    StreamingService("ard",       "ARD Mediathek","de.swr.avp.ard.phone",                 "https://www.ardmediathek.de/video/{id}"),
    StreamingService("zdf",       "ZDF Mediathek","de.zdf.android.app",                   "https://www.zdf.de/serien/{slug}")
)
