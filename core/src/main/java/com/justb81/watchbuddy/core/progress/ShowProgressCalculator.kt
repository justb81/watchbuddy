package com.justb81.watchbuddy.core.progress

import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Per-show progress summary used by both the phone HomeScreen and the TV HomeScreen.
 *
 * The calculator is pure Kotlin — it lives in `core/` so the TV can reuse it without
 * pulling in Android-only dependencies, and so it can be unit-tested on the JVM.
 */
sealed class ShowProgress {

    /** User has not watched any non-special episode yet. */
    data class NotStarted(
        val nextAired: Instant?,
        val nextAiredLabel: String?
    ) : ShowProgress()

    /**
     * User watched at least one episode and at least one newer episode has aired.
     * [episodesBehind] is clamped to ≥ 0 for cases where Trakt is fresher than the
     * cached TMDB data.
     */
    data class InProgress(
        val latestWatched: Instant,
        val latestWatchedLabel: String,
        val lastAired: Instant,
        val lastAiredLabel: String,
        val nextAired: Instant?,
        val nextAiredLabel: String?,
        val episodesBehind: Int
    ) : ShowProgress()

    /** User is up-to-date and a new episode is scheduled. */
    data class CaughtUpAiring(
        val latestWatched: Instant?,
        val latestWatchedLabel: String?,
        val nextAired: Instant,
        val nextAiredLabel: String
    ) : ShowProgress()

    /** User is up-to-date and the show has ended. */
    data class CaughtUpEnded(
        val latestWatched: Instant?,
        val latestWatchedLabel: String?
    ) : ShowProgress()

    /** No TMDB hint available — we only know what Trakt told us. */
    data class Unknown(
        val latestWatched: Instant?,
        val latestWatchedLabel: String?
    ) : ShowProgress()
}

object ShowProgressCalculator {

    private const val STATUS_ENDED = "Ended"
    private const val STATUS_CANCELED = "Canceled"

    /**
     * Returns the (season, episode) pair of the next episode the user should watch.
     *
     * Priority:
     * 1. [TmdbProgressHint.nextAired] — TMDB's globally scheduled next episode (precise).
     * 2. Highest watched regular episode + 1 (naive; does not cross season boundaries).
     * 3. S01E01 when no episodes are watched yet.
     *
     * Returns null when [entry] has no TMDB show ID (callers should skip the fetch).
     */
    fun nextEpisodeNumbers(entry: TraktWatchedEntry, hint: TmdbProgressHint?): Pair<Int, Int>? {
        hint?.nextAired?.let { next ->
            if (isRegularSeason(next.season_number)) return next.season_number to next.episode_number
        }
        val latest = latestWatched(entry)
        return if (latest == null) 1 to 1 else latest.season to (latest.episode + 1)
    }

    fun compute(
        entry: TraktWatchedEntry,
        hint: TmdbProgressHint?,
        zone: ZoneId = ZoneId.systemDefault()
    ): ShowProgress {
        val latest = latestWatched(entry)
        val latestInstant = latest?.instant
        val latestLabel = latest?.label

        if (hint == null) {
            return ShowProgress.Unknown(latestInstant, latestLabel)
        }

        val nextAiredInstant = parseDate(hint.nextAired?.air_date, zone)
        val nextAiredLabel = hint.nextAired?.let { formatLabel(it.season_number, it.episode_number) }

        if (latest == null) {
            return ShowProgress.NotStarted(nextAiredInstant, nextAiredLabel)
        }

        val ended = hint.status == STATUS_ENDED || hint.status == STATUS_CANCELED
        val lastAiredLabel = hint.lastAired?.let { formatLabel(it.season_number, it.episode_number) }
        val lastAiredInstant = parseDate(hint.lastAired?.air_date, zone)

        val behind = episodesBehind(entry, hint)

        return when {
            behind > 0 -> ShowProgress.InProgress(
                latestWatched = latestInstant!!,
                latestWatchedLabel = latestLabel!!,
                lastAired = lastAiredInstant ?: latestInstant,
                lastAiredLabel = lastAiredLabel ?: latestLabel,
                nextAired = nextAiredInstant,
                nextAiredLabel = nextAiredLabel,
                episodesBehind = behind
            )
            ended -> ShowProgress.CaughtUpEnded(latestInstant, latestLabel)
            nextAiredInstant != null && nextAiredLabel != null -> ShowProgress.CaughtUpAiring(
                latestWatched = latestInstant,
                latestWatchedLabel = latestLabel,
                nextAired = nextAiredInstant,
                nextAiredLabel = nextAiredLabel
            )
            else -> ShowProgress.CaughtUpEnded(latestInstant, latestLabel)
        }
    }

    /** Timestamp of the most recently watched non-special episode, or null if none. */
    fun latestWatchedInstant(entry: TraktWatchedEntry): Instant? =
        latestWatched(entry)?.instant

    fun isCompleted(entry: TraktWatchedEntry, hint: TmdbProgressHint?): Boolean {
        if (hint == null) return false
        val airedRegular = hint.seasons
            .filter { isRegularSeason(it.season_number) }
            .sumOf { it.episode_count }
        if (airedRegular == 0) return false
        return episodesBehind(entry, hint) == 0
    }

    /**
     * Returns true when the next unwatched regular episode is S(n+1)E01 — i.e. the user
     * finished their last-watched season and a new season has already started airing.
     *
     * False when: no TMDB hint, not started, mid-season, season length unknown, or the
     * new season has not aired yet. Specials (season 0) are never considered.
     */
    fun hasNewSeasonAvailable(entry: TraktWatchedEntry, hint: TmdbProgressHint?): Boolean {
        val lastAired = hint?.lastAired ?: return false
        val watched = latestWatched(entry) ?: return false
        val lastSeasonEpisodes = hint.seasons
            .filter { isRegularSeason(it.season_number) }
            .find { it.season_number == watched.season }
            ?.episode_count
            ?.takeIf { it > 0 }
            ?: return false
        return watched.episode >= lastSeasonEpisodes &&
            isRegularSeason(lastAired.season_number) &&
            lastAired.season_number > watched.season
    }

    private fun episodesBehind(entry: TraktWatchedEntry, hint: TmdbProgressHint): Int {
        val airedRegular = hint.seasons
            .filter { isRegularSeason(it.season_number) }
            .sumOf { it.episode_count }
        val watchedRegular = entry.seasons
            .filter { isRegularSeason(it.number) }
            .sumOf { it.episodes.size }
        return (airedRegular - watchedRegular).coerceAtLeast(0)
    }

    private data class WatchedRef(val season: Int, val episode: Int, val instant: Instant) {
        val label: String get() = formatLabel(season, episode)
    }

    private fun latestWatched(entry: TraktWatchedEntry): WatchedRef? {
        var best: WatchedRef? = null
        for (season in entry.seasons) {
            if (!isRegularSeason(season.number)) continue
            for (ep in season.episodes) {
                val ts = parseInstant(ep.last_watched_at) ?: continue
                if (best == null ||
                    season.number > best.season ||
                    (season.number == best.season && ep.number > best.episode)
                ) {
                    best = WatchedRef(season.number, ep.number, ts)
                }
            }
        }
        return best
    }

    private fun parseInstant(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseDate(raw: String?, zone: ZoneId): Instant? {
        if (raw.isNullOrBlank()) return null
        return try {
            LocalDate.parse(raw).atStartOfDay(zone).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

internal fun formatLabel(season: Int, episode: Int): String =
    "S%02dE%02d".format(season, episode)

/**
 * Shared predicate for "is this a regular (non-special) season?" — specials
 * live in season 0 (and never a negative number) and must be excluded from
 * all progress arithmetic: `latestWatched`, episode counts, and the
 * "episodes behind" delta.
 */
internal fun isRegularSeason(seasonNumber: Int): Boolean = seasonNumber >= 1
