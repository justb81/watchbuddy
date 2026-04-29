package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.model.TmdbTvSearchResponse
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for issue #468: unknown-show detection and forced overlay.
 *
 * An "unknown show" is a candidate whose matchedShow has no Trakt ID but does have a TMDB ID —
 * the result of the TMDB fallback cascade for a show not yet in the user's library.
 */
@DisplayName("MediaSessionScrobbler — Unknown-show detection (#468)")
class MediaSessionScrobblerUnknownShowTest {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    private val knownShow = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1, tmdb = 100))
    private val testEpisode = TraktEpisode(season = 1, number = 1)

    private val strangerThingsTmdb = TmdbShow(
        id = 66732,
        name = "Stranger Things",
        first_air_date = "2016-07-15",
    )
    private val strangerThingsSearchResult = TmdbTvSearchResponse(
        results = listOf(strangerThingsTmdb),
    )

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(
            context, tmdbApiService, watchedShowSource, scrobbleDispatcher, NoOpTitleExtractor
        )
        coEvery { scrobbleDispatcher.dispatchStart(any(), any(), any()) } just runs
        coEvery { scrobbleDispatcher.dispatchStop(any(), any(), any()) } just runs
        coEvery { watchedShowSource.getCachedShows() } returns emptyList()
        coEvery { watchedShowSource.getTmdbApiKey() } returns null
    }

    // ── ScrobbleCandidate.isUnknownShow() ─────────────────────────────────────

    @Nested
    @DisplayName("ScrobbleCandidate.isUnknownShow()")
    inner class IsUnknownShowTest {

        @Test
        fun `returns true when show has tmdb id but no trakt id`() {
            val candidate = ScrobbleCandidate(
                packageName = "com.plex.android",
                mediaTitle = "Stranger Things",
                confidence = 0.95f,
                matchedShow = TraktShow("Stranger Things", 2016, TraktIds(tmdb = 66732)),
                matchedEpisode = testEpisode,
            )
            assertTrue(candidate.isUnknownShow())
        }

        @Test
        fun `returns false when show has both trakt and tmdb ids`() {
            val candidate = ScrobbleCandidate(
                packageName = "com.netflix.ninja",
                mediaTitle = "Breaking Bad",
                confidence = 0.95f,
                matchedShow = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1, tmdb = 100)),
                matchedEpisode = testEpisode,
            )
            assertFalse(candidate.isUnknownShow())
        }

        @Test
        fun `returns false when show has only trakt id`() {
            val candidate = ScrobbleCandidate(
                packageName = "com.netflix.ninja",
                mediaTitle = "Breaking Bad",
                confidence = 0.95f,
                matchedShow = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1)),
                matchedEpisode = testEpisode,
            )
            assertFalse(candidate.isUnknownShow())
        }

        @Test
        fun `returns false when matchedShow is null`() {
            val candidate = ScrobbleCandidate(
                packageName = "com.netflix.ninja",
                mediaTitle = "Breaking Bad",
                confidence = 0.50f,
                matchedShow = null,
                matchedEpisode = null,
            )
            assertFalse(candidate.isUnknownShow())
        }

        @Test
        fun `returns false when show has no ids at all`() {
            val candidate = ScrobbleCandidate(
                packageName = "com.netflix.ninja",
                mediaTitle = "Something",
                confidence = 0.80f,
                matchedShow = TraktShow("Something", null, TraktIds()),
                matchedEpisode = testEpisode,
            )
            assertFalse(candidate.isUnknownShow())
        }
    }

    // ── processPlayingMedia routing ───────────────────────────────────────────

    @Nested
    @DisplayName("processPlayingMedia — unknown show routes to overlay")
    inner class UnknownShowRoutingTest {

        @Test
        fun `unknown show at AUTO_SCROBBLE_THRESHOLD does not call dispatchStart`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns emptyList()
            coEvery { watchedShowSource.getTmdbApiKey() } returns "tmdb-key"
            coEvery { tmdbApiService.searchTv(any(), "tmdb-key") } returns strangerThingsSearchResult

            val snapshot = snapshotOf("com.plex.android", "title" to "Stranger Things S01E01")
            scrobbler.processPlayingMedia(snapshot, "com.plex.android:Stranger Things S01E01", progress = 50f)

            // TMDB-only candidate (unknown show) must NOT auto-scrobble regardless of confidence.
            coVerify(exactly = 0) { scrobbleDispatcher.dispatchStart(any(), any(), any()) }
        }

        @Test
        fun `known show at AUTO_SCROBBLE_THRESHOLD auto-scrobbles as before`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(TraktWatchedEntry(show = knownShow))
            coEvery { watchedShowSource.getShowHint(any()) } returns null

            val snapshot = snapshotOf("com.netflix.ninja", "title" to "Breaking Bad S01E01")
            scrobbler.processPlayingMedia(snapshot, "com.netflix.ninja:Breaking Bad S01E01", progress = 50f)

            // Known show (has Trakt ID) at high confidence must still auto-scrobble.
            coVerify(exactly = 1) { scrobbleDispatcher.dispatchStart(any(), any(), any()) }
        }

        @Test
        fun `unknown show at OVERLAY_THRESHOLD sets lastCandidate with autoScrobbled=false`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns emptyList()
            coEvery { watchedShowSource.getTmdbApiKey() } returns "tmdb-key"
            coEvery { tmdbApiService.searchTv(any(), "tmdb-key") } returns strangerThingsSearchResult

            val snapshot = snapshotOf("com.plex.android", "title" to "Stranger Things S01E01")
            scrobbler.processPlayingMedia(snapshot, "com.plex.android:Stranger Things S01E01", progress = 50f)

            val last = scrobbler.lastCandidate.value
            assertNotNull(last)
            assertFalse(last!!.autoScrobbled, "Unknown show must surface via overlay, not auto-scrobble")
            assertTrue(last.candidate.isUnknownShow(), "Candidate must be an unknown show")
        }

        @Test
        fun `unknown show below OVERLAY_THRESHOLD is ignored`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns emptyList()
            coEvery { watchedShowSource.getTmdbApiKey() } returns "tmdb-key"
            // Return a TMDB result with a very low fuzzy score.
            coEvery { tmdbApiService.searchTv(any(), "tmdb-key") } returns TmdbTvSearchResponse(
                results = listOf(TmdbShow(id = 12345, name = "ZZZTotallyDifferentXXX"))
            )

            val snapshot = snapshotOf("com.plex.android", "title" to "Stranger Things S01E01")
            scrobbler.processPlayingMedia(snapshot, "com.plex.android:Stranger Things S01E01", progress = 50f)

            coVerify(exactly = 0) { scrobbleDispatcher.dispatchStart(any(), any(), any()) }
        }
    }

    // ── matchSnapshot — TMDB fallback path ───────────────────────────────────

    @Nested
    @DisplayName("matchSnapshot — TMDB fallback produces isUnknownShow() candidate")
    inner class TmdbFallbackCandidateTest {

        @Test
        fun `TMDB fallback with empty library produces TMDB-only candidate`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns emptyList()
            coEvery { watchedShowSource.getTmdbApiKey() } returns "tmdb-key"
            coEvery { tmdbApiService.searchTv("Stranger Things", "tmdb-key") } returns
                strangerThingsSearchResult

            val snapshot = snapshotOf("com.plex.android", "title" to "Stranger Things S01E01")
            val candidate = scrobbler.matchSnapshot(snapshot)

            assertNotNull(candidate)
            assertTrue(candidate!!.isUnknownShow(), "TMDB-fallback candidate must be an unknown show")
        }

        @Test
        fun `library match produces known show candidate — isUnknownShow returns false`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(TraktWatchedEntry(show = knownShow))
            coEvery { watchedShowSource.getShowHint(any()) } returns null

            val snapshot = snapshotOf("com.netflix.ninja", "title" to "Breaking Bad S01E01")
            val candidate = scrobbler.matchSnapshot(snapshot)

            assertNotNull(candidate)
            assertFalse(candidate!!.isUnknownShow(), "Library-matched candidate must not be an unknown show")
        }
    }
}
