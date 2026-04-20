package com.justb81.watchbuddy.core.progress

import com.justb81.watchbuddy.core.model.TmdbEpisodeSummary
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TmdbSeasonSummary
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        fun `picks highest episode number not latest timestamp`() {
            // S01E03 was back-filled later (newer timestamp) but S01E05 is the furthest
            // episode in the series — it must be reported as last-watched.
            // lastAired=S01E08 so behind=3 → InProgress (not CaughtUp).
            val e = entry(
                Triple(1, 5, "2024-01-05T10:00:00Z"),
                Triple(1, 3, "2024-02-01T10:00:00Z") // back-filled, newer timestamp
            )
            val h = hint(
                lastAired = TmdbEpisodeSummary(1, 8, air_date = "2024-03-01"),
                seasons = listOf(TmdbSeasonSummary(1, 10))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            assertEquals("S01E05", (result as ShowProgress.InProgress).latestWatchedLabel)
        }

        @Test
        fun `back-filling an earlier episode does not displace a higher watched episode`() {
            // User has watched up to S02E03. They back-fill S01E02 which gets a newer
            // Instant.now() timestamp. The displayed last-watched must remain S02E03.
            val e = entry(
                Triple(2, 3, "2024-03-01T10:00:00Z"),
                Triple(1, 2, "2024-05-01T10:00:00Z")  // back-filled later
            )
            val h = hint(
                lastAired = TmdbEpisodeSummary(2, 5, air_date = "2024-04-01"),
                seasons = listOf(TmdbSeasonSummary(1, 10), TmdbSeasonSummary(2, 10))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            assertEquals("S02E03", (result as ShowProgress.InProgress).latestWatchedLabel)
        }

        @Test
        fun `picks highest season over higher episode number in a lower season`() {
            // S01E10 has a higher episode number than S02E01, but S02 is the further
            // season — S02E01 must win.
            val e = entry(
                Triple(1, 10, "2024-01-10T10:00:00Z"),
                Triple(2, 1, "2024-02-01T10:00:00Z")
            )
            val h = hint(
                lastAired = TmdbEpisodeSummary(2, 5, air_date = "2024-03-01"),
                seasons = listOf(TmdbSeasonSummary(1, 10), TmdbSeasonSummary(2, 10))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            assertEquals("S02E01", (result as ShowProgress.InProgress).latestWatchedLabel)
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

        @Test
        fun `season 0 episode_count does not inflate airedOrdinal across seasons`() {
            // TMDB returns S0 with 100 specials alongside real seasons. The
            // S0 episode_count must not be summed into prior-season totals
            // when computing the gap between watched and aired.
            val e = entry(Triple(1, 5, "2024-01-05T10:00:00Z"))
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(2, 3, air_date = "2024-02-01"),
                seasons = listOf(
                    TmdbSeasonSummary(0, 100), // specials
                    TmdbSeasonSummary(1, 10),
                    TmdbSeasonSummary(2, 10)
                )
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            // watched = S1E5 → 5
            // aired   = S2E3 → priorSum(S1=10) + 3 = 13  (S0=100 must NOT count)
            // behind  = 13 - 5 = 8
            assertEquals(8, (result as ShowProgress.InProgress).episodesBehind)
        }

        @Test
        fun `only-specials aired reports zero behind`() {
            // TMDB says the most recently aired episode is a special. The
            // calculator must not treat that as a regular episode the user
            // is behind on.
            val e = entry(Triple(1, 10, "2024-01-05T10:00:00Z"))
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(0, 47, air_date = "2024-02-01"),
                nextAired = TmdbEpisodeSummary(2, 1, air_date = "2024-06-01"),
                seasons = listOf(
                    TmdbSeasonSummary(0, 50),
                    TmdbSeasonSummary(1, 10)
                )
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            // lastAired is a special → airedOrdinal=0 → behind clamps to 0,
            // and because there is a next regular aired we expect CaughtUpAiring.
            assertTrue(result is ShowProgress.CaughtUpAiring)
        }

        @Test
        fun `specials watched alongside regulars do not affect behind delta`() {
            val e = entry(
                Triple(0, 3, "2024-03-01T10:00:00Z"),   // special
                Triple(1, 2, "2024-01-01T10:00:00Z"),
                Triple(1, 3, "2024-01-10T10:00:00Z")
            )
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(1, 5, air_date = "2024-02-01"),
                seasons = listOf(
                    TmdbSeasonSummary(0, 20),
                    TmdbSeasonSummary(1, 10)
                )
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            // watched regular = S1E3 → 3, aired regular = S1E5 → 5
            assertEquals(2, (result as ShowProgress.InProgress).episodesBehind)
            assertEquals("S01E03", result.latestWatchedLabel)
        }
    }

    @Nested
    @DisplayName("isCompleted")
    inner class IsCompletedTest {

        private fun seasons(vararg counts: Pair<Int, Int>) =
            counts.map { (season, count) -> TmdbSeasonSummary(season, count) }

        @Test
        fun `returns false when hint is null`() {
            val e = entry(Triple(1, 1, "2024-01-01T10:00:00Z"), Triple(1, 2, "2024-01-02T10:00:00Z"))
            assertFalse(ShowProgressCalculator.isCompleted(e, null))
        }

        @Test
        fun `returns false when airedRegular is zero (upcoming series)`() {
            val e = entry()
            val h = hint(seasons = seasons(1 to 0))
            assertFalse(ShowProgressCalculator.isCompleted(e, h))
        }

        @Test
        fun `returns false when no episodes watched`() {
            val e = entry()
            val h = hint(seasons = seasons(1 to 5))
            assertFalse(ShowProgressCalculator.isCompleted(e, h))
        }

        @Test
        fun `returns false when partially watched`() {
            val e = entry(Triple(1, 1, "2024-01-01T10:00:00Z"), Triple(1, 2, "2024-01-02T10:00:00Z"))
            val h = hint(seasons = seasons(1 to 5))
            assertFalse(ShowProgressCalculator.isCompleted(e, h))
        }

        @Test
        fun `returns true when all regular episodes watched`() {
            val e = entry(
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-02T10:00:00Z"),
                Triple(2, 1, "2024-02-01T10:00:00Z")
            )
            val h = hint(seasons = seasons(1 to 2, 2 to 1))
            assertTrue(ShowProgressCalculator.isCompleted(e, h))
        }

        @Test
        fun `returns true when Trakt is ahead of TMDB (watched count exceeds aired)`() {
            // Stale TMDB: user has watched 3 but TMDB says only 2 aired.
            val e = entry(
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-02T10:00:00Z"),
                Triple(1, 3, "2024-01-03T10:00:00Z")
            )
            val h = hint(seasons = seasons(1 to 2))
            assertTrue(ShowProgressCalculator.isCompleted(e, h))
        }

        @Test
        fun `specials (S0) are excluded from both aired and watched counts`() {
            // Only S0 aired (no regular episodes) → not completed.
            val e = entry(Triple(0, 1, "2024-01-01T10:00:00Z"))
            val h = hint(seasons = seasons(0 to 5))
            assertFalse(ShowProgressCalculator.isCompleted(e, h))
        }

        @Test
        fun `specials watched alongside regulars do not inflate watched count`() {
            // 1 regular aired, 1 regular watched + 1 special watched → completed
            val e = entry(
                Triple(0, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 1, "2024-02-01T10:00:00Z")
            )
            val h = hint(seasons = seasons(0 to 5, 1 to 1))
            assertTrue(ShowProgressCalculator.isCompleted(e, h))
        }

        @Test
        fun `multi-season show partially watched is not completed`() {
            val e = entry(
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-02T10:00:00Z"),
                Triple(2, 1, "2024-02-01T10:00:00Z")
            )
            val h = hint(seasons = seasons(1 to 2, 2 to 3))
            assertFalse(ShowProgressCalculator.isCompleted(e, h))
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

    @Nested
    @DisplayName("hasNewSeasonAvailable")
    inner class HasNewSeasonAvailableTest {

        @Test
        fun `null hint returns false`() {
            val e = entry(Triple(1, 10, "2024-01-01T10:00:00Z"))
            assertFalse(ShowProgressCalculator.hasNewSeasonAvailable(e, null))
        }

        @Test
        fun `no watched episodes returns false`() {
            val e = entry()
            val h = hint(
                lastAired = TmdbEpisodeSummary(2, 1, air_date = "2024-06-01"),
                seasons = listOf(TmdbSeasonSummary(1, 10), TmdbSeasonSummary(2, 8))
            )
            assertFalse(ShowProgressCalculator.hasNewSeasonAvailable(e, h))
        }

        @Test
        fun `mid-season (episode below count) returns false`() {
            val e = entry(Triple(1, 5, "2024-01-01T10:00:00Z"))
            val h = hint(
                lastAired = TmdbEpisodeSummary(2, 1, air_date = "2024-06-01"),
                seasons = listOf(TmdbSeasonSummary(1, 10), TmdbSeasonSummary(2, 8))
            )
            assertFalse(ShowProgressCalculator.hasNewSeasonAvailable(e, h))
        }

        @Test
        fun `finished season but next season not yet aired returns false`() {
            val e = entry(Triple(1, 10, "2024-01-01T10:00:00Z"))
            val h = hint(
                lastAired = TmdbEpisodeSummary(1, 10, air_date = "2024-03-01"),
                seasons = listOf(TmdbSeasonSummary(1, 10))
            )
            assertFalse(ShowProgressCalculator.hasNewSeasonAvailable(e, h))
        }

        @Test
        fun `finished season and new season S2E01 aired returns true`() {
            val e = entry(Triple(1, 10, "2024-01-01T10:00:00Z"))
            val h = hint(
                lastAired = TmdbEpisodeSummary(2, 1, air_date = "2024-06-01"),
                seasons = listOf(TmdbSeasonSummary(1, 10), TmdbSeasonSummary(2, 8))
            )
            assertTrue(ShowProgressCalculator.hasNewSeasonAvailable(e, h))
        }

        @Test
        fun `finished season and new season has multiple episodes aired returns true`() {
            val e = entry(Triple(1, 10, "2024-01-01T10:00:00Z"))
            val h = hint(
                lastAired = TmdbEpisodeSummary(2, 4, air_date = "2024-07-01"),
                seasons = listOf(TmdbSeasonSummary(1, 10), TmdbSeasonSummary(2, 8))
            )
            assertTrue(ShowProgressCalculator.hasNewSeasonAvailable(e, h))
        }

        @Test
        fun `watching mid-season-2 does not show new season available`() {
            val e = entry(
                Triple(1, 10, "2024-01-01T10:00:00Z"),
                Triple(2, 3, "2024-06-15T10:00:00Z")
            )
            val h = hint(
                lastAired = TmdbEpisodeSummary(3, 1, air_date = "2024-12-01"),
                seasons = listOf(
                    TmdbSeasonSummary(1, 10),
                    TmdbSeasonSummary(2, 8),
                    TmdbSeasonSummary(3, 6)
                )
            )
            // latestWatched = S02E03, season 2 has 8 eps, ep 3 < 8 → mid-season → false
            assertFalse(ShowProgressCalculator.hasNewSeasonAvailable(e, h))
        }

        @Test
        fun `specials only (season 0) do not trigger new season`() {
            val e = entry(Triple(0, 5, "2024-01-01T10:00:00Z"))
            val h = hint(
                lastAired = TmdbEpisodeSummary(1, 1, air_date = "2024-06-01"),
                seasons = listOf(TmdbSeasonSummary(0, 5), TmdbSeasonSummary(1, 8))
            )
            // no regular episodes watched → latestWatched = null → false
            assertFalse(ShowProgressCalculator.hasNewSeasonAvailable(e, h))
        }

        @Test
        fun `last watched season not found in TMDB seasons returns false`() {
            val e = entry(Triple(3, 5, "2024-01-01T10:00:00Z"))
            val h = hint(
                lastAired = TmdbEpisodeSummary(4, 1, air_date = "2024-06-01"),
                seasons = listOf(TmdbSeasonSummary(1, 10), TmdbSeasonSummary(2, 8))
                // Season 3 missing from TMDB data
            )
            assertFalse(ShowProgressCalculator.hasNewSeasonAvailable(e, h))
        }

        @Test
        fun `episode count zero in season returns false`() {
            val e = entry(Triple(1, 1, "2024-01-01T10:00:00Z"))
            val h = hint(
                lastAired = TmdbEpisodeSummary(2, 1, air_date = "2024-06-01"),
                seasons = listOf(TmdbSeasonSummary(1, 0))
            )
            assertFalse(ShowProgressCalculator.hasNewSeasonAvailable(e, h))
        }

        @Test
        fun `no lastAired in hint returns false`() {
            val e = entry(Triple(1, 10, "2024-01-01T10:00:00Z"))
            val h = hint(
                lastAired = null,
                seasons = listOf(TmdbSeasonSummary(1, 10))
            )
            assertFalse(ShowProgressCalculator.hasNewSeasonAvailable(e, h))
        }
    }
}
