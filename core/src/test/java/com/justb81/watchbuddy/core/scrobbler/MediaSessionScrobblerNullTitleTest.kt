package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression tests for the null-`METADATA_KEY_TITLE` scrobble cascade. Previously
 * the polling loop early-returned when TITLE was null, dropping every Plex,
 * Jellyfin, and Netflix-skin session that ships the show in another field. The
 * fix identifies sessions by the first non-blank `candidateStrings()` entry
 * instead of raw title, so pause/stop dedup + vanished-stop reconciliation keep
 * working when TITLE is unavailable.
 *
 * Also covers the `lastObservedSession` diagnostics StateFlow that surfaces the
 * full `MediaMetadataSnapshot` in the TV diagnostics view regardless of whether
 * a scrobble fired — so the user can see exactly what each streaming app is
 * publishing, rather than just a single "title='(null)'" row.
 */
@DisplayName("MediaSessionScrobbler — null TITLE cascade + lastObservedSession")
class MediaSessionScrobblerNullTitleTest {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    private val breakingBadShow = TraktShow(
        title = "Breaking Bad",
        year = 2008,
        ids = TraktIds(trakt = 1, tmdb = 1396),
    )
    private val breakingBadEntry = TraktWatchedEntry(show = breakingBadShow)

    // Plex: TITLE = episode name, ALBUM_ARTIST = show, DISPLAY_SUBTITLE = S##E##
    private val plexPlayingSnapshot = MediaMetadataSnapshot(
        packageName = "com.plexapp.android",
        title = null,
        albumArtist = "Breaking Bad",
        displaySubtitle = "S01E01",
    )
    // sessionKey = "${packageName}:${candidateStrings().first()}"
    // candidateStrings priority: albumArtist > album > artist > displayTitle > displaySubtitle > title > displayDescription
    private val plexSessionKey = "com.plexapp.android:Breaking Bad"

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(
            context, tmdbApiService, watchedShowSource, scrobbleDispatcher, NoOpTitleExtractor,
        )
        coEvery { watchedShowSource.getTmdbApiKey() } returns null
        coEvery { watchedShowSource.getShowHint(any()) } returns null
        coEvery { scrobbleDispatcher.dispatchStart(any(), any(), any()) } just runs
        coEvery { scrobbleDispatcher.dispatchPause(any(), any(), any()) } just runs
        coEvery { scrobbleDispatcher.dispatchStop(any(), any(), any()) } just runs
    }

    // ── lastObservedSession (diagnostics exposure) ────────────────────────────

    @Test
    fun `publishObservedSession populates lastObservedSession StateFlow`() {
        assertNull(scrobbler.lastObservedSession.value)

        scrobbler.publishObservedSession(
            snapshot = plexPlayingSnapshot,
            playbackState = 3,
            positionMs = 123L,
            durationMs = 2600000L,
        )

        val observed = scrobbler.lastObservedSession.value
        assertNotNull(observed)
        assertEquals(plexPlayingSnapshot, observed!!.snapshot)
        assertEquals(3, observed.playbackState)
        assertEquals(123L, observed.positionMs)
        assertEquals(2600000L, observed.durationMs)
        assertTrue(observed.observedAtMs > 0L)
    }

    @Test
    fun `publishObservedSession populates even when every string field is null`() {
        val emptySnapshot = MediaMetadataSnapshot(packageName = "com.example.splash")

        scrobbler.publishObservedSession(
            snapshot = emptySnapshot,
            playbackState = -1,
            positionMs = -1L,
            durationMs = -1L,
        )

        val observed = scrobbler.lastObservedSession.value
        assertNotNull(observed)
        assertEquals(emptySnapshot, observed!!.snapshot)
        assertTrue(emptySnapshot.candidateStrings().isEmpty())
    }

    @Test
    fun `stopListening clears lastObservedSession`() {
        scrobbler.publishObservedSession(plexPlayingSnapshot, 3, 0L, 0L)
        assertNotNull(scrobbler.lastObservedSession.value)

        scrobbler.stopListening()

        assertNull(scrobbler.lastObservedSession.value)
    }

    // ── Pause/stop dedup keyed by sessionKey, not raw title ───────────────────

    @Test
    fun `Plex-shape pause deduped by session key when TITLE is null`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)
        val candidate = scrobbler.matchSnapshot(plexPlayingSnapshot)
        assertNotNull(candidate)
        scrobbler.autoScrobble(candidate!!, progress = 10f, sessionKey = plexSessionKey)
        clearMocks(scrobbleDispatcher, answers = false)
        coEvery { scrobbleDispatcher.dispatchPause(any(), any(), any()) } just runs

        // Same session key → dispatchPause fires.
        scrobbler.handleScrobblePause(plexPlayingSnapshot, plexSessionKey, progress = 42f)

        coVerify { scrobbleDispatcher.dispatchPause(breakingBadShow, any(), 42f) }
    }

    @Test
    fun `Plex-shape stop deduped by session key when TITLE is null`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)
        val candidate = scrobbler.matchSnapshot(plexPlayingSnapshot)
        assertNotNull(candidate)
        scrobbler.autoScrobble(candidate!!, progress = 10f, sessionKey = plexSessionKey)
        clearMocks(scrobbleDispatcher, answers = false)
        coEvery { scrobbleDispatcher.dispatchStop(any(), any(), any()) } just runs

        scrobbler.handleScrobbleStop(plexPlayingSnapshot, plexSessionKey, progress = 88f)

        coVerify { scrobbleDispatcher.dispatchStop(breakingBadShow, any(), 88f) }
    }

    @Test
    fun `pause with different session key does not fire even if snapshot matches cache`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)
        val candidate = scrobbler.matchSnapshot(plexPlayingSnapshot)
        assertNotNull(candidate)
        scrobbler.autoScrobble(candidate!!, progress = 10f, sessionKey = plexSessionKey)

        scrobbler.handleScrobblePause(
            plexPlayingSnapshot,
            "some.other.app:Different Show",
            progress = 42f,
        )

        coVerify(exactly = 0) { scrobbleDispatcher.dispatchPause(any(), any(), any()) }
    }

    // ── Vanished-stop uses the stashed snapshot (multi-field cascade) ──────────

    @Test
    fun `vanished session with stashed snapshot dispatches stop via matchSnapshot`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)
        val candidate = ScrobbleCandidate(
            packageName = plexPlayingSnapshot.packageName,
            mediaTitle = "Breaking Bad",
            confidence = 0.95f,
            matchedShow = breakingBadShow,
            matchedEpisode = TraktEpisode(season = 1, number = 1),
        )
        scrobbler.autoScrobble(candidate, progress = 10f, sessionKey = plexSessionKey)
        // Poll once — stash the snapshot alongside the progress so vanish can re-match.
        scrobbler.recordProgress(plexSessionKey, plexPlayingSnapshot, 55f)

        scrobbler.reconcileVanished(liveKeys = emptySet())

        coVerify { scrobbleDispatcher.dispatchStop(breakingBadShow, any(), 55f) }
    }

    // ── sessionKey identity derivation ────────────────────────────────────────

    @Test
    fun `sessionKey for snapshot with empty candidate strings is null`() {
        val emptySnapshot = MediaMetadataSnapshot(packageName = "com.example.splash")
        with(scrobbler) {
            assertNull(emptySnapshot.sessionKey())
        }
    }

    @Test
    fun `sessionKey derives from highest-priority non-blank field`() {
        val plexSnapshot = MediaMetadataSnapshot(
            packageName = "com.plexapp.android",
            title = "Pilot",
            albumArtist = "Breaking Bad",
        )
        // candidateStrings order: albumArtist > album > artist > displayTitle > displaySubtitle > title > displayDescription
        with(scrobbler) {
            assertEquals("com.plexapp.android:Breaking Bad", plexSnapshot.sessionKey())
        }
    }
}
