package com.justb81.watchbuddy.core.simkl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("SimklMappers")
class SimklMappersTest {

    // ── SimklIds.toTraktIds ───────────────────────────────────────────────────

    @Nested
    @DisplayName("SimklIds.toTraktIds")
    inner class ToTraktIdsTest {

        @Test
        fun `trakt id is always null for SIMKL items`() {
            val ids = SimklIds(simkl = 123, tmdb = 456)
            val traktIds = ids.toTraktIds()
            assertNull(traktIds.trakt)
        }

        @Test
        fun `simkl id is populated from simkl field`() {
            val ids = SimklIds(simkl = 789)
            val traktIds = ids.toTraktIds()
            assertEquals(789, traktIds.simkl)
        }

        @Test
        fun `simkl id falls back to simkl_id when simkl is null`() {
            val ids = SimklIds(simkl = null, simklId = 321)
            val traktIds = ids.toTraktIds()
            assertEquals(321, traktIds.simkl)
        }

        @Test
        fun `all other ids are copied correctly`() {
            val ids = SimklIds(
                simkl = 1,
                tmdb = 42,
                imdb = "tt0000001",
                tvdb = 100,
                slug = "breaking-bad"
            )
            val traktIds = ids.toTraktIds()
            assertEquals(42, traktIds.tmdb)
            assertEquals("tt0000001", traktIds.imdb)
            assertEquals(100, traktIds.tvdb)
            assertEquals("breaking-bad", traktIds.slug)
        }
    }

    // ── SimklShowItem.toTraktWatchedEntry ─────────────────────────────────────

    @Nested
    @DisplayName("SimklShowItem.toTraktWatchedEntry")
    inner class ToTraktWatchedEntryTest {

        private val simklShow = SimklShow(
            title = "Breaking Bad",
            year = 2008,
            ids = SimklIds(simkl = 100, tmdb = 1396)
        )

        @Test
        fun `maps show title and year`() {
            val item = SimklShowItem(show = simklShow)
            val entry = item.toTraktWatchedEntry()
            assertEquals("Breaking Bad", entry.show.title)
            assertEquals(2008, entry.show.year)
        }

        @Test
        fun `watchlist-only item has empty seasons`() {
            val item = SimklShowItem(show = simklShow, status = "plantowatch", seasons = emptyList())
            val entry = item.toTraktWatchedEntry()
            assertEquals(0, entry.seasons.size)
        }

        @Test
        fun `watched item maps seasons and episodes`() {
            val item = SimklShowItem(
                show = simklShow,
                status = "watching",
                seasons = listOf(
                    SimklSeason(
                        number = 1,
                        episodes = listOf(
                            SimklEpisode(number = 1, watchedAt = "2023-01-01T00:00:00Z"),
                            SimklEpisode(number = 2, watchedAt = "2023-01-02T00:00:00Z")
                        )
                    )
                )
            )
            val entry = item.toTraktWatchedEntry()
            assertEquals(1, entry.seasons.size)
            val season = entry.seasons[0]
            assertEquals(1, season.number)
            assertEquals(2, season.episodes.size)
            assertEquals(1, season.episodes[0].number)
            assertEquals(2, season.episodes[1].number)
        }

        @Test
        fun `each mapped episode has plays = 1`() {
            val item = SimklShowItem(
                show = simklShow,
                seasons = listOf(
                    SimklSeason(number = 1, episodes = listOf(SimklEpisode(number = 1)))
                )
            )
            val entry = item.toTraktWatchedEntry()
            assertEquals(1, entry.seasons[0].episodes[0].plays)
        }

        @Test
        fun `watchedAt is preserved in the mapped episode`() {
            val timestamp = "2024-05-15T20:00:00Z"
            val item = SimklShowItem(
                show = simklShow,
                seasons = listOf(
                    SimklSeason(number = 1, episodes = listOf(SimklEpisode(number = 3, watchedAt = timestamp)))
                )
            )
            val entry = item.toTraktWatchedEntry()
            assertEquals(timestamp, entry.seasons[0].episodes[0].last_watched_at)
        }
    }

    // ── SimklShowItem.toTraktSeasonsWithEpisodes ──────────────────────────────

    @Nested
    @DisplayName("SimklShowItem.toTraktSeasonsWithEpisodes")
    inner class ToTraktSeasonsWithEpisodesTest {

        private val show = SimklShow(title = "Show", ids = SimklIds(simkl = 1))

        @Test
        fun `empty seasons returns empty list`() {
            val item = SimklShowItem(show = show, seasons = emptyList())
            assertEquals(emptyList<Any>(), item.toTraktSeasonsWithEpisodes())
        }

        @Test
        fun `season number is preserved`() {
            val item = SimklShowItem(
                show = show,
                seasons = listOf(SimklSeason(number = 2, episodes = emptyList()))
            )
            assertEquals(2, item.toTraktSeasonsWithEpisodes()[0].number)
        }

        @Test
        fun `episode season and number are set correctly`() {
            val item = SimklShowItem(
                show = show,
                seasons = listOf(
                    SimklSeason(
                        number = 3,
                        episodes = listOf(SimklEpisode(number = 5))
                    )
                )
            )
            val season = item.toTraktSeasonsWithEpisodes()[0]
            val ep = season.episodes[0]
            assertEquals(3, ep.season)
            assertEquals(5, ep.number)
        }
    }

    // ── SimklSearchResult.toTraktSearchResult ─────────────────────────────────

    @Nested
    @DisplayName("SimklSearchResult.toTraktSearchResult")
    inner class ToTraktSearchResultTest {

        @Test
        fun `returns null when ids are null`() {
            val result = SimklSearchResult(title = "Show", ids = null)
            assertNull(result.toTraktSearchResult())
        }

        @Test
        fun `returns null when title is null`() {
            val result = SimklSearchResult(title = null, ids = SimklIds(simkl = 1))
            assertNull(result.toTraktSearchResult())
        }

        @Test
        fun `maps title and year`() {
            val result = SimklSearchResult(
                title = "Inception",
                year = 2010,
                ids = SimklIds(simkl = 42, tmdb = 27205),
                type = "movie",
                scores = SimklSearchScores(best = 0.98f)
            )
            val mapped = result.toTraktSearchResult()
            assertNotNull(mapped)
            assertEquals("Inception", mapped!!.show?.title)
            assertEquals(2010, mapped.show?.year)
        }

        @Test
        fun `type is always show`() {
            val result = SimklSearchResult(
                title = "Series",
                ids = SimklIds(simkl = 1)
            )
            assertEquals("show", result.toTraktSearchResult()!!.type)
        }

        @Test
        fun `score is taken from scores best`() {
            val result = SimklSearchResult(
                title = "Show",
                ids = SimklIds(simkl = 1),
                scores = SimklSearchScores(best = 0.75f)
            )
            assertEquals(0.75f, result.toTraktSearchResult()!!.score)
        }

        @Test
        fun `score is null when scores is null`() {
            val result = SimklSearchResult(title = "Show", ids = SimklIds(simkl = 1))
            assertNull(result.toTraktSearchResult()!!.score)
        }
    }

    // ── SimklIds.canonicalSimklId ─────────────────────────────────────────────

    @Nested
    @DisplayName("SimklIds.canonicalSimklId")
    inner class CanonicalSimklIdTest {

        @Test
        fun `returns simkl when both are set`() {
            val ids = SimklIds(simkl = 10, simklId = 20)
            assertEquals(10, ids.canonicalSimklId)
        }

        @Test
        fun `returns simkl_id when simkl is null`() {
            val ids = SimklIds(simkl = null, simklId = 20)
            assertEquals(20, ids.canonicalSimklId)
        }

        @Test
        fun `returns null when both are null`() {
            val ids = SimklIds()
            assertNull(ids.canonicalSimklId)
        }
    }
}
