package com.justb81.watchbuddy.core.scrobbler

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
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

@DisplayName("MediaSessionScrobbler — Lifecycle & Scrobble dispatch")
class MediaSessionScrobblerLifecycleTest {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    private val testShow = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1))
    private val testEpisode = TraktEpisode(season = 1, number = 1)
    private val testCandidate = ScrobbleCandidate(
        "com.netflix", "Breaking Bad S01E01", 0.95f, testShow, testEpisode
    )

    @BeforeEach
    fun setUp() {
        val mockSessionManager = mockk<MediaSessionManager>(relaxed = true)
        every { context.getSystemService(Context.MEDIA_SESSION_SERVICE) } returns mockSessionManager
        every { mockSessionManager.getActiveSessions(any()) } returns emptyList()
        scrobbler = MediaSessionScrobbler(context, tmdbApiService, watchedShowSource, scrobbleDispatcher)
    }

    // ── startListening / stopListening ────────────────────────────────────────

    @Nested
    @DisplayName("startListening + stopListening")
    inner class ListeningLifecycleTest {

        @Test
        fun `startListening does not throw`() {
            val component = mockk<ComponentName>()
            scrobbler.startListening(component)
            scrobbler.stopListening()
        }

        @Test
        fun `stopListening is idempotent when not started`() {
            scrobbler.stopListening()
            scrobbler.stopListening()
        }

        @Test
        fun `multiple startListening calls replace previous polling job`() {
            val component = mockk<ComponentName>()
            scrobbler.startListening(component)
            scrobbler.startListening(component)
            scrobbler.stopListening()
        }
    }

    // ── autoScrobble() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("autoScrobble()")
    inner class AutoScrobbleTest {

        @BeforeEach
        fun setUpDispatcher() {
            coEvery { scrobbleDispatcher.dispatchStart(any(), any(), any()) } just runs
        }

        @Test
        fun `calls dispatchStart with show and episode`() = runTest {
            scrobbler.autoScrobble(testCandidate)

            coVerify { scrobbleDispatcher.dispatchStart(testShow, testEpisode, 0f) }
        }

        @Test
        fun `passes explicit progress to dispatcher`() = runTest {
            scrobbler.autoScrobble(testCandidate, progress = 55f)

            coVerify { scrobbleDispatcher.dispatchStart(testShow, testEpisode, 55f) }
        }

        @Test
        fun `uses 0f when progress is null`() = runTest {
            scrobbler.autoScrobble(testCandidate, progress = null)

            coVerify { scrobbleDispatcher.dispatchStart(testShow, testEpisode, 0f) }
        }

        @Test
        fun `skips dispatch when matchedShow is null`() = runTest {
            val noShow = testCandidate.copy(matchedShow = null)
            scrobbler.autoScrobble(noShow)

            coVerify(exactly = 0) { scrobbleDispatcher.dispatchStart(any(), any(), any()) }
        }

        @Test
        fun `skips dispatch when matchedEpisode is null`() = runTest {
            val noEpisode = testCandidate.copy(matchedEpisode = null)
            scrobbler.autoScrobble(noEpisode)

            coVerify(exactly = 0) { scrobbleDispatcher.dispatchStart(any(), any(), any()) }
        }
    }

    // ── handleScrobblePause() ────────────────────────────────────────────────

    @Nested
    @DisplayName("handleScrobblePause()")
    inner class ScrobblePauseTest {

        private suspend fun primeScrobbling() {
            coEvery { scrobbleDispatcher.dispatchStart(any(), any(), any()) } just runs
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = testShow))
            coEvery { watchedShowSource.getTmdbApiKey() } returns null
            scrobbler.autoScrobble(testCandidate)
        }

        @Test
        fun `calls dispatchPause with explicit progress`() = runTest {
            coEvery { scrobbleDispatcher.dispatchPause(any(), any(), any()) } just runs
            primeScrobbling()

            scrobbler.handleScrobblePause("Breaking Bad S01E01", progress = 42f)

            coVerify { scrobbleDispatcher.dispatchPause(any(), any(), 42f) }
        }

        @Test
        fun `defaults to 50 when progress null`() = runTest {
            coEvery { scrobbleDispatcher.dispatchPause(any(), any(), any()) } just runs
            primeScrobbling()

            scrobbler.handleScrobblePause("Breaking Bad S01E01", progress = null)

            coVerify { scrobbleDispatcher.dispatchPause(any(), any(), 50f) }
        }

        @Test
        fun `skips when title does not match currently scrobbling`() = runTest {
            scrobbler.handleScrobblePause("Other Show", progress = 50f)

            coVerify(exactly = 0) { scrobbleDispatcher.dispatchPause(any(), any(), any()) }
        }
    }

    // ── handleScrobbleStop() ─────────────────────────────────────────────────

    @Nested
    @DisplayName("handleScrobbleStop()")
    inner class ScrobbleStopTest {

        private suspend fun primeScrobbling() {
            coEvery { scrobbleDispatcher.dispatchStart(any(), any(), any()) } just runs
            coEvery { watchedShowSource.getCachedShows() } returns
                listOf(TraktWatchedEntry(show = testShow))
            coEvery { watchedShowSource.getTmdbApiKey() } returns null
            scrobbler.autoScrobble(testCandidate)
        }

        @Test
        fun `calls dispatchStop with real progress`() = runTest {
            coEvery { scrobbleDispatcher.dispatchStop(any(), any(), any()) } just runs
            primeScrobbling()

            scrobbler.handleScrobbleStop("Breaking Bad S01E01", progress = 80f)

            coVerify { scrobbleDispatcher.dispatchStop(any(), any(), 80f) }
        }

        @Test
        fun `skips stop when progress is null`() = runTest {
            primeScrobbling()

            scrobbler.handleScrobbleStop("Breaking Bad S01E01", progress = null)

            coVerify(exactly = 0) { scrobbleDispatcher.dispatchStop(any(), any(), any()) }
        }

        @Test
        fun `skips when title does not match currently scrobbling`() = runTest {
            scrobbler.handleScrobbleStop("Other Show", progress = 90f)

            coVerify(exactly = 0) { scrobbleDispatcher.dispatchStop(any(), any(), any()) }
        }
    }

    // ── pendingConfirmation flow ─────────────────────────────────────────────

    @Nested
    @DisplayName("pendingConfirmation flow")
    inner class PendingConfirmationTest {

        @Test
        fun `high confidence candidate triggers dispatchStart not pendingConfirmation`() = runTest {
            coEvery { scrobbleDispatcher.dispatchStart(any(), any(), any()) } just runs

            scrobbler.autoScrobble(testCandidate) // confidence 0.95 → auto

            coVerify { scrobbleDispatcher.dispatchStart(any(), any(), any()) }
        }
    }
}
