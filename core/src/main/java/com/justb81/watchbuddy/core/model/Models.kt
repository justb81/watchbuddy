package com.justb81.watchbuddy.core.model

import kotlinx.serialization.SerialName
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

@Serializable
data class TraktSeasonWithEpisodes(
    val number: Int,
    val episodes: List<TraktEpisode> = emptyList()
)

// ── TMDB Models ───────────────────────────────────────────────────────────────

@Serializable
data class TmdbShow(
    val id: Int,
    val name: String,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null,
    val status: String? = null,
    val number_of_episodes: Int? = null,
    val last_episode_to_air: TmdbEpisodeSummary? = null,
    val next_episode_to_air: TmdbEpisodeSummary? = null,
    val seasons: List<TmdbSeasonSummary> = emptyList()
)

@Serializable
data class TmdbTvSearchResponse(
    val results: List<TmdbShow> = emptyList()
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

@Serializable
data class TmdbEpisodeSummary(
    val season_number: Int,
    val episode_number: Int,
    val name: String? = null,
    val air_date: String? = null
)

@Serializable
data class TmdbSeasonSummary(
    val season_number: Int,
    val episode_count: Int
)

/**
 * Wire-slim subset of TMDB per-show progress information shipped over the companion
 * HTTP API as part of [EnrichedShowEntry]. Keeps /shows payload small while giving the
 * TV all it needs to render the same progress visualisation as the phone.
 */
@Serializable
data class TmdbProgressHint(
    val status: String? = null,
    val lastAired: TmdbEpisodeSummary? = null,
    val nextAired: TmdbEpisodeSummary? = null,
    val seasons: List<TmdbSeasonSummary> = emptyList()
)

@Serializable
data class EnrichedShowEntry(
    val entry: TraktWatchedEntry,
    val tmdb: TmdbProgressHint? = null,
    val posterPath: String? = null
)

// ── Companion / Device Models ─────────────────────────────────────────────────

@Serializable
data class DeviceCapability(
    val deviceId: String,
    val userName: String,
    val userAvatarUrl: String? = null,
    val deviceName: String,
    val llmBackend: LlmBackend,         // AICORE, LITERT, NONE
    val modelQuality: Int,              // 0–150 (see scoring docs)
    val freeRamMb: Int,
    val isAvailable: Boolean = true,
    val tmdbConfigured: Boolean = false,
    val tmdbApiKey: String? = null,     // populated by phone so TV can call TMDB directly
    /**
     * Where the phone wants the TV to source this user's avatar from.
     * TRAKT (default) → render [userAvatarUrl] directly (Trakt CDN).
     * GENERATED → TV renders deterministic initials from [userName]; [userAvatarUrl] is null.
     * CUSTOM → [userAvatarUrl] points at this phone's `/avatar?v=N` endpoint.
     */
    val avatarSource: AvatarSource = AvatarSource.TRAKT
)

enum class LlmBackend { AICORE, LITERT, NONE }

@Serializable
enum class AvatarSource { TRAKT, GENERATED, CUSTOM }

// ── Scrobble / Session ────────────────────────────────────────────────────────

@Serializable
data class ScrobbleCandidate(
    val packageName: String,
    val mediaTitle: String,
    val confidence: Float,              // 0.0–1.0
    val matchedShow: TraktShow? = null,
    val matchedEpisode: TraktEpisode? = null
)

/**
 * Structured view of the MediaMetadata fields a streaming app publishes for the
 * currently-playing session. Streaming apps distribute signal across several
 * fields (Plex puts the show in ALBUM_ARTIST, Jellyfin in ALBUM, some Netflix
 * skins use DISPLAY_SUBTITLE for SxxExx) so the scrobbler tries them all before
 * falling back to the LLM extractor.
 */
@Serializable
data class MediaMetadataSnapshot(
    val packageName: String,
    val title: String? = null,
    val displayTitle: String? = null,
    val displaySubtitle: String? = null,
    val displayDescription: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val subtitle: String? = null,
) {
    /**
     * Candidate strings to try for SxxExx parsing + fuzzy matching, ordered by
     * how likely they are to hold the show title: `ALBUM_ARTIST` > `ALBUM` >
     * `ARTIST` > `DISPLAY_TITLE` > `DISPLAY_SUBTITLE` > `TITLE` > `SUBTITLE` >
     * `DISPLAY_DESCRIPTION`. Empty / blank values are filtered; duplicates are
     * collapsed so the fuzzy cascade doesn't redo the same scoring twice.
     */
    fun candidateStrings(): List<String> =
        listOfNotNull(albumArtist, album, artist, displayTitle, displaySubtitle, title, subtitle, displayDescription)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
}

/**
 * Minimal show reference shipped to the LLM extractor so it can prefer a library
 * match when the raw MediaSession values match a show the user already watches.
 * Kept tiny (~40 bytes per entry) so the prompt stays under a few KB even with
 * 50+ shows.
 */
@Serializable
data class LibraryHint(
    val traktId: Int? = null,
    val tmdbId: Int? = null,
    val title: String,
    val year: Int? = null,
)

/**
 * Request body for `POST /scrobble/extract`. The TV ships every MediaMetadata
 * field it has + a top-N library hint list; the phone returns normalized
 * `(showTitle, season?, episode?)`.
 */
@Serializable
data class TitleExtractionRequest(
    val snapshot: MediaMetadataSnapshot,
    val libraryHints: List<LibraryHint> = emptyList(),
)

/**
 * Response from the phone's LLM title extractor. The TV never trusts it blindly
 * — it re-runs the existing cache/TMDB match cascade with the normalized
 * [showTitle] instead. Fields are optional because a) the LLM may fail, b) the
 * extractor is a *hint provider*, not an authority.
 */
@Serializable
data class TitleExtractionResponse(
    val showTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** Populated when the LLM picked a library hint. Validated server-side. */
    val libraryTraktId: Int? = null,
    /** Self-reported, clamped to `[0.0, 1.0]` server-side. */
    val confidence: Float = 0f,
)

// ── Scrobble Display ─────────────────────────────────────────────────────────

@Serializable
enum class ScrobbleAction { START, PAUSE, STOP }

@Serializable
data class ScrobbleDisplayEvent(
    val action: ScrobbleAction,
    val show: TraktShow,
    val episode: TraktEpisode,
    val progress: Float,
    val timestamp: Long
)

// ── TMDB Watch Providers ──────────────────────────────────────────────────────

@Serializable
data class WatchProviderEntry(
    @SerialName("provider_id") val providerId: Int,
    @SerialName("provider_name") val providerName: String,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("display_priority") val displayPriority: Int = 0,
)

@Serializable
data class WatchProviderResult(
    val link: String? = null,
    val flatrate: List<WatchProviderEntry> = emptyList(),
    val rent: List<WatchProviderEntry> = emptyList(),
    val buy: List<WatchProviderEntry> = emptyList(),
    val ads: List<WatchProviderEntry> = emptyList(),
    val free: List<WatchProviderEntry> = emptyList(),
)

@Serializable
data class WatchProviderResponse(
    val id: Int,
    val results: Map<String, WatchProviderResult> = emptyMap(),
)

/**
 * A fully resolved streaming provider for a show, ready for the UI.
 * Combines TMDB provider data with the [ProviderCatalog] mapping and
 * TV-local state (installed app, last-used tracking).
 */
data class ResolvedProvider(
    val providerId: Int,
    val name: String,
    val logoPath: String?,
    val packageName: String?,
    val deepLinkTemplate: String?,
    val isInstalled: Boolean,
    val isLastUsed: Boolean,
    val tmdbPageUrl: String?,
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
    StreamingService("prime",     "Prime Video",  "com.amazon.amazonvideo.livingroom",     "https://www.primevideo.com/search?phrase={slug}"),
    StreamingService("disney",    "Disney+",      "com.disney.disneyplus",                "https://www.disneyplus.com/series/{slug}/{tmdb_id}"),
    StreamingService("waipu",     "WaipuTV",      "tv.waipu.app",                         "waipu://tv"),
    StreamingService("joyn",      "Joyn",         "de.prosiebensat1digital.android.joyn", "https://www.joyn.de/serien/{slug}"),
    StreamingService("ard",       "ARD Mediathek","de.swr.avp.ard.phone",                 "https://www.ardmediathek.de/video/{id}"),
    StreamingService("zdf",       "ZDF Mediathek","de.zdf.android.app",                   "https://www.zdf.de/serien/{slug}")
)
