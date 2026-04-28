package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import android.media.session.MediaSessionManager
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MediaSessionScrobbler — debugLogMediaSession firehose")
class MediaSessionScrobblerDebugLogTest {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    @BeforeEach
    fun setUp() {
        val mockSessionManager = mockk<MediaSessionManager>(relaxed = true)
        every { context.getSystemService(Context.MEDIA_SESSION_SERVICE) } returns mockSessionManager
        every { mockSessionManager.getActiveSessions(any()) } returns emptyList()
        scrobbler = MediaSessionScrobbler(context, tmdbApiService, watchedShowSource, scrobbleDispatcher, NoOpTitleExtractor)
        DiagnosticLog.clear()
    }

    @AfterEach
    fun tearDown() {
        DiagnosticLog.clear()
    }

    @Test
    fun `does not log session breadcrumb when flag is off`() {
        scrobbler.debugLogMediaSession = false

        scrobbler.logSessionIfDebug(
            snapshot = snapshotOf("com.netflix.ninja", "title" to "Breaking Bad S01E01"),
            state = 3,
            positionMs = 1234L,
            durationMs = 2600000L,
        )

        val sessionEntries = DiagnosticLog.snapshot()
            .filter { it.message.startsWith("session pkg=") }
        assertTrue(sessionEntries.isEmpty())
    }

    @Test
    fun `logs exactly one session breadcrumb per call when flag is on`() {
        scrobbler.debugLogMediaSession = true

        scrobbler.logSessionIfDebug(
            snapshot = snapshotOf("com.netflix.ninja", "title" to "Breaking Bad S01E01"),
            state = 3,
            positionMs = 1234L,
            durationMs = 2600000L,
        )

        val sessionEntries = DiagnosticLog.snapshot()
            .filter { it.message.startsWith("session pkg=") }
        assertEquals(1, sessionEntries.size)
        val entry = sessionEntries.single()
        assertEquals(DiagnosticLog.Level.INFO, entry.level)
        assertTrue(entry.message.contains("pkg=com.netflix.ninja"))
        assertTrue(entry.message.contains("Breaking Bad S01E01"))
        assertTrue(entry.message.contains("state=3"))
        assertTrue(entry.message.contains("pos=1234ms"))
        assertTrue(entry.message.contains("dur=2600000ms"))
    }

    @Test
    fun `renders blank snapshot as no-evidence placeholder`() {
        scrobbler.debugLogMediaSession = true

        scrobbler.logSessionIfDebug(
            snapshot = MediaMetadataSnapshot(packageName = "com.netflix.ninja"),
            state = -1,
            positionMs = -1L,
            durationMs = -1L,
        )

        val entry = DiagnosticLog.snapshot()
            .single { it.message.startsWith("session pkg=") }
        assertTrue(entry.message.contains("(no evidence)"))
    }

    @Test
    fun `breadcrumb includes all evidence lines joined with pipe`() {
        scrobbler.debugLogMediaSession = true

        scrobbler.logSessionIfDebug(
            snapshot = snapshotOf(
                "com.plexapp.android",
                "albumArtist" to "Breaking Bad",
                "displaySubtitle" to "S01E01",
                "displayTitle" to "Pilot",
            ),
            state = 3,
            positionMs = 100L,
            durationMs = 2000L,
        )

        val entry = DiagnosticLog.snapshot()
            .single { it.message.startsWith("session pkg=") }
        assertTrue(entry.message.contains("pkg=com.plexapp.android"))
        assertTrue(entry.message.contains("Breaking Bad"))
        assertTrue(entry.message.contains("S01E01"))
        assertTrue(entry.message.contains("Pilot"))
    }
}
