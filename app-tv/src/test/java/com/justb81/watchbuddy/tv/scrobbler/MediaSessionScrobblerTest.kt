package com.justb81.watchbuddy.tv.scrobbler

import android.content.Context
import android.media.session.MediaSessionManager
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler
import com.justb81.watchbuddy.core.scrobbler.ScrobbleDispatcher
import com.justb81.watchbuddy.core.scrobbler.WatchedShowSource
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * TV-side integration smoke-tests for [MediaSessionScrobbler].
 *
 * Internal functions (normalize, fuzzyScore, computeProgress, matchTitle,
 * handleScrobblePause, handleScrobbleStop) are internal to the :core module
 * and therefore not visible here. They are covered exhaustively by
 * MediaSessionScrobblerFuzzyTest and MediaSessionScrobblerLifecycleTest in :core.
 *
 * These tests validate that the public API delegates correctly to [ScrobbleDispatcher],
 * which is the TV-specific concern (fan-out to connected phones).
 */
@DisplayName("MediaSessionScrobbler — TV integration")
class MediaSessionScrobblerTest {

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

    // ── autoScrobble() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("autoScrobble()")
    inner class AutoScrobbleTest {

        @BeforeEach
        fun setUpDispatcher() {
            coEvery { scrobbleDispatcher.dispatchStart(any(), any(), any()) } just runs
        }

        @Test
        fun `calls dispatchStart on scrobble dispatcher`() = runTest {
            scrobbler.autoScrobble(testCandidate)

            coVerify {
                scrobbleDispatcher.dispatchStart(testShow, testEpisode, 0f)
            }
        }

        @Test
        fun `forwards explicit progress value`() = runTest {
            scrobbler.autoScrobble(testCandidate, progress = 42.5f)

            coVerify {
                scrobbleDispatcher.dispatchStart(testShow, testEpisode, 42.5f)
            }
        }

        @Test
        fun `falls back to 0 when progress is null`() = runTest {
            scrobbler.autoScrobble(testCandidate, progress = null)

            coVerify {
                scrobbleDispatcher.dispatchStart(testShow, testEpisode, 0f)
            }
        }

        @Test
        fun `skips when no matched show`() = runTest {
            val candidate = ScrobbleCandidate("pkg", "Title", 0.95f, null, testEpisode)
            scrobbler.autoScrobble(candidate)

            coVerify(exactly = 0) { scrobbleDispatcher.dispatchStart(any(), any(), any()) }
        }

        @Test
        fun `skips when no matched episode`() = runTest {
            val candidate = ScrobbleCandidate("pkg", "Title", 0.95f, testShow, null)
            scrobbler.autoScrobble(candidate)

            coVerify(exactly = 0) { scrobbleDispatcher.dispatchStart(any(), any(), any()) }
        }
    }

    // ── startListening / stopListening ────────────────────────────────────────

    @Nested
    @DisplayName("Lifecycle")
    inner class LifecycleTest {

        @Test
        fun `startListening and stopListening do not throw`() {
            val component = mockk<android.content.ComponentName>()
            scrobbler.startListening(component)
            scrobbler.stopListening()
        }

        @Test
        fun `stopListening is idempotent when never started`() {
            scrobbler.stopListening()
            scrobbler.stopListening()
        }
    }
}
