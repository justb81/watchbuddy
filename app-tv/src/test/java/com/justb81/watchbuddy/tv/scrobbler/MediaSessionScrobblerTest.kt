package com.justb81.watchbuddy.tv.scrobbler

import android.content.Context
import android.media.session.MediaSessionManager
import com.justb81.watchbuddy.core.model.PlaybackTick
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler
import com.justb81.watchbuddy.core.scrobbler.MediaSnapshotBuilder
import com.justb81.watchbuddy.core.scrobbler.MetadataEnricher
import com.justb81.watchbuddy.core.scrobbler.NoOpTitleExtractor
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
        scrobbler = MediaSessionScrobbler(context, tmdbApiService, watchedShowSource, scrobbleDispatcher, NoOpTitleExtractor)
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

    // ── WatchNextMetadataSource enricher integration ──────────────────────────

    @Nested
    @DisplayName("WatchNextMetadataSource enricher — snapshot contains watchNext lines")
    inner class WatchNextEnricherTest {

        private fun buildWatchNextEnricher(): MetadataEnricher = MetadataEnricher { _, _, builder ->
            builder.add("watchNext.title", "Stranger Things")
            builder.add("watchNext.season", "4")
            builder.add("watchNext.episode", "1")
            builder.add("watchNext.contentId", "tmdb:66732")
            builder.add("watchNext.marker", "S04E01")
            builder.addSource("watchNext")
        }

        @Test
        fun `watchNext lines appear in snapshot text when enricher is registered`() = runTest {
            val enricher = buildWatchNextEnricher()
            val builder = MediaSnapshotBuilder("com.disney.disneyplus")
            enricher.enrich(
                "com.disney.disneyplus",
                PlaybackTick(PlaybackTick.STATE_PLAYING, 600_000, 2_700_000, System.currentTimeMillis()),
                builder,
            )
            val enrichedSnapshot = builder.build()

            assertTrue(enrichedSnapshot.text.contains("watchNext.title: Stranger Things"))
            assertTrue(enrichedSnapshot.text.contains("watchNext.season: 4"))
            assertTrue(enrichedSnapshot.text.contains("watchNext.episode: 1"))
            assertTrue(enrichedSnapshot.text.contains("watchNext.contentId: tmdb:66732"))
            assertTrue(enrichedSnapshot.text.contains("watchNext.marker: S04E01"))
            assertTrue(enrichedSnapshot.sources.contains("watchNext"))
        }
    }

    // ── NotificationMetadataSource enricher integration ───────────────────────

    @Nested
    @DisplayName("NotificationMetadataSource enricher — snapshot contains notification lines")
    inner class NotificationEnricherTest {

        @Test
        fun `notification lines appear in snapshot when enricher holds a fresh snippet`() = runTest {
            val notificationSource = NotificationMetadataSource()
            notificationSource.onPosted(
                NotificationSnippet(
                    packageName = "com.netflix.ninja",
                    title = "Stranger Things",
                    text = "S4:E1 Chapter One: The Hellfire Club",
                    subText = "Netflix",
                    bigText = null,
                    infoText = null,
                    postedAtMs = System.currentTimeMillis(),
                ),
            )

            val builder = MediaSnapshotBuilder("com.netflix.ninja")
            notificationSource.enrich(
                "com.netflix.ninja",
                PlaybackTick(PlaybackTick.STATE_PLAYING, 60_000, 2_700_000, System.currentTimeMillis()),
                builder,
            )
            val snapshot = builder.build()

            assertTrue(snapshot.text.contains("notification.title: Stranger Things"))
            assertTrue(snapshot.text.contains("notification.text: S4:E1 Chapter One: The Hellfire Club"))
            assertTrue(snapshot.text.contains("notification.subText: Netflix"))
            assertTrue(snapshot.sources.contains("notification"))
        }

        @Test
        fun `notification enricher produces no lines when session is not playing`() = runTest {
            val notificationSource = NotificationMetadataSource()
            notificationSource.onPosted(
                NotificationSnippet(
                    packageName = "com.netflix.ninja",
                    title = "Stranger Things",
                    text = "S4:E1",
                    subText = null,
                    bigText = null,
                    infoText = null,
                    postedAtMs = System.currentTimeMillis(),
                ),
            )

            val builder = MediaSnapshotBuilder("com.netflix.ninja")
            notificationSource.enrich(
                "com.netflix.ninja",
                PlaybackTick(PlaybackTick.STATE_PAUSED, 60_000, 2_700_000, System.currentTimeMillis()),
                builder,
            )

            assertFalse(builder.build().text.contains("notification."))
        }
    }
}
