package com.justb81.watchbuddy.core.scrobbler

import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
import com.justb81.watchbuddy.core.model.TitleExtractionResponse
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry

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
