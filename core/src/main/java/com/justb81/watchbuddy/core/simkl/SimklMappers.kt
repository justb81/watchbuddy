package com.justb81.watchbuddy.core.simkl

import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import com.justb81.watchbuddy.core.trakt.TraktSearchResult

/**
 * Pure mapping functions from SIMKL wire shapes to [Trakt*] model shapes.
 *
 * By adapting SIMKL responses into the existing [Trakt*] models here, nothing
 * downstream (TV HTTP contract, progress calculator, UI) requires changes when
 * the active backend is SIMKL. These functions are Android-free and fully
 * unit-testable.
 */

/** Maps [SimklIds] to [TraktIds]. [TraktIds.trakt] is always null for SIMKL items. */
fun SimklIds.toTraktIds(): TraktIds = TraktIds(
    trakt = null,
    slug = slug,
    tvdb = tvdb,
    imdb = imdb,
    tmdb = tmdb,
    simkl = canonicalSimklId
)

/**
 * Maps a [SimklShowItem] (watched or watchlist entry) to a [TraktWatchedEntry].
 *
 * - Watched shows: seasons and episodes are mapped directly.
 * - Watchlist-only items (status = "plantowatch", empty seasons): `seasons = emptyList()`
 *   mirrors the existing Trakt watchlist-only path.
 */
fun SimklShowItem.toTraktWatchedEntry(): TraktWatchedEntry = TraktWatchedEntry(
    show = TraktShow(
        title = show.title,
        year = show.year,
        ids = show.ids.toTraktIds()
    ),
    seasons = seasons.map { season ->
        TraktWatchedSeason(
            number = season.number,
            episodes = season.episodes.map { ep ->
                TraktWatchedEpisode(
                    number = ep.number,
                    plays = 1,
                    last_watched_at = ep.watchedAt
                )
            }
        )
    }
)

/**
 * Maps a [SimklShowItem] to a list of [TraktSeasonWithEpisodes].
 *
 * Used for the `/shows/{id}/seasons` endpoint: SIMKL returns seasons+episodes
 * in the all-items response, which we reuse to answer structural queries.
 */
fun SimklShowItem.toTraktSeasonsWithEpisodes(): List<TraktSeasonWithEpisodes> =
    seasons.map { season ->
        TraktSeasonWithEpisodes(
            number = season.number,
            episodes = season.episodes.map { ep ->
                TraktEpisode(
                    season = season.number,
                    number = ep.number
                )
            }
        )
    }

/**
 * Maps a [SimklSearchResult] to a [TraktSearchResult].
 *
 * Returns null when the result has no usable ids or title.
 */
fun SimklSearchResult.toTraktSearchResult(): TraktSearchResult? {
    val simklIds = ids ?: return null
    val showTitle = title ?: return null
    return TraktSearchResult(
        type = "show",
        score = scores?.best,
        show = TraktShow(
            title = showTitle,
            year = year,
            ids = simklIds.toTraktIds()
        )
    )
}
