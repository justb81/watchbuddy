package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression tests for issue #403: handleScrobbleStop silently dropped stop events when
 * duration/position was unavailable (e.g. streaming apps that report duration=0 on the
 * last tick during credits or livestream segmentation).
 */
@DisplayName("MediaSessionScrobbler — Stop with unavailable progress (#403)")
class MediaSessionScrobblerStopTest {

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
    private val testSnapshot = snapshotOf("com.netflix", "title" to "Breaking Bad S01E01")
    private val testSessionKey = "com.netflix:Breaking Bad S01E01"

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(
            context, tmdbApiService, watchedShowSource, scrobbleDispatcher, NoOpTitleExtractor
        )
        coEvery { scrobbleDispatcher.dispatchStart(any(), any(), any()) } just runs
        coEvery { scrobbleDispatcher.dispatchStop(any(), any(), any()) } just runs
        coEvery { watchedShowSource.getCachedShows() } returns
            listOf(TraktWatchedEntry(show = testShow))
        coEvery { watchedShowSource.getTmdbApiKey() } returns null
    }

    private suspend fun primeScrobbling() {
        scrobbler.autoScrobble(testCandidate)
    }

    @Test
    fun `dispatches stop with 100f when progress is null — duration=0 on last tick`() = runTest {
        primeScrobbling()

        scrobbler.handleScrobbleStop(testSnapshot, testSessionKey, progress = null)

        coVerify { scrobbleDispatcher.dispatchStop(testShow, testEpisode, 100f) }
    }

    @Test
    fun `clears currentlyScrobbling after null-progress stop so next play can scrobble`() = runTest {
        primeScrobbling()

        scrobbler.handleScrobbleStop(testSnapshot, testSessionKey, progress = null)

        scrobbler.autoScrobble(testCandidate)
        coVerify { scrobbleDispatcher.dispatchStart(testShow, testEpisode, any()) }
    }

    @Test
    fun `dispatches stop with explicit progress when available`() = runTest {
        primeScrobbling()

        scrobbler.handleScrobbleStop(testSnapshot, testSessionKey, progress = 85f)

        coVerify { scrobbleDispatcher.dispatchStop(testShow, testEpisode, 85f) }
    }

    @Test
    fun `null-progress stop emits DiagnosticLog WARN with session key`() = runTest {
        primeScrobbling()
        val beforeMs = System.currentTimeMillis()

        scrobbler.handleScrobbleStop(testSnapshot, testSessionKey, progress = null)

        val warnEntries = DiagnosticLog.snapshot().filter { entry ->
            entry.level == DiagnosticLog.Level.WARN &&
                entry.message.contains(testSessionKey) &&
                entry.message.contains("100%") &&
                entry.timestampMs >= beforeMs
        }
        assertTrue(warnEntries.isNotEmpty(), "Expected a WARN breadcrumb mentioning the session key and 100%")
    }

    @Test
    fun `no dispatch when session key does not match currently scrobbling`() = runTest {
        primeScrobbling()
        val otherKey = "com.netflix:Other Show"
        val otherSnapshot = snapshotOf("com.netflix", "title" to "Other Show")

        scrobbler.handleScrobbleStop(otherSnapshot, otherKey, progress = null)

        coVerify(exactly = 0) { scrobbleDispatcher.dispatchStop(any(), any(), any()) }
    }

    @Test
    fun `null-progress stop still skips dispatch when no library match`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns emptyList()
        primeScrobbling()

        scrobbler.handleScrobbleStop(testSnapshot, testSessionKey, progress = null)

        coVerify(exactly = 0) { scrobbleDispatcher.dispatchStop(any(), any(), any()) }
    }

    @Test
    fun `progress of 0 is not treated as null — dispatches with 0f`() = runTest {
        primeScrobbling()

        scrobbler.handleScrobbleStop(testSnapshot, testSessionKey, progress = 0f)

        coVerify { scrobbleDispatcher.dispatchStop(testShow, testEpisode, 0f) }
    }

    @Test
    fun `null-progress stop does not double-dispatch when called twice`() = runTest {
        primeScrobbling()

        scrobbler.handleScrobbleStop(testSnapshot, testSessionKey, progress = null)
        scrobbler.handleScrobbleStop(testSnapshot, testSessionKey, progress = null)

        coVerify(exactly = 1) { scrobbleDispatcher.dispatchStop(any(), any(), any()) }
    }
}
