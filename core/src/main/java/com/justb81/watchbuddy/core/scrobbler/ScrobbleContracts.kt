package com.justb81.watchbuddy.core.scrobbler

import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
import com.justb81.watchbuddy.core.model.TitleExtractionResponse
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import kotlinx.serialization.Serializable

/**
 * Provides the user's watched show list for fuzzy title matching and the TMDB API key
 * for the TMDB search fallback. Implemented per-app: the TV reads from in-memory cache
 * populated by connected phones; the phone reads from its own ShowRepository.
 */
interface WatchedShowSource {
    suspend fun getCachedShows(): List<TraktWatchedEntry>
    suspend fun getTmdbApiKey(): String?

    /**
     * Returns the cached TMDB progress hint for a show, used by the scrobbler to guess
     * the next unwatched episode when the playing app's MediaMetadata title lacks an
     * explicit `S##E##` marker (issue #401). Returns null when no hint is cached.
     */
    suspend fun getShowHint(ids: TraktIds): TmdbProgressHint? = null
}

/**
 * Sends scrobble events to the appropriate destination. The TV fans out to each
 * connected phone's HTTP API; the phone calls the Trakt API directly with its own token.
 */
interface ScrobbleDispatcher {
    suspend fun dispatchStart(show: TraktShow, episode: TraktEpisode, progress: Float)
    suspend fun dispatchPause(show: TraktShow, episode: TraktEpisode, progress: Float)
    suspend fun dispatchStop(show: TraktShow, episode: TraktEpisode, progress: Float)
}

/**
 * Last-resort fallback for [MediaSessionScrobbler.matchSnapshot]: when the
 * cache / multi-field regex path cannot resolve a title to a show (confidence
 * below the overlay threshold), the scrobbler consults a [TitleExtractor]
 * for a normalized `(showTitle, season?, episode?)` triple and re-runs the
 * deterministic cascade with it.
 *
 * The TV implementation forwards the snapshot to the best-scoring phone's
 * `POST /scrobble/extract`, which invokes the local LLM. The phone app binds
 * [NoOpTitleExtractor] — the scrobbler there already has the Trakt library
 * in-process so richer matching is unnecessary.
 */
interface TitleExtractor {
    suspend fun extract(snapshot: MediaMetadataSnapshot): TitleExtractionResponse?
}

/** No-op implementation wired on the phone app and in tests that don't exercise the LLM path. */
object NoOpTitleExtractor : TitleExtractor {
    override suspend fun extract(snapshot: MediaMetadataSnapshot): TitleExtractionResponse? = null
}

/**
 * Captures the user's Watch-Now intent when they tap a streaming-provider chip on the TV
 * ShowDetailScreen. Stored TV-local by [PlaybackIntentProvider]; consulted by
 * [MediaSessionScrobbler.matchSnapshot] as the Phase 0 gate.
 */
@Serializable
data class PlaybackIntent(
    val showIds: TraktIds,
    /** Display title used for snapshot text-scoring in Phase 0. */
    val showTitle: String,
    val season: Int,
    val episode: Int,
    /** Package name of the streaming app that was launched. */
    val providerPackageName: String,
    /** Wall-clock milliseconds at capture time, for TTL eviction. */
    val capturedAtMs: Long,
)

/** Counters for Watch-Now intent outcomes, surfaced in TV Diagnostics. */
data class PlaybackIntentStats(
    /** Phase 0 confirmed the intent and auto-scrobbled. */
    val hits: Int,
    /** Phase 0 had an intent but text score < 0.40; intent surfaced as a Top-3 candidate. */
    val fallthroughs: Int,
    /** User manually marked a different episode while an intent was active (channel-surfing signal). */
    val overriddenByManualMark: Int,
)

/**
 * Abstracts the Watch-Now intent store so [MediaSessionScrobbler] (core) stays decoupled
 * from the TV-specific [PlaybackIntentRegistry].
 *
 * The phone app binds [NoOpPlaybackIntentProvider]; the TV app binds [PlaybackIntentRegistry].
 */
interface PlaybackIntentProvider {
    /** Records a new intent, replacing any existing intent for the same package. */
    fun record(intent: PlaybackIntent)
    /**
     * Returns the stored intent for [packageName] if it is still within the TTL window,
     * or null if none exists or the intent has expired (and evicts it).
     */
    fun peek(packageName: String): PlaybackIntent?
    /** Removes the intent for [packageName] (called after a Phase-0 auto-scrobble). */
    fun consumeIntent(packageName: String)
    /** Increments the Phase-0-confirmed counter. */
    fun recordHit()
    /** Increments the Phase-0-fallthrough counter. */
    fun recordFallthrough()
    /** Returns a snapshot of all intent-lifecycle counters. */
    fun intentStats(): PlaybackIntentStats
}

/** No-op implementation used by the phone app and in tests that don't exercise the intent path. */
class NoOpPlaybackIntentProvider : PlaybackIntentProvider {
    override fun record(intent: PlaybackIntent) {}
    override fun peek(packageName: String): PlaybackIntent? = null
    override fun consumeIntent(packageName: String) {}
    override fun recordHit() {}
    override fun recordFallthrough() {}
    override fun intentStats(): PlaybackIntentStats = PlaybackIntentStats(0, 0, 0)
}
