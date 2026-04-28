package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of the user's Trakt watched shows.
 * Populated by [TvHomeViewModel] when shows are loaded from a phone.
 * Used by [MediaSessionScrobbler] for local fuzzy-matching before hitting the Trakt API,
 * and for the progress-hint fallback that guesses the next unwatched episode when the
 * streaming app's MediaMetadata title lacks an explicit `S##E##` marker (issue #401).
 *
 * Also stores evidence hints from resolved ambiguous-scrobble prompts (#474):
 * `recordEvidenceHint(sessionKey, traktId)` records a user's selection so the
 * scrobbler can look it up to skip re-prompting for the same session.
 */
@Singleton
class TvShowCache @Inject constructor() {

    @Volatile
    private var cachedShows: List<TraktWatchedEntry> = emptyList()

    @Volatile
    private var hintsByTraktId: Map<Int, TmdbProgressHint> = emptyMap()

    /** sessionKey → traktId: recorded when the user resolves an ambiguous-scrobble prompt. */
    private val evidenceHints: MutableMap<String, Int> = mutableMapOf()

    fun updateShows(shows: List<TraktWatchedEntry>) {
        cachedShows = shows
        hintsByTraktId = emptyMap()
    }

    fun updateEnrichedShows(shows: List<EnrichedShowEntry>) {
        cachedShows = shows.map { it.entry }
        hintsByTraktId = shows
            .mapNotNull { enriched ->
                val traktId = enriched.entry.show.ids.trakt ?: return@mapNotNull null
                val hint = enriched.tmdb ?: return@mapNotNull null
                traktId to hint
            }
            .toMap()
    }

    fun getCachedShows(): List<TraktWatchedEntry> = cachedShows

    fun getHint(ids: TraktIds): TmdbProgressHint? = ids.trakt?.let { hintsByTraktId[it] }

    /** Records that the user resolved [sessionKey] by choosing [traktId]. */
    fun recordEvidenceHint(sessionKey: String, traktId: Int) {
        evidenceHints[sessionKey] = traktId
    }

    /** Returns the Trakt ID the user chose for [sessionKey], or null if not recorded. */
    fun lookupEvidenceHint(sessionKey: String): Int? = evidenceHints[sessionKey]
}
