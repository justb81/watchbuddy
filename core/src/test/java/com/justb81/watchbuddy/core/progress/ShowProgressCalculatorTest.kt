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
        fun `watched S01E01-03 with 5 aired in season yields behind=2`() {
            // episode_count=5 represents the 5 aired episodes; watched 3 of them → behind=2
            val e = entry(
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-02T10:00:00Z"),
                Triple(1, 3, "2024-01-03T10:00:00Z")
            )
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(1, 5, air_date = "2024-02-01"),
                nextAired = TmdbEpisodeSummary(1, 6, air_date = "2024-02-08"),
                seasons = listOf(TmdbSeasonSummary(1, 5))
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
            // User watched all 10 of S1. S2 (8 eps) fully aired, S3 has 2 aired.
            // episode_count per season = aired count: S1=10, S2=8, S3=2.
            // airedRegular=20, watchedRegular=10 → behind=10.
            val e = entry(
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-02T10:00:00Z"),
                Triple(1, 3, "2024-01-03T10:00:00Z"),
                Triple(1, 4, "2024-01-04T10:00:00Z"),
                Triple(1, 5, "2024-01-05T10:00:00Z"),
                Triple(1, 6, "2024-01-06T10:00:00Z"),
                Triple(1, 7, "2024-01-07T10:00:00Z"),
                Triple(1, 8, "2024-01-08T10:00:00Z"),
                Triple(1, 9, "2024-01-09T10:00:00Z"),
                Triple(1, 10, "2024-01-10T10:00:00Z")
            )
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(3, 2, air_date = "2024-06-01"),
                seasons = listOf(
                    TmdbSeasonSummary(1, 10),
                    TmdbSeasonSummary(2, 8),
                    TmdbSeasonSummary(3, 2)
                )
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
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
        fun `season 0 episode_count does not inflate airedRegular count`() {
            // TMDB returns S0 with 100 specials alongside real seasons. The
            // S0 episode_count must not be included in the aired-regular sum.
            // User watched S1E01-E05 (5 eps); S1 has 10, S2 has 3 aired.
            // airedRegular = 10+3 = 13 (S0=100 excluded), watchedRegular = 5 → behind=8.
            val e = entry(
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-02T10:00:00Z"),
                Triple(1, 3, "2024-01-03T10:00:00Z"),
                Triple(1, 4, "2024-01-04T10:00:00Z"),
                Triple(1, 5, "2024-01-05T10:00:00Z")
            )
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(2, 3, air_date = "2024-02-01"),
                seasons = listOf(
                    TmdbSeasonSummary(0, 100), // specials — must not inflate count
                    TmdbSeasonSummary(1, 10),
                    TmdbSeasonSummary(2, 3)
                )
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            assertEquals(8, (result as ShowProgress.InProgress).episodesBehind)
        }

        @Test
        fun `only-specials aired reports zero behind`() {
            // User has watched all 10 regular episodes of S1. The most recently
            // aired episode is a special (season 0). S0 must not be counted in
            // airedRegular. airedRegular=10, watchedRegular=10 → behind=0 → CaughtUpAiring.
            val e = entry(
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-02T10:00:00Z"),
                Triple(1, 3, "2024-01-03T10:00:00Z"),
                Triple(1, 4, "2024-01-04T10:00:00Z"),
                Triple(1, 5, "2024-01-05T10:00:00Z"),
                Triple(1, 6, "2024-01-06T10:00:00Z"),
                Triple(1, 7, "2024-01-07T10:00:00Z"),
                Triple(1, 8, "2024-01-08T10:00:00Z"),
                Triple(1, 9, "2024-01-09T10:00:00Z"),
                Triple(1, 10, "2024-01-10T10:00:00Z")
            )
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
            assertTrue(result is ShowProgress.CaughtUpAiring)
        }

        @Test
        fun `only finale watched on ended show yields InProgress not CaughtUpEnded`() {
            // Bug regression: user watched only S03E08 (finale) of an 8-ep ended show.
            // The ordinal approach gave behind=0 (watched ordinal == aired ordinal) and
            // wrongly returned CaughtUpEnded. The count approach gives behind=7.
            val e = entry(Triple(3, 8, "2024-10-08T10:00:00Z"))
            val h = hint(
                status = "Ended",
                lastAired = TmdbEpisodeSummary(3, 8, air_date = "2024-10-01"),
                seasons = listOf(TmdbSeasonSummary(3, 8))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            assertEquals(7, (result as ShowProgress.InProgress).episodesBehind)
        }

        @Test
        fun `5 of 8 episodes watched contiguously yields behind=3`() {
            val e = entry(
                Triple(3, 1, "2024-03-01T10:00:00Z"),
                Triple(3, 2, "2024-03-08T10:00:00Z"),
                Triple(3, 3, "2024-03-15T10:00:00Z"),
                Triple(3, 4, "2024-03-22T10:00:00Z"),
                Triple(3, 5, "2024-03-29T10:00:00Z")
            )
            val h = hint(
                status = "Ended",
                lastAired = TmdbEpisodeSummary(3, 8, air_date = "2024-05-01"),
                seasons = listOf(TmdbSeasonSummary(3, 8))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            assertEquals(3, (result as ShowProgress.InProgress).episodesBehind)
        }

        @Test
        fun `5 of 8 episodes watched with gap at S03E03 yields behind=3`() {
            // Gap-watching: S03E03 skipped. Count-based behind correctly counts
            // unwatched episodes (8 total - 5 watched = 3), not ordinal gap.
            val e = entry(
                Triple(3, 1, "2024-03-01T10:00:00Z"),
                Triple(3, 2, "2024-03-08T10:00:00Z"),
                // S03E03 intentionally skipped
                Triple(3, 4, "2024-03-22T10:00:00Z"),
                Triple(3, 5, "2024-03-29T10:00:00Z"),
                Triple(3, 6, "2024-04-05T10:00:00Z")
            )
            val h = hint(
                status = "Ended",
                lastAired = TmdbEpisodeSummary(3, 8, air_date = "2024-05-01"),
                seasons = listOf(TmdbSeasonSummary(3, 8))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
            assertEquals(3, (result as ShowProgress.InProgress).episodesBehind)
        }

        @Test
        fun `specials watched alongside regulars do not affect behind delta`() {
            // User watched S1E01-E03 (3 regular eps) plus a special (S0E03).
            // episode_count=5 for S1 (5 aired regular eps), S0 excluded from count.
            // airedRegular=5, watchedRegular=3 → behind=2.
            val e = entry(
                Triple(0, 3, "2024-03-01T10:00:00Z"),   // special — must not count
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-05T10:00:00Z"),
                Triple(1, 3, "2024-01-10T10:00:00Z")
            )
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(1, 5, air_date = "2024-02-01"),
                seasons = listOf(
                    TmdbSeasonSummary(0, 20),
                    TmdbSeasonSummary(1, 5)
                )
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.InProgress)
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
        fun `all 8 of 8 episodes watched on ended show returns CaughtUpEnded`() {
            // Regression guard: user watched every episode → CaughtUpEnded, not InProgress.
            val e = entry(
                Triple(3, 1, "2024-03-01T10:00:00Z"),
                Triple(3, 2, "2024-03-08T10:00:00Z"),
                Triple(3, 3, "2024-03-15T10:00:00Z"),
                Triple(3, 4, "2024-03-22T10:00:00Z"),
                Triple(3, 5, "2024-03-29T10:00:00Z"),
                Triple(3, 6, "2024-04-05T10:00:00Z"),
                Triple(3, 7, "2024-04-12T10:00:00Z"),
                Triple(3, 8, "2024-10-08T10:00:00Z")
            )
            val h = hint(
                status = "Ended",
                lastAired = TmdbEpisodeSummary(3, 8, air_date = "2024-10-01"),
                seasons = listOf(TmdbSeasonSummary(3, 8))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.CaughtUpEnded)
        }

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
            // User watched all 5 aired episodes; episode_count=5 to match.
            // airedRegular=5, watchedRegular=5 → behind=0 → CaughtUpAiring.
            val e = entry(
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-08T10:00:00Z"),
                Triple(1, 3, "2024-01-15T10:00:00Z"),
                Triple(1, 4, "2024-01-22T10:00:00Z"),
                Triple(1, 5, "2024-02-01T10:00:00Z")
            )
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(1, 5, air_date = "2024-02-01"),
                nextAired = TmdbEpisodeSummary(1, 6, air_date = "2024-02-08"),
                seasons = listOf(TmdbSeasonSummary(1, 5))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.CaughtUpAiring)
            assertEquals("S01E06", (result as ShowProgress.CaughtUpAiring).nextAiredLabel)
        }

        @Test
        fun `stale TMDB (watched ahead of lastAired) clamps to zero and stays CaughtUp`() {
            // Trakt is fresher than TMDB: user watched 8 episodes but TMDB only lists 5.
            // watchedRegular=8, airedRegular=5 → behind=max(5-8,0)=0 → CaughtUpAiring.
            val e = entry(
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-08T10:00:00Z"),
                Triple(1, 3, "2024-01-15T10:00:00Z"),
                Triple(1, 4, "2024-01-22T10:00:00Z"),
                Triple(1, 5, "2024-01-29T10:00:00Z"),
                Triple(1, 6, "2024-02-05T10:00:00Z"),
                Triple(1, 7, "2024-02-07T10:00:00Z"),
                Triple(1, 8, "2024-02-10T10:00:00Z")
            )
            val h = hint(
                status = "Returning Series",
                lastAired = TmdbEpisodeSummary(1, 5, air_date = "2024-02-01"),
                nextAired = TmdbEpisodeSummary(1, 6, air_date = "2024-02-08"),
                seasons = listOf(TmdbSeasonSummary(1, 5))
            )
            val result = ShowProgressCalculator.compute(e, h, utc)
            assertTrue(result is ShowProgress.CaughtUpAiring)
        }

        @Test
        fun `canceled status treated as ended`() {
            // All 3 episodes watched on a canceled show → CaughtUpEnded.
            val e = entry(
                Triple(1, 1, "2024-01-01T10:00:00Z"),
                Triple(1, 2, "2024-01-08T10:00:00Z"),
                Triple(1, 3, "2024-02-10T10:00:00Z")
            )
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

    @Nested
    @DisplayName("nextEpisodeNumbers")
    inner class NextEpisodeNumbersTest {

        @Test
        fun `returns hint nextAired when available`() {
            val e = entry(Triple(1, 3, "2024-01-01T10:00:00Z"))
            val h = hint(nextAired = TmdbEpisodeSummary(2, 1, air_date = "2024-06-01"))
            assertEquals(2 to 1, ShowProgressCalculator.nextEpisodeNumbers(e, h))
        }

        @Test
        fun `skips season-0 nextAired and falls back to Trakt +1`() {
            val e = entry(Triple(1, 3, "2024-01-01T10:00:00Z"))
            val h = hint(nextAired = TmdbEpisodeSummary(0, 5, air_date = "2024-06-01"))
            assertEquals(1 to 4, ShowProgressCalculator.nextEpisodeNumbers(e, h))
        }

        @Test
        fun `no hint returns Trakt latestWatched + 1`() {
            val e = entry(Triple(2, 5, "2024-01-01T10:00:00Z"))
            assertEquals(2 to 6, ShowProgressCalculator.nextEpisodeNumbers(e, null))
        }

        @Test
        fun `no watched episodes and no hint returns S01E01`() {
            val e = entry()
            assertEquals(1 to 1, ShowProgressCalculator.nextEpisodeNumbers(e, null))
        }

        @Test
        fun `no watched episodes with hint nextAired returns hint values`() {
            val e = entry()
            val h = hint(nextAired = TmdbEpisodeSummary(1, 3, air_date = "2024-06-01"))
            assertEquals(1 to 3, ShowProgressCalculator.nextEpisodeNumbers(e, h))
        }

        @Test
        fun `hint with null nextAired falls back to Trakt +1`() {
            val e = entry(Triple(1, 7, "2024-01-01T10:00:00Z"))
            val h = hint(nextAired = null)
            assertEquals(1 to 8, ShowProgressCalculator.nextEpisodeNumbers(e, h))
        }

        @Test
        fun `picks highest S×E pair from Trakt data (not latest timestamp)`() {
            val e = entry(
                Triple(1, 5, "2024-01-05T10:00:00Z"),
                Triple(1, 3, "2024-02-01T10:00:00Z") // later timestamp but lower episode
            )
            assertEquals(1 to 6, ShowProgressCalculator.nextEpisodeNumbers(e, null))
        }

        @Test
        fun `specials are excluded from Trakt fallback calculation`() {
            val e = entry(
                Triple(0, 10, "2024-03-01T10:00:00Z"), // season 0 special
                Triple(1, 4, "2024-01-01T10:00:00Z")
            )
            assertEquals(1 to 5, ShowProgressCalculator.nextEpisodeNumbers(e, null))
        }

    }

    @Nested
    @DisplayName("nextUnwatchedEpisodeNumbers")
    inner class NextUnwatchedEpisodeNumbersTest {

        @Test
        fun `not started returns S01E01 regardless of nextAired hint`() {
            val e = entry()
            val h = hint(nextAired = TmdbEpisodeSummary(1, 3, air_date = "2024-06-01"))
            assertEquals(1 to 1, ShowProgressCalculator.nextUnwatchedEpisodeNumbers(e, h))
        }

        @Test
        fun `not started with no hint also returns S01E01`() {
            val e = entry()
            assertEquals(1 to 1, ShowProgressCalculator.nextUnwatchedEpisodeNumbers(e, null))
        }

        @Test
        fun `no hint falls back to latestWatched plus one`() {
            val e = entry(Triple(2, 5, "2024-01-01T10:00:00Z"))
            assertEquals(2 to 6, ShowProgressCalculator.nextUnwatchedEpisodeNumbers(e, null))
        }

        @Test
        fun `caught up with nextAired returns nextAired`() {
            val e = entry(Triple(1, 3, "2024-01-01T10:00:00Z"))
            val h = hint(nextAired = TmdbEpisodeSummary(2, 1, air_date = "2024-06-01"))
            assertEquals(2 to 1, ShowProgressCalculator.nextUnwatchedEpisodeNumbers(e, h))
        }

        @Test
        fun `caught up with null nextAired returns naive plus one`() {
            val e = entry(Triple(1, 7, "2024-01-01T10:00:00Z"))
            val h = hint(nextAired = null)
            assertEquals(1 to 8, ShowProgressCalculator.nextUnwatchedEpisodeNumbers(e, h))
        }

        // ── Issue #498 regression tests ───────────────────────────────────────

        @Test
        fun `user behind (S03E05, last aired S05E10, next S05E11) returns S03E06 not S05E11`() {
            val e = entry(Triple(3, 5, "2024-01-01T10:00:00Z"))
            val h = hint(
                lastAired = TmdbEpisodeSummary(5, 10, air_date = "2024-03-01"),
                nextAired = TmdbEpisodeSummary(5, 11, air_date = "2024-04-01"),
                seasons = listOf(
                    TmdbSeasonSummary(1, 10),
                    TmdbSeasonSummary(2, 10),
                    TmdbSeasonSummary(3, 10),
                    TmdbSeasonSummary(4, 10),
                    TmdbSeasonSummary(5, 11)
                )
            )
            assertEquals(3 to 6, ShowProgressCalculator.nextUnwatchedEpisodeNumbers(e, h))
        }

        @Test
        fun `user at season finale (S03E08) with S04E02 aired crosses season boundary to S04E01`() {
            val e = entry(Triple(3, 8, "2024-01-01T10:00:00Z"))
            val h = hint(
                lastAired = TmdbEpisodeSummary(4, 2, air_date = "2024-03-01"),
                seasons = listOf(
                    TmdbSeasonSummary(1, 10),
                    TmdbSeasonSummary(2, 10),
                    TmdbSeasonSummary(3, 8),
                    TmdbSeasonSummary(4, 10)
                )
            )
            assertEquals(4 to 1, ShowProgressCalculator.nextUnwatchedEpisodeNumbers(e, h))
        }

        @Test
        fun `user caught up to last aired (S05E10) with next aired (S05E11) returns S05E11`() {
            val e = entry(Triple(5, 10, "2024-01-01T10:00:00Z"))
            val h = hint(
                lastAired = TmdbEpisodeSummary(5, 10, air_date = "2024-03-01"),
                nextAired = TmdbEpisodeSummary(5, 11, air_date = "2024-04-01"),
                seasons = listOf(
                    TmdbSeasonSummary(1, 10),
                    TmdbSeasonSummary(2, 10),
                    TmdbSeasonSummary(3, 10),
                    TmdbSeasonSummary(4, 10),
                    TmdbSeasonSummary(5, 10)
                )
            )
            assertEquals(5 to 11, ShowProgressCalculator.nextUnwatchedEpisodeNumbers(e, h))
        }

        @Test
        fun `user caught up on ended show with no nextAired returns naive plus one`() {
            val e = entry(Triple(5, 10, "2024-01-01T10:00:00Z"))
            val h = hint(
                status = "Ended",
                lastAired = TmdbEpisodeSummary(5, 10, air_date = "2024-03-01"),
                nextAired = null,
                seasons = listOf(
                    TmdbSeasonSummary(1, 10),
                    TmdbSeasonSummary(2, 10),
                    TmdbSeasonSummary(3, 10),
                    TmdbSeasonSummary(4, 10),
                    TmdbSeasonSummary(5, 10)
                )
            )
            assertEquals(5 to 11, ShowProgressCalculator.nextUnwatchedEpisodeNumbers(e, h))
        }
    }
}
