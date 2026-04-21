package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression tests for issue #402: some streaming apps destroy their MediaSession
 * entirely when playback ends instead of transitioning through STATE_STOPPED.
 * When that happens `getActiveSessions` just stops returning the session — no
 * STATE_STOPPED is ever observed — so the dedup key `currentlyScrobbling` used
 * to stay pinned forever, silently blocking every future scrobble of the same title.
 *
 * The fix reconciles `currentlyScrobbling` against the set of titles currently
 * reported as live on each poll, dispatches an implicit stop with the last
 * captured progress, and clears local state so the next play can scrobble again.
 */
@DisplayName("MediaSessionScrobbler — Session vanish reconciliation (#402)")
class MediaSessionScrobblerVanishTest {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    private val testShow = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1))
    private val testEpisode = TraktEpisode(season = 1, number = 1)
    private val testTitle = "Breaking Bad S01E01"
    private val testCandidate = ScrobbleCandidate(
        "com.netflix", testTitle, 0.95f, testShow, testEpisode
    )

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(context, tmdbApiService, watchedShowSource, scrobbleDispatcher)
        coEvery { scrobbleDispatcher.dispatchStart(any(), any(), any()) } just runs
        coEvery { scrobbleDispatcher.dispatchStop(any(), any(), any()) } just runs
        coEvery { watchedShowSource.getCachedShows() } returns
            listOf(TraktWatchedEntry(show = testShow))
        coEvery { watchedShowSource.getTmdbApiKey() } returns null
        coEvery { watchedShowSource.getShowHint(any()) } returns null
    }

    @Test
    fun `vanished session dispatches stop with last captured progress`() = runTest {
        scrobbler.autoScrobble(testCandidate)
        scrobbler.recordProgress(testTitle, 42f)

        scrobbler.reconcileVanished(liveTitles = emptySet())

        coVerify { scrobbleDispatcher.dispatchStop(testShow, testEpisode, 42f) }
    }

    @Test
    fun `subsequent scrobble of same title proceeds after reconciliation`() = runTest {
        scrobbler.autoScrobble(testCandidate)
        scrobbler.recordProgress(testTitle, 42f)
        scrobbler.reconcileVanished(liveTitles = emptySet())
        clearMocks(scrobbleDispatcher, answers = false)

        scrobbler.autoScrobble(testCandidate)

        coVerify { scrobbleDispatcher.dispatchStart(testShow, testEpisode, any()) }
    }

    @Test
    fun `live title still present does not trigger implicit stop`() = runTest {
        scrobbler.autoScrobble(testCandidate)
        scrobbler.recordProgress(testTitle, 42f)

        scrobbler.reconcileVanished(liveTitles = setOf(testTitle))

        coVerify(exactly = 0) { scrobbleDispatcher.dispatchStop(any(), any(), any()) }
    }

    @Test
    fun `no-op when nothing is currently scrobbling`() = runTest {
        scrobbler.reconcileVanished(liveTitles = emptySet())

        coVerify(exactly = 0) { scrobbleDispatcher.dispatchStop(any(), any(), any()) }
    }

    @Test
    fun `vanished session with no captured progress skips dispatch but still clears state`() = runTest {
        scrobbler.autoScrobble(testCandidate)
        // Intentionally no recordProgress() — stream ended before any progress-bearing poll.

        scrobbler.reconcileVanished(liveTitles = emptySet())

        coVerify(exactly = 0) { scrobbleDispatcher.dispatchStop(any(), any(), any()) }
        // Follow-up start must no longer be blocked by the dedup guard.
        clearMocks(scrobbleDispatcher, answers = false)
        scrobbler.autoScrobble(testCandidate)
        coVerify { scrobbleDispatcher.dispatchStart(testShow, testEpisode, any()) }
    }

    @Test
    fun `stopListening clears scrobble state`() = runTest {
        scrobbler.autoScrobble(testCandidate)
        scrobbler.recordProgress(testTitle, 42f)

        scrobbler.stopListening()

        // After stop, reconciliation is a no-op because state is cleared.
        scrobbler.reconcileVanished(liveTitles = emptySet())
        coVerify(exactly = 0) { scrobbleDispatcher.dispatchStop(any(), any(), any()) }
    }
}
