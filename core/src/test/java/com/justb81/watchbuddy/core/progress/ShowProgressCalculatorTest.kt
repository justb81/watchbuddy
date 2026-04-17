package com.justb81.watchbuddy.core.progress

import com.justb81.watchbuddy.core.model.TmdbEpisodeSummary
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TmdbSeasonSummary
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

@DisplayName("ShowProgressCalculator")
class ShowProgressCalculatorTest {

    private val utc = ZoneId.of("UTC")

    private fun entry(vararg episodes: Triple<Int, Int, String?>): TraktWatchedEntry {
        val grouped = episodes.groupBy { it.first }
        val seasons = grouped.map { (seasonNum, eps) ->
            TraktWatchedSeason(
                number = seasonNum,
                episodes = eps.map { TraktWatchedEpisode(number = it.second, last_watched_at = it.third) }
            )
        }
        return TraktWatchedEntry(
            show = com.justb81.watchbuddy.core.model.TraktShow(
                title = "Test Show",
                year = 2020,
                ids = com.justb81.watchbuddy.core.model.TraktIds()
            ),
            seasons = seasons
        )
    }

    private fun hint(
        status: String? = "Returning Series",
        lastAired: TmdbEpisodeSummary? = null,
        nextAired: TmdbEpisodeSummary? = null,
        seasons: List<TmdbSeasonSummary> = emptyList()
    ) = TmdbProgressHint(status, lastAired, nextAired, seasons)

    @Nested
    @DisplayName("no TMDB hint")
    inner class NoHint {
        @Test
        fun `returns Unknown with latestWatched when shows exist`() {
            val e = entry(Triple(1, 1, "2024-01-01T10:00:00Z"))
            val result = ShowProgressCalculator.compute(e, null)
            assertTrue(result is ShowProgress.Unknown)
            assertEquals(Instant.parse("2024-01-01T10:00:00Z"), (result as ShowProgress.Unknown).latestWatched)
            assertEquals("S01E01", result.latestWatchedLabel)
        }

        @Test
        fun `returns Unknown with null fields when nothing watched`() {
            val e = entry()
            val result = ShowProgressCalculator.compute(e, null)
            assertTrue(result is ShowProgress.Unknown)
            assertNull((result as ShowProgress.Unknown).latestWatched)
        }
    }

    @Nested
    @DisplayName("NotStarted")
    inner class NotStartedTest {
        @Test
        fun `no watched episodes and hint exists returns NotStarted`() {
            val e = entry()
            val h = hint(nextAired = TmdbEpisodeSummary(1, 1, air_date = "2025-06-01"))
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.NotStarted)
            val ns = result as ShowProgress.NotStarted
            assertEquals("S01E01", ns.nextAiredLabel)
            assertEquals(Instant.parse("2025-06-01T00:00:00Z"), ns.nextAired)
        }

        @Test
        fun `only season 0 specials watched is still NotStarted`() {
            val e = entry(Triple(0, 1, "2024-01-01T10:00:00Z"))
            val h = hint(nextAired = TmdbEpisodeSummary(1, 1, air_date = "2025-06-01"))
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.NotStarted)
        }
    }

    @Nested
    @DisplayName("InProgress")
    inner class InProgressTest {
        @Test
        fun `watched S01E03 with S01E05 aired yields behind=2`() {
            val e = entry(
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-02T10:00:00Z"),
                Triple(1, 3, "2024-01-03T10:00:00Z")
            )
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(1, 5, air_date = "2024-02-01"),
                nextAired = TmdbEpisodeSummary(1, 6, air_date = "2024-02-08"),
                seasons = listOf(TmdbSeasonSummary(1, 10))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            val ip = result as ShowProgress.InProgress
            assertEquals(2, ip.episodesBehind)
            assertEquals("S01E03", ip.latestWatchedLabel)
            assertEquals("S01E05", ip.lastAiredLabel)
            assertEquals("S01E06", ip.nextAiredLabel)
        }

        @Test
        fun `multi-season gap math sums prior seasons`() {
            val e = entry(Triple(1, 10, "2024-01-01T10:00:00Z"))
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(3, 2, air_date = "2024-06-01"),
                seasons = listOf(
                    TmdbSeasonSummary(1, 10),
                    TmdbSeasonSummary(2, 8),
                    TmdbSeasonSummary(3, 10)
                )
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            // watched=10 (S1E10), aired=S3E2 => 10+8+2=20 → behind=10
            assertEquals(10, (result as ShowProgress.InProgress).episodesBehind)
        }

        @Test
        fun `picks latest timestamp not highest episode number`() {
            val e = entry(
                Triple(1, 5, "2024-01-05T10:00:00Z"),
                Triple(1, 3, "2024-02-01T10:00:00Z") // re-watch, later timestamp
            )
            val h = hint(
                lastAired = TmdbEpisodeSummary(1, 5, air_date = "2024-01-05"),
                seasons = listOf(TmdbSeasonSummary(1, 10))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            assertEquals("S01E03", (result as ShowProgress.InProgress).latestWatchedLabel)
        }

        @Test
        fun `season 0 specials are ignored when picking latest`() {
            val e = entry(
                Triple(0, 1, "2024-03-01T10:00:00Z"), // later special
                Triple(1, 2, "2024-01-01T10:00:00Z")
            )
            val h = hint(
                lastAired = TmdbEpisodeSummary(1, 5, air_date = "2024-02-01"),
                seasons = listOf(TmdbSeasonSummary(1, 10))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            assertEquals("S01E02", (result as ShowProgress.InProgress).latestWatchedLabel)
        }
    }

    @Nested
    @DisplayName("CaughtUp")
    inner class CaughtUpTest {
        @Test
        fun `ended show all watched returns CaughtUpEnded`() {
            val e = entry(
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-02T10:00:00Z")
            )
            val h = hint(
                status = "Ended",
                lastAired = TmdbEpisodeSummary(1, 2, air_date = "2023-12-01"),
                seasons = listOf(TmdbSeasonSummary(1, 2))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.CaughtUpEnded)
            assertEquals("S01E02", (result as ShowProgress.CaughtUpEnded).latestWatchedLabel)
        }

        @Test
        fun `airing show all watched with next scheduled returns CaughtUpAiring`() {
            val e = entry(Triple(1, 5, "2024-02-01T10:00:00Z"))
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(1, 5, air_date = "2024-02-01"),
                nextAired = TmdbEpisodeSummary(1, 6, air_date = "2024-02-08"),
                seasons = listOf(TmdbSeasonSummary(1, 10))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.CaughtUpAiring)
            assertEquals("S01E06", (result as ShowProgress.CaughtUpAiring).nextAiredLabel)
        }

        @Test
        fun `stale TMDB (watched ahead of lastAired) clamps to zero and stays CaughtUp`() {
            val e = entry(Triple(1, 8, "2024-02-10T10:00:00Z"))
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(1, 5, air_date = "2024-02-01"),
                nextAired = TmdbEpisodeSummary(1, 6, air_date = "2024-02-08"),
                seasons = listOf(TmdbSeasonSummary(1, 10))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            // watchedOrdinal=8, airedOrdinal=5 → behind=0 (clamped), next scheduled → CaughtUpAiring
            assertTrue(result is ShowProgress.CaughtUpAiring)
        }

        @Test
        fun `canceled status treated as ended`() {
            val e = entry(Triple(1, 3, "2024-02-10T10:00:00Z"))
            val h = hint(
                status = "Canceled",
                lastAired = TmdbEpisodeSummary(1, 3, air_date = "2024-02-01"),
                seasons = listOf(TmdbSeasonSummary(1, 3))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.CaughtUpEnded)
        }

        @Test
        fun `no lastAired and no nextAired and airing status returns CaughtUpEnded fallback`() {
            val e = entry(Triple(1, 1, "2024-02-01T10:00:00Z"))
            val h = hint(status = "Returning Series")
            val result = ShowProgressCalculator.compute(e, h, utc)
            // No aired info → behind=0, not ended, no next → fallback CaughtUpEnded
            assertTrue(result is ShowProgress.CaughtUpEnded)
        }
    }
}
