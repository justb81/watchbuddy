package com.justb81.watchbuddy.tv.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.ScrobbleBody
import com.justb81.watchbuddy.core.trakt.ScrobbleResponse
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.tv.data.TvShowCache
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest

@DisplayName("MediaSessionScrobbler")
class MediaSessionScrobblerTest {

    private val context: Context = mockk(relaxed = true)
    private val traktApi: TraktApiService = mockk()
    private val tvShowCache: TvShowCache = mockk()
    private val tvTokenCache: TvTokenCache = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(context, traktApi, tvShowCache, tvTokenCache)
    }

    // ── normalize() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("normalize()")
    inner class NormalizeTest {

        @Test
        fun `lowercases input`() {
            assertEquals("breaking bad", scrobbler.normalize("Breaking Bad"))
        }

        @Test
        fun `strips special characters`() {
            assertEquals("mr robot", scrobbler.normalize("Mr. Robot!"))
        }

        @Test
        fun `removes leading article the`() {
            assertEquals("walking dead", scrobbler.normalize("The Walking Dead"))
        }

        @Test
        fun `collapses multiple spaces`() {
            assertEquals("game of thrones", scrobbler.normalize("Game  of   Thrones"))
        }

        @Test
        fun `trims whitespace`() {
            assertEquals("test", scrobbler.normalize("  test  "))
        }

        @Test
        fun `handles empty string`() {
            assertEquals("", scrobbler.normalize(""))
        }

        @Test
        fun `combined normalization`() {
            assertEquals("walking dead", scrobbler.normalize("The Walking Dead!!!"))
        }

        @Test
        fun `strips parentheses and brackets`() {
            assertEquals("show name 2024", scrobbler.normalize("Show Name (2024)"))
        }
    }

    // ── fuzzyScore() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("fuzzyScore()")
    inner class FuzzyScoreTest {

        @Test
        fun `exact match returns 1_0`() {
            assertEquals(1.0f, scrobbler.fuzzyScore("Test", "Test"))
        }

        @Test
        fun `exact match after normalization - Breaking Bad`() {
            assertEquals(1.0f, scrobbler.fuzzyScore("Breaking Bad", "Breaking Bad"))
        }

        @Test
        fun `exact match after normalization - Walking Dead with article`() {
            assertEquals(1.0f, scrobbler.fuzzyScore("the walking dead", "Walking Dead"))
        }

        @Test
        fun `exact match after normalization - Mr Robot`() {
            assertEquals(1.0f, scrobbler.fuzzyScore("MR. ROBOT", "mr robot"))
        }

        @Test
        fun `prefix match returns 0_95`() {
            assertEquals(0.95f, scrobbler.fuzzyScore("Breaking Bad", "Breaking Bad Season"))
        }

        @Test
        fun `completely different strings return low score`() {
            val score = scrobbler.fuzzyScore("Breaking Bad", "Cooking Show")
            assertTrue(score < 0.50f)
        }

        @Test
        fun `empty string returns 0_0`() {
            assertEquals(0f, scrobbler.fuzzyScore("", "test"))
        }

        @Test
        fun `both empty strings return 0_0`() {
            assertEquals(0f, scrobbler.fuzzyScore("", ""))
        }

        @Test
        fun `similar strings - Breaking Bad vs Breaking Baad`() {
            assertTrue(scrobbler.fuzzyScore("Breaking Bad", "Breaking Baad") >= 0.70f)
        }

        @Test
        fun `similar strings - Stranger Things vs Stranger Thing`() {
            assertTrue(scrobbler.fuzzyScore("Stranger Things", "Stranger Thing") >= 0.70f)
        }

        @Test
        fun `similar strings - Game of Thrones vs Game of Throne`() {
            assertTrue(scrobbler.fuzzyScore("Game of Thrones", "Game of Throne") >= 0.70f)
        }

        @Test
        fun `score is between 0 and 1`() {
            val score = scrobbler.fuzzyScore("Show A", "Show B")
            assertTrue(score in 0f..1f)
        }
    }

    // ── autoScrobble() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("autoScrobble()")
    inner class AutoScrobbleTest {

        private val testShow = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1))
        private val testEpisode = TraktEpisode(season = 1, number = 1)
        private val testCandidate = ScrobbleCandidate(
            "com.netflix", "Breaking Bad S01E01", 0.95f, testShow, testEpisode
        )

        private fun mockScrobbleResponse() {
            coEvery { traktApi.scrobbleStart(any(), any()) } returns ScrobbleResponse(
                id = 1L, action = "start", progress = 0f,
                show = testShow, episode = testEpisode
            )
        }

        @Test
        fun `sends scrobble start to Trakt API for single user`() = runTest {
            coEvery { tvTokenCache.getAllTokens() } returns listOf(
                TvTokenCache.PhoneToken("device-1", "token-1")
            )
            mockScrobbleResponse()

            scrobbler.autoScrobble(testCandidate)

            coVerify {
                traktApi.scrobbleStart(
                    "Bearer token-1",
                    match<ScrobbleBody> { it.show == testShow && it.episode == testEpisode && it.progress == 0f }
                )
            }
        }

        @Test
        fun `scrobbles for each connected user when multiple phones present`() = runTest {
            coEvery { tvTokenCache.getAllTokens() } returns listOf(
                TvTokenCache.PhoneToken("device-1", "token-1"),
                TvTokenCache.PhoneToken("device-2", "token-2")
            )
            mockScrobbleResponse()

            scrobbler.autoScrobble(testCandidate)

            coVerify { traktApi.scrobbleStart("Bearer token-1", any()) }
            coVerify { traktApi.scrobbleStart("Bearer token-2", any()) }
            coVerify(exactly = 2) { traktApi.scrobbleStart(any(), any()) }
        }

        @Test
        fun `skips when no tokens available`() = runTest {
            coEvery { tvTokenCache.getAllTokens() } returns emptyList()

            scrobbler.autoScrobble(testCandidate)

            coVerify(exactly = 0) { traktApi.scrobbleStart(any(), any()) }
        }

        @Test
        fun `skips when no matched show`() = runTest {
            coEvery { tvTokenCache.getAllTokens() } returns listOf(
                TvTokenCache.PhoneToken("device-1", "token-1")
            )

            val candidate = ScrobbleCandidate("pkg", "Title", 0.95f, null, testEpisode)
            scrobbler.autoScrobble(candidate)

            coVerify(exactly = 0) { traktApi.scrobbleStart(any(), any()) }
        }

        @Test
        fun `skips when no matched episode`() = runTest {
            coEvery { tvTokenCache.getAllTokens() } returns listOf(
                TvTokenCache.PhoneToken("device-1", "token-1")
            )

            val candidate = ScrobbleCandidate("pkg", "Title", 0.95f, testShow, null)
            scrobbler.autoScrobble(candidate)

            coVerify(exactly = 0) { traktApi.scrobbleStart(any(), any()) }
        }

        @Test
        fun `handles API exception for one user without blocking others`() = runTest {
            coEvery { tvTokenCache.getAllTokens() } returns listOf(
                TvTokenCache.PhoneToken("device-1", "token-1"),
                TvTokenCache.PhoneToken("device-2", "token-2")
            )
            coEvery { traktApi.scrobbleStart("Bearer token-1", any()) } throws RuntimeException("API Error")
            coEvery { traktApi.scrobbleStart("Bearer token-2", any()) } returns ScrobbleResponse(
                id = 2L, action = "start", progress = 0f, show = testShow, episode = testEpisode
            )

            // Should not throw — failure for one user is isolated
            scrobbler.autoScrobble(testCandidate)

            coVerify { traktApi.scrobbleStart("Bearer token-1", any()) }
            coVerify { traktApi.scrobbleStart("Bearer token-2", any()) }
        }

        @Test
        fun `handles single API exception gracefully`() = runTest {
            coEvery { tvTokenCache.getAllTokens() } returns listOf(
                TvTokenCache.PhoneToken("device-1", "token-1")
            )
            coEvery { traktApi.scrobbleStart(any(), any()) } throws RuntimeException("API Error")

            // Should not throw
            scrobbler.autoScrobble(testCandidate)
        }
    }

    // ── Levenshtein distance via fuzzyScore ──────────────────────────────────

    @Nested
    @DisplayName("Levenshtein distance via fuzzyScore")
    inner class LevenshteinTest {

        @Test
        fun `identical strings have perfect score`() {
            assertEquals(1.0f, scrobbler.fuzzyScore("hello", "hello"))
        }

        @Test
        fun `single character difference reduces score`() {
            val score = scrobbler.fuzzyScore("hello", "hallo")
            assertTrue(score > 0.7f)
            assertTrue(score < 1.0f)
        }

        @Test
        fun `completely different strings have low score`() {
            val score = scrobbler.fuzzyScore("abcdef", "zyxwvu")
            assertTrue(score < 0.3f)
        }
    }
}
