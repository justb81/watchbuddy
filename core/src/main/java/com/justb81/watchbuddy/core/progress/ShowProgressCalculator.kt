package com.justb81.watchbuddy.core.progress

import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TmdbSeasonSummary
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
        val lastAiredSeason = hint.lastAired?.season_number
        val lastAiredEpisode = hint.lastAired?.episode_number
        val lastAiredLabel = hint.lastAired?.let { formatLabel(it.season_number, it.episode_number) }
        val lastAiredInstant = parseDate(hint.lastAired?.air_date, zone)

        val watchedOrdinal = absoluteOrdinal(latest.season, latest.episode, hint.seasons)
        val airedOrdinal = if (lastAiredSeason != null && lastAiredEpisode != null) {
            absoluteOrdinal(lastAiredSeason, lastAiredEpisode, hint.seasons)
        } else 0
        val behind = (airedOrdinal - watchedOrdinal).coerceAtLeast(0)

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

    private data class WatchedRef(val season: Int, val episode: Int, val instant: Instant) {
        val label: String get() = formatLabel(season, episode)
    }

    private fun latestWatched(entry: TraktWatchedEntry): WatchedRef? {
        var best: WatchedRef? = null
        for (season in entry.seasons) {
            if (season.number <= 0) continue
            for (ep in season.episodes) {
                val ts = parseInstant(ep.last_watched_at) ?: continue
                if (best == null || ts.isAfter(best.instant)) {
                    best = WatchedRef(season.number, ep.number, ts)
                }
            }
        }
        return best
    }

    private fun absoluteOrdinal(season: Int, episode: Int, seasons: List<TmdbSeasonSummary>): Int {
        if (season <= 0) return 0
        val priorSum = seasons
            .filter { it.season_number in 1 until season }
            .sumOf { it.episode_count }
        return priorSum + episode
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
