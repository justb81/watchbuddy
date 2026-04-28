package com.justb81.watchbuddy.tv.scrobbler

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import androidx.tvprovider.media.tv.TvContractCompat
import com.justb81.watchbuddy.core.model.PlaybackTick
import com.justb81.watchbuddy.core.scrobbler.MediaSnapshotBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [WatchNextMetadataSource].
 *
 * ContentResolver is mocked with [MatrixCursor] fixtures so no Android runtime is required.
 */
@DisplayName("WatchNextMetadataSource")
class WatchNextMetadataSourceTest {

    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true) {
        every { contentResolver } returns this@WatchNextMetadataSourceTest.contentResolver
    }
    private val source = WatchNextMetadataSource(context)

    private val freshMs = System.currentTimeMillis() - 30_000L  // 30 s ago — well within 5 min

    private val PROJECTION = arrayOf(
        TvContractCompat.WatchNextPrograms.COLUMN_TITLE,
        TvContractCompat.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
        TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
        TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_TITLE,
        TvContractCompat.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
        TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID,
        TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
    )

    private fun buildCursor(vararg rows: Array<Any?>): MatrixCursor =
        MatrixCursor(PROJECTION).apply {
            rows.forEach { addRow(it) }
        }

    private fun givenQuery(cursor: MatrixCursor) {
        every {
            contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                any(),
                any(),
                any(),
                any(),
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
            val cursor = buildCursor(
                arrayOf(
                    "Stranger Things",       // COLUMN_TITLE
                    "4",                     // COLUMN_SEASON_DISPLAY_NUMBER
                    "1",                     // COLUMN_EPISODE_DISPLAY_NUMBER
                    "Chapter One",           // COLUMN_EPISODE_TITLE
                    "A short description",   // COLUMN_SHORT_DESCRIPTION
                    "tmdb:66732",            // COLUMN_CONTENT_ID
                    freshMs,                 // COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS
                ),
            )
            givenQuery(cursor)
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
            val cursor = buildCursor(
                arrayOf(
                    "Some Show",
                    "Season 4",              // not a bare integer — marker should be omitted
                    "E1",                    // not a bare integer — marker should be omitted
                    null,
                    null,
                    null,
                    freshMs,
                ),
            )
            givenQuery(cursor)
            val tick = PlaybackTick(
                state = PlaybackTick.STATE_PLAYING,
                positionMs = 0L,
                durationMs = 3_600_000L,
                capturedAtMs = System.currentTimeMillis(),
            )

            source.enrich("com.example.streaming", tick, builder)

            val snapshot = builder.build()
            assertFalse(snapshot.text.contains("watchNext.marker:"), "marker should not be synthesised for non-integer season/episode")
        }

        @Test
        fun `ignores stale row older than ROW_FRESHNESS_MS`() = runTest {
            val staleMs = System.currentTimeMillis() - WatchNextMetadataSource.ROW_FRESHNESS_MS - 1_000L
            val cursor = buildCursor(
                arrayOf("Old Show", "3", "5", null, null, null, staleMs),
            )
            givenQuery(cursor)
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
            givenQuery(buildCursor())   // empty cursor
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
            val cursor = buildCursor(
                arrayOf(
                    "My Show",
                    null,     // no season
                    "3",
                    "Episode Title",
                    null,
                    null,
                    freshMs,
                ),
            )
            givenQuery(cursor)
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

        private val PKG_COLUMN = arrayOf(TvContractCompat.WatchNextPrograms.COLUMN_PACKAGE_NAME)

        private fun buildPackageCursor(vararg packageNames: String): MatrixCursor =
            MatrixCursor(PKG_COLUMN).apply {
                packageNames.forEach { addRow(arrayOf(it)) }
            }

        private fun givenCountQuery(cursor: MatrixCursor) {
            every {
                contentResolver.query(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    PKG_COLUMN,
                    any(),
                    any(),
                    null,
                )
            } returns cursor
        }

        @Test
        fun `returns Success with count of distinct publishing packages`() {
            givenCountQuery(
                buildPackageCursor(
                    "com.netflix.ninja",
                    "com.disney.disneyplus",
                    "com.netflix.ninja",   // duplicate — should be deduplicated
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
