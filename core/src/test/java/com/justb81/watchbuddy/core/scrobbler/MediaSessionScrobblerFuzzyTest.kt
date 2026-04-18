package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.model.TmdbTvSearchResponse
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MediaSessionScrobbler — Fuzzy Matching")
class MediaSessionScrobblerFuzzyTest {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(context, tmdbApiService, watchedShowSource, scrobbleDispatcher)
    }

    // ── normalize() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("normalize()")
    inner class NormalizeTest {

        @Test
        fun `lowercases input`() = assertEquals("breaking bad", scrobbler.normalize("Breaking Bad"))

        @Test
        fun `strips special characters`() = assertEquals("mr robot", scrobbler.normalize("Mr. Robot!"))

        @Test
        fun `removes leading article the`() =
            assertEquals("walking dead", scrobbler.normalize("The Walking Dead"))

        @Test
        fun `collapses multiple spaces`() =
            assertEquals("game of thrones", scrobbler.normalize("Game  of   Thrones"))

        @Test
        fun `trims whitespace`() = assertEquals("test", scrobbler.normalize("  test  "))

        @Test
        fun `handles empty string`() = assertEquals("", scrobbler.normalize(""))

        @Test
        fun `strips parentheses and special chars`() =
            assertEquals("show name 2024", scrobbler.normalize("Show Name (2024)"))

        @Test
        fun `combined normalization - article plus exclamation`() =
            assertEquals("walking dead", scrobbler.normalize("The Walking Dead!!!"))
    }

    // ── fuzzyScore() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("fuzzyScore()")
    inner class FuzzyScoreTest {

        @Test
        fun `exact match returns 1_0`() = assertEquals(1.0f, scrobbler.fuzzyScore("Test", "Test"))

        @Test
        fun `exact match after normalization`() =
            assertEquals(1.0f, scrobbler.fuzzyScore("Breaking Bad", "Breaking Bad"))

        @Test
        fun `matches despite article difference`() =
            assertEquals(1.0f, scrobbler.fuzzyScore("the walking dead", "Walking Dead"))

        @Test
        fun `matches despite special chars`() =
            assertEquals(1.0f, scrobbler.fuzzyScore("MR. ROBOT", "mr robot"))

        @Test
        fun `prefix match returns 0_95`() =
            assertEquals(0.95f, scrobbler.fuzzyScore("Breaking Bad", "Breaking Bad Season"))

        @Test
        fun `completely different strings return low score`() {
            val score = scrobbler.fuzzyScore("Breaking Bad", "Cooking Show")
            assertTrue(score < 0.50f)
        }

        @Test
        fun `empty string returns 0_0`() = assertEquals(0f, scrobbler.fuzzyScore("", "test"))

        @Test
        fun `both empty returns 0_0`() = assertEquals(0f, scrobbler.fuzzyScore("", ""))

        @Test
        fun `typo still above 0_70`() {
            assertTrue(scrobbler.fuzzyScore("Breaking Bad", "Breaking Baad") >= 0.70f)
        }

        @Test
        fun `one-char drop still above 0_70`() {
            assertTrue(scrobbler.fuzzyScore("Stranger Things", "Stranger Thing") >= 0.70f)
        }

        @Test
        fun `score always in 0 to 1 range`() {
            val score = scrobbler.fuzzyScore("Show A", "Show B")
            assertTrue(score in 0f..1f)
        }
    }

    // ── Levenshtein distance ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Levenshtein distance via fuzzyScore")
    inner class LevenshteinTest {

        @Test
        fun `identical strings have perfect score`() =
            assertEquals(1.0f, scrobbler.fuzzyScore("hello", "hello"))

        @Test
        fun `single character substitution reduces score but stays high`() {
            val score = scrobbler.fuzzyScore("hello", "hallo")
            assertTrue(score > 0.7f)
            assertTrue(score < 1.0f)
        }

        @Test
        fun `completely different strings have very low score`() {
            val score = scrobbler.fuzzyScore("abcdef", "zyxwvu")
            assertTrue(score < 0.3f)
        }
    }

    // ── matchTitle() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("matchTitle()")
    inner class MatchTitleTest {

        private val breakingBadTmdb = TmdbShow(
            id = 1396,
            name = "Breaking Bad",
            first_air_date = "2008-01-20"
        )
        private val breakingBadShow = TraktShow(
            title = "Breaking Bad",
            ids = TraktIds(trakt = 1, tmdb = 1396)
        )

        @Test
        fun `cache hit returns candidate without TMDB call`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = breakingBadShow))
            coEvery { watchedShowSource.getTmdbApiKey() } returns "key"

            val result = scrobbler.matchTitle("com.netflix", "Breaking Bad S01E01")

            assertNotNull(result)
            assertEquals(breakingBadShow, result!!.matchedShow)
            assertEquals(1, result.matchedEpisode?.season)
            assertEquals(1, result.matchedEpisode?.number)
            coVerify(exactly = 0) { tmdbApiService.searchTv(any(), any()) }
        }

        @Test
        fun `falls back to TMDB when cache is empty`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns emptyList()
            coEvery { watchedShowSource.getTmdbApiKey() } returns "test-key"
            coEvery { tmdbApiService.searchTv("Breaking Bad", "test-key") } returns
                TmdbTvSearchResponse(listOf(breakingBadTmdb))

            val result = scrobbler.matchTitle("com.netflix", "Breaking Bad S01E01")

            assertNotNull(result)
            assertEquals("Breaking Bad", result!!.matchedShow?.title)
            assertEquals(2008, result.matchedShow?.year)
            assertEquals(TraktIds(tmdb = 1396), result.matchedShow?.ids)
        }

        @Test
        fun `returns null when no TMDB key and cache empty`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns emptyList()
            coEvery { watchedShowSource.getTmdbApiKey() } returns null

            val result = scrobbler.matchTitle("com.netflix", "Breaking Bad S01E01")

            assertNull(result)
            coVerify(exactly = 0) { tmdbApiService.searchTv(any(), any()) }
        }

        @Test
        fun `returns null when TMDB score too low`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns emptyList()
            coEvery { watchedShowSource.getTmdbApiKey() } returns "key"
            coEvery { tmdbApiService.searchTv(any(), any()) } returns
                TmdbTvSearchResponse(listOf(TmdbShow(id = 1, name = "Completely Unrelated Show")))

            val result = scrobbler.matchTitle("com.netflix", "Breaking Bad S01E01")

            assertNull(result)
        }

        @Test
        fun `returns null when TMDB search throws`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns emptyList()
            coEvery { watchedShowSource.getTmdbApiKey() } returns "key"
            coEvery { tmdbApiService.searchTv(any(), any()) } throws RuntimeException("network error")

            val result = scrobbler.matchTitle("com.netflix", "Breaking Bad S01E01")

            assertNull(result)
        }

        @Test
        fun `blank title returns null`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns emptyList()

            val result = scrobbler.matchTitle("com.netflix", "")

            assertNull(result)
            coVerify(exactly = 0) { watchedShowSource.getCachedShows() }
        }

        @Test
        fun `SxxExx pattern parses season and episode numbers`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = breakingBadShow))

            val result = scrobbler.matchTitle("com.netflix", "Breaking Bad S03E07")

            assertNotNull(result)
            assertEquals(3, result!!.matchedEpisode?.season)
            assertEquals(7, result.matchedEpisode?.number)
        }

        @Test
        fun `title without SxxExx has null episode`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = breakingBadShow))

            val result = scrobbler.matchTitle("com.netflix", "Breaking Bad")

            assertNotNull(result)
            assertNull(result!!.matchedEpisode)
        }
    }
}
