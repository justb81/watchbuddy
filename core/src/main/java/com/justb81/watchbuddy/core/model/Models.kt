package com.justb81.watchbuddy.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Trakt Models ─────────────────────────────────────────────────────────────

@Serializable
data class TraktShow(
    val title: String,
    val year: Int? = null,
    val ids: TraktIds,
    /** Episode runtime in minutes. Populated when fetched with extended=full; null otherwise. */
    val runtime: Int? = null,
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
    val avatarSource: AvatarSource = AvatarSource.TRAKT,
    /**
     * Session key of the most recently resolved ambiguous prompt, or null when no prompt
     * has been resolved since service start. The TV reads this to stop re-dispatching the
     * same ambiguous prompt (#474). Null-safe: old phone builds without this field default
     * it to null via WatchBuddyJson's lenient decoding.
     */
    val lastResolvedSessionKey: String? = null,
    /**
     * Trakt ID of the show the user selected for [lastResolvedSessionKey], or null
     * when no prompt has been resolved. Used by the TV to record evidence hints in
     * [TvShowCache] so the same metadata shape short-circuits Phase 1 next time.
     */
    val lastResolvedTraktId: Int? = null,
)

enum class LlmBackend { AICORE, LITERT, NONE }

@Serializable
enum class AvatarSource { TRAKT, GENERATED, CUSTOM }

// ── Scrobble / Session ────────────────────────────────────────────────────────

/**
 * Fast-changing playback state sampled once per poll tick, kept separate from
 * [MediaMetadataSnapshot] which is invariant for the duration of an episode.
 *
 * [state] mirrors Android's `PlaybackState.STATE_*` integer constants.
 * [positionMs] and [durationMs] are -1 when the streaming app does not report them.
 */
@Serializable
data class PlaybackTick(
    val state: Int,
    val positionMs: Long,
    val durationMs: Long,
    val capturedAtMs: Long,
) {
    val progress: Float
        get() = if (durationMs > 0 && positionMs >= 0) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

    val isPlaying: Boolean get() = state == STATE_PLAYING
    val isStopped: Boolean get() = state == STATE_STOPPED || state == STATE_NONE

    companion object {
        const val STATE_NONE = 0
        const val STATE_STOPPED = 1
        const val STATE_PAUSED = 2
        const val STATE_PLAYING = 3
        val UNKNOWN = PlaybackTick(state = -1, positionMs = -1L, durationMs = -1L, capturedAtMs = 0L)
    }
}

@Serializable
data class ScrobbleCandidate(
    val packageName: String,
    val mediaTitle: String,
    val confidence: Float,              // 0.0–1.0
    val matchedShow: TraktShow? = null,
    val matchedEpisode: TraktEpisode? = null
) {
    /** True when the cascade matched via TMDB but the show has no Trakt ID — not yet in the user's library. */
    fun isUnknownShow(): Boolean = matchedShow?.ids?.trakt == null && matchedShow?.ids?.tmdb != null
}

/**
 * One stable string of evidence per session tick, plus the package name.
 *
 * [text] is a newline-joined sequence of `"<sourceTag>: <value>"` lines written
 * in priority order by [com.justb81.watchbuddy.core.scrobbler.MediaSnapshotBuilder].
 * For the MediaSession-only case the tag prefix is `mediaSession.*`. Additional
 * enrichers (#471, #472) append their own prefixed lines without changing the schema.
 *
 * Wire-format note: [text] defaults to an empty string so that a TV client built
 * against the old schema (which sent 8 named fields instead of [text]) is
 * deserialized gracefully by a new phone — [WatchBuddyJson] ignores unknown keys,
 * and an empty [text] naturally yields confidence 0 from the extraction cascade.
 *
 * [sources] records which enrichers contributed; used for diagnostics only.
 */
@Serializable
data class MediaMetadataSnapshot(
    val packageName: String,
    val text: String = "",
    val sources: Set<String> = emptySet(),
)

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

/** Request body for `POST /shows/add-to-library`. Shared between TV (client) and phone (server). */
@Serializable
data class PhoneAddToLibraryRequest(
    val show: TraktShow,
    val episode: TraktEpisode,
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

// ── Ambiguous Scrobble Prompt ─────────────────────────────────────────────────

/**
 * One candidate show suggested to the user in an ambiguous-scrobble prompt.
 * [sourceLabel] is "library", "tmdb", or "llm-hint" for UI grouping / diagnostics.
 * [score] is the runtimeAffinity-weighted fuzzy score (0.40–0.69 for prompt candidates).
 */
@Serializable
data class AmbiguousCandidate(
    val show: TraktShow,
    val episode: TraktEpisode? = null,
    val score: Float,
    val sourceLabel: String,
)

/**
 * Emitted by [MediaSessionScrobbler] when Phase 1/2/3 all fail to clear the
 * [OVERLAY_THRESHOLD] but at least one candidate scores ≥ [AMBIGUOUS_THRESHOLD].
 *
 * The TV fans this to every connected phone via `POST /scrobble/prompt`. The phone
 * presents a notification and/or in-app card so the user can pick the correct show.
 *
 * [sessionKey] identifies the MediaSession that triggered this prompt; it is used
 * for dedup (the TV won't re-dispatch the same session) and for clearing the prompt
 * after resolution (the phone reports back via [DeviceCapability.lastResolvedSessionKey]).
 */
@Serializable
data class AmbiguousScrobbleEvent(
    val sessionKey: String,
    val packageName: String,
    /** Top-3 candidates sorted DESC by score. */
    val candidates: List<AmbiguousCandidate>,
    val tick: PlaybackTick,
    val capturedAtMs: Long,
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
 *
 * Deep links are resolved at runtime by JustWatch (see `JustWatchDeepLinkRepository`),
 * not from a static template.
 */
data class ResolvedProvider(
    val providerId: Int,
    val name: String,
    val logoPath: String?,
    val packageName: String?,
    val isInstalled: Boolean,
    val isLastUsed: Boolean,
    val tmdbPageUrl: String?,
)
