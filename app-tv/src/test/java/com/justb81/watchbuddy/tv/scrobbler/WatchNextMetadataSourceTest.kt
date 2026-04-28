package com.justb81.watchbuddy.tv.scrobbler

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import androidx.tvprovider.media.tv.TvContractCompat
import com.justb81.watchbuddy.core.model.PlaybackTick
import com.justb81.watchbuddy.core.scrobbler.MediaSnapshotBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [WatchNextMetadataSource].
 *
 * ContentResolver is mocked with a mock [Cursor] so no Android runtime is required.
 * (MatrixCursor is an Android stub in unit tests — its methods return default values.)
 */
@DisplayName("WatchNextMetadataSource")
class WatchNextMetadataSourceTest {

    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true) {
        every { contentResolver } returns this@WatchNextMetadataSourceTest.contentResolver
    }
    private val source = WatchNextMetadataSource(context)

    private val freshMs = System.currentTimeMillis() - 30_000L // 30 s ago — well within 5 min

    // Column indices matching the PROJECTION in WatchNextMetadataSource
    private val colTitle = 0
    private val colSeason = 1
    private val colEpisode = 2
    private val colEpisodeTitle = 3
    private val colShortDesc = 4
    private val colContentId = 5
    private val colLastEngagement = 6

    /**
     * Builds a mock Cursor for the episode-lookup query (PROJECTION with 7 columns).
     * Pass [empty] = true to simulate an empty cursor (moveToFirst returns false).
     */
    private fun buildEpisodeCursor(
        title: String?,
        season: String?,
        episode: String?,
        episodeTitle: String?,
        shortDesc: String?,
        contentId: String?,
        lastEngagementMs: Long?,
        empty: Boolean = false,
    ): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToFirst() } returns !empty
        every { cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_TITLE) } returns colTitle
        every {
            cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER)
        } returns colSeason
        every {
            cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER)
        } returns colEpisode
        every {
            cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_TITLE)
        } returns colEpisodeTitle
        every {
            cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION)
        } returns colShortDesc
        every {
            cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID)
        } returns colContentId
        every {
            cursor.getColumnIndex(
                TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            )
        } returns colLastEngagement
        every { cursor.getString(colTitle) } returns title
        every { cursor.getString(colSeason) } returns season
        every { cursor.getString(colEpisode) } returns episode
        every { cursor.getString(colEpisodeTitle) } returns episodeTitle
        every { cursor.getString(colShortDesc) } returns shortDesc
        every { cursor.getString(colContentId) } returns contentId
        every { cursor.isNull(colLastEngagement) } returns (lastEngagementMs == null)
        every { cursor.getLong(colLastEngagement) } returns (lastEngagementMs ?: 0L)
        return cursor
    }

    /**
     * Builds a mock Cursor for the package-count query (single COLUMN_PACKAGE_NAME column).
     */
    private fun buildPackageCursor(vararg packageNames: String): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        val callCount = intArrayOf(0)
        every { cursor.moveToNext() } answers { callCount[0]++ < packageNames.size }
        if (packageNames.isNotEmpty()) {
            every { cursor.getString(0) } answers { packageNames[callCount[0] - 1] }
        }
        return cursor
    }

    private fun givenEpisodeQuery(cursor: Cursor) {
        every {
            contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                any(), any(), any(), any(),
            )
        } returns cursor
    }

    private fun givenCountQuery(cursor: Cursor) {
        every {
            contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                any(), any(), any(), null,
            )
        } returns cursor
    }

    // ── enrich() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("enrich()")
    inner class EnrichTest {

        private lateinit var builder: MediaSnapshotBuilder

        @BeforeEach
        fun setUp() {
            builder = MediaSnapshotBuilder("com.disney.disneyplus")
        }

        @Test
        fun `skips provider query when tick is not playing`() = runTest {
            val tick = PlaybackTick(
                state = PlaybackTick.STATE_PAUSED,
                positionMs = 60_000L,
                durationMs = 2_700_000L,
                capturedAtMs = System.currentTimeMillis(),
            )

            source.enrich("com.disney.disneyplus", tick, builder)

            verify(exactly = 0) { contentResolver.query(any(), any(), any(), any(), any()) }
            val snapshot = builder.build()
            assertFalse(snapshot.text.contains("watchNext."))
        }

        @Test
        fun `skips provider query when tick is stopped`() = runTest {
            val tick = PlaybackTick(
                state = PlaybackTick.STATE_STOPPED,
                positionMs = -1L,
                durationMs = -1L,
                capturedAtMs = System.currentTimeMillis(),
            )

            source.enrich("com.netflix.ninja", tick, builder)

            verify(exactly = 0) { contentResolver.query(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `adds watchNext lines for a fresh episode row`() = runTest {
            givenEpisodeQuery(
                buildEpisodeCursor(
                    title = "Stranger Things",
                    season = "4",
                    episode = "1",
                    episodeTitle = "Chapter One",
                    shortDesc = "A short description",
                    contentId = "tmdb:66732",
                    lastEngagementMs = freshMs,
                ),
            )
            val tick = PlaybackTick(
                state = PlaybackTick.STATE_PLAYING,
                positionMs = 60_000L,
                durationMs = 2_700_000L,
                capturedAtMs = System.currentTimeMillis(),
            )

            source.enrich("com.disney.disneyplus", tick, builder)

            val snapshot = builder.build()
            assertTrue(snapshot.text.contains("watchNext.title: Stranger Things"))
            assertTrue(snapshot.text.contains("watchNext.season: 4"))
            assertTrue(snapshot.text.contains("watchNext.episode: 1"))
            assertTrue(snapshot.text.contains("watchNext.episodeTitle: Chapter One"))
            assertTrue(snapshot.text.contains("watchNext.contentId: tmdb:66732"))
            assertTrue(snapshot.text.contains("watchNext.marker: S04E01"), "synthesised marker missing")
            assertTrue(snapshot.sources.contains("watchNext"))
        }

        @Test
        fun `synthesises S##E## marker only when both numbers are integer-parseable`() = runTest {
            givenEpisodeQuery(
                buildEpisodeCursor(
                    title = "Some Show",
                    season = "Season 4", // not a bare integer — marker should be omitted
                    episode = "E1", // not a bare integer — marker should be omitted
                    episodeTitle = null,
                    shortDesc = null,
                    contentId = null,
                    lastEngagementMs = freshMs,
                ),
            )
            val tick = PlaybackTick(
                state = PlaybackTick.STATE_PLAYING,
                positionMs = 0L,
                durationMs = 3_600_000L,
                capturedAtMs = System.currentTimeMillis(),
            )

            source.enrich("com.example.streaming", tick, builder)

            val snapshot = builder.build()
            assertFalse(
                snapshot.text.contains("watchNext.marker:"),
                "marker should not be synthesised for non-integer season/episode",
            )
        }

        @Test
        fun `ignores stale row older than ROW_FRESHNESS_MS`() = runTest {
            val staleMs = System.currentTimeMillis() - WatchNextMetadataSource.ROW_FRESHNESS_MS - 1_000L
            givenEpisodeQuery(
                buildEpisodeCursor(
                    title = "Old Show",
                    season = "3",
                    episode = "5",
                    episodeTitle = null,
                    shortDesc = null,
                    contentId = null,
                    lastEngagementMs = staleMs,
                ),
            )
            val tick = PlaybackTick(
                state = PlaybackTick.STATE_PLAYING,
                positionMs = 0L,
                durationMs = 3_600_000L,
                capturedAtMs = System.currentTimeMillis(),
            )

            source.enrich("com.example.streaming", tick, builder)

            val snapshot = builder.build()
            assertFalse(snapshot.text.contains("watchNext."), "stale row should produce no watchNext lines")
        }

        @Test
        fun `produces no watchNext lines when no rows exist for package`() = runTest {
            givenEpisodeQuery(
                buildEpisodeCursor(
                    title = null, season = null, episode = null, episodeTitle = null,
                    shortDesc = null, contentId = null, lastEngagementMs = null, empty = true,
                ),
            )
            val tick = PlaybackTick(
                state = PlaybackTick.STATE_PLAYING,
                positionMs = 0L,
                durationMs = 3_600_000L,
                capturedAtMs = System.currentTimeMillis(),
            )

            source.enrich("com.unknown.app", tick, builder)

            assertFalse(builder.build().text.contains("watchNext."))
        }

        @Test
        fun `handles missing season column gracefully — no marker emitted`() = runTest {
            givenEpisodeQuery(
                buildEpisodeCursor(
                    title = "My Show",
                    season = null,
                    episode = "3",
                    episodeTitle = "Episode Title",
                    shortDesc = null,
                    contentId = null,
                    lastEngagementMs = freshMs,
                ),
            )
            val tick = PlaybackTick(
                state = PlaybackTick.STATE_PLAYING,
                positionMs = 0L,
                durationMs = 2_700_000L,
                capturedAtMs = System.currentTimeMillis(),
            )

            source.enrich("com.example.streaming", tick, builder)

            val snapshot = builder.build()
            assertTrue(snapshot.text.contains("watchNext.title: My Show"))
            assertFalse(snapshot.text.contains("watchNext.season:"), "null season should be skipped")
            assertFalse(snapshot.text.contains("watchNext.marker:"), "marker omitted when season is null")
        }

        @Test
        fun `produces no watchNext lines on SecurityException`() = runTest {
            every {
                contentResolver.query(any(), any(), any(), any(), any())
            } throws SecurityException("READ_TV_LISTINGS denied")
            val tick = PlaybackTick(
                state = PlaybackTick.STATE_PLAYING,
                positionMs = 0L,
                durationMs = 2_700_000L,
                capturedAtMs = System.currentTimeMillis(),
            )

            source.enrich("com.netflix.ninja", tick, builder)

            assertFalse(builder.build().text.contains("watchNext."))
        }
    }

    // ── countPublishingApps() ─────────────────────────────────────────────────

    @Nested
    @DisplayName("countPublishingApps()")
    inner class CountPublishingAppsTest {

        @Test
        fun `returns Success with count of distinct publishing packages`() {
            givenCountQuery(
                buildPackageCursor(
                    "com.netflix.ninja",
                    "com.disney.disneyplus",
                    "com.netflix.ninja", // duplicate — should be deduplicated
                ),
            )

            val result = source.countPublishingApps()

            assertTrue(result is WatchNextMetadataSource.CountResult.Success)
            assertEquals(2, (result as WatchNextMetadataSource.CountResult.Success).count)
        }

        @Test
        fun `returns Success(0) when cursor is empty`() {
            givenCountQuery(buildPackageCursor())

            val result = source.countPublishingApps()

            assertEquals(WatchNextMetadataSource.CountResult.Success(0), result)
        }

        @Test
        fun `returns Success(0) when contentResolver returns null cursor`() {
            every {
                contentResolver.query(any(), any(), any(), any(), null)
            } returns null

            val result = source.countPublishingApps()

            assertEquals(WatchNextMetadataSource.CountResult.Success(0), result)
        }

        @Test
        fun `returns PermissionDenied on SecurityException`() {
            every {
                contentResolver.query(any(), any(), any(), any(), any())
            } throws SecurityException("READ_TV_LISTINGS denied")

            val result = source.countPublishingApps()

            assertTrue(result is WatchNextMetadataSource.CountResult.PermissionDenied)
        }
    }
}
