package com.justb81.watchbuddy.tv.scrobbler

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.TvContractCompat
import com.justb81.watchbuddy.core.model.PlaybackTick
import com.justb81.watchbuddy.core.scrobbler.MediaSnapshotBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
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
 *
 * `ContextCompat.checkSelfPermission` is statically mocked to default to `PERMISSION_GRANTED`
 * — the production code now pre-checks `READ_TV_LISTINGS` before issuing any provider query
 * (the `Selection not allowed for ...` SecurityException is a TvProvider quirk we route
 * around by never sending a selection clause; see KDoc on [WatchNextMetadataSource]).
 */
@DisplayName("WatchNextMetadataSource")
class WatchNextMetadataSourceTest {

    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true) {
        every { contentResolver } returns this@WatchNextMetadataSourceTest.contentResolver
    }
    private val source = WatchNextMetadataSource(context)

    private val freshMs = System.currentTimeMillis() - 30_000L // 30 s ago — well within 5 min

    // Column indices for the row PROJECTION in WatchNextMetadataSource (package-name first).
    private val colPackage = 0
    private val colTitle = 1
    private val colSeason = 2
    private val colEpisode = 3
    private val colEpisodeTitle = 4
    private val colShortDesc = 5
    private val colContentId = 6
    private val colLastEngagement = 7

    @BeforeEach
    fun grantReadTvListingsByDefault() {
        // The production pre-check in countPublishingApps() consults checkSelfPermission()
        // before any provider round-trip. Tests that exercise the provider must default to
        // PERMISSION_GRANTED; tests asserting denial behaviour override per-test.
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), "android.permission.READ_TV_LISTINGS")
        } returns PackageManager.PERMISSION_GRANTED
    }

    @AfterEach
    fun unmockContextCompat() {
        unmockkStatic(ContextCompat::class)
    }

    private data class EpisodeRow(
        val packageName: String,
        val title: String? = null,
        val season: String? = null,
        val episode: String? = null,
        val episodeTitle: String? = null,
        val shortDesc: String? = null,
        val contentId: String? = null,
        val lastEngagementMs: Long? = null,
    )

    /**
     * Builds a mock Cursor that walks `rows` via `moveToNext()`. The current row index is
     * tracked in a single-element array so `getString(idx)` / `getLong(idx)` / `isNull(idx)`
     * answer values for the right row.
     */
    private fun buildEpisodeCursor(vararg rows: EpisodeRow): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        val cursorPos = intArrayOf(-1)
        every { cursor.moveToNext() } answers {
            cursorPos[0]++
            cursorPos[0] < rows.size
        }
        every { cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_PACKAGE_NAME) } returns colPackage
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

        every { cursor.getString(colPackage) } answers { rows.getOrNull(cursorPos[0])?.packageName }
        every { cursor.getString(colTitle) } answers { rows.getOrNull(cursorPos[0])?.title }
        every { cursor.getString(colSeason) } answers { rows.getOrNull(cursorPos[0])?.season }
        every { cursor.getString(colEpisode) } answers { rows.getOrNull(cursorPos[0])?.episode }
        every { cursor.getString(colEpisodeTitle) } answers { rows.getOrNull(cursorPos[0])?.episodeTitle }
        every { cursor.getString(colShortDesc) } answers { rows.getOrNull(cursorPos[0])?.shortDesc }
        every { cursor.getString(colContentId) } answers { rows.getOrNull(cursorPos[0])?.contentId }
        every { cursor.isNull(colPackage) } answers { rows.getOrNull(cursorPos[0])?.packageName == null }
        every { cursor.isNull(colLastEngagement) } answers {
            rows.getOrNull(cursorPos[0])?.lastEngagementMs == null
        }
        every { cursor.getLong(colLastEngagement) } answers {
            rows.getOrNull(cursorPos[0])?.lastEngagementMs ?: 0L
        }
        return cursor
    }

    /**
     * Convenience for the count query, which only reads `COLUMN_PACKAGE_NAME` and
     * `COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS`. We reuse [buildEpisodeCursor] with minimal
     * rows so the same cursor can serve either query path (the production code uses two
     * different projections but the mock only cares about which column indices are read).
     */
    private fun buildPackageCursor(vararg packageNames: String): Cursor =
        buildEpisodeCursor(
            *packageNames.map { EpisodeRow(packageName = it, lastEngagementMs = freshMs) }.toTypedArray(),
        )

    /**
     * Asserts that the production code passes `selection = null` AND `selectionArgs = null` —
     * any non-null selection triggers AOSP TvProvider's
     * `SecurityException("Selection not allowed for ...")`.
     */
    private fun givenEpisodeQuery(cursor: Cursor) {
        every {
            contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                any(),
                isNull<String>(),
                isNull<Array<String>>(),
                isNull<String>(),
            )
        } returns cursor
    }

    private fun givenCountQuery(cursor: Cursor) {
        every {
            contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                any(),
                isNull<String>(),
                isNull<Array<String>>(),
                isNull<String>(),
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
                    EpisodeRow(
                        packageName = "com.disney.disneyplus",
                        title = "Stranger Things",
                        season = "4",
                        episode = "1",
                        episodeTitle = "Chapter One",
                        shortDesc = "A short description",
                        contentId = "tmdb:66732",
                        lastEngagementMs = freshMs,
                    ),
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
                    EpisodeRow(
                        packageName = "com.example.streaming",
                        title = "Some Show",
                        season = "Season 4", // not a bare integer — marker should be omitted
                        episode = "E1", // not a bare integer — marker should be omitted
                        lastEngagementMs = freshMs,
                    ),
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
                    EpisodeRow(
                        packageName = "com.example.streaming",
                        title = "Old Show",
                        season = "3",
                        episode = "5",
                        lastEngagementMs = staleMs,
                    ),
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
            givenEpisodeQuery(buildEpisodeCursor()) // empty
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
        fun `ignores rows belonging to other packages`() = runTest {
            // Cursor mixes Netflix + Disney rows. enrich() asks for Disney and must not
            // accidentally surface Netflix metadata even though it's freshest.
            givenEpisodeQuery(
                buildEpisodeCursor(
                    EpisodeRow(
                        packageName = "com.netflix.ninja",
                        title = "Stranger Things",
                        season = "4",
                        episode = "9",
                        lastEngagementMs = freshMs,
                    ),
                    EpisodeRow(
                        packageName = "com.disney.disneyplus",
                        title = "The Mandalorian",
                        season = "3",
                        episode = "2",
                        lastEngagementMs = freshMs - 60_000L, // older but matches package
                    ),
                ),
            )
            val tick = PlaybackTick(
                state = PlaybackTick.STATE_PLAYING,
                positionMs = 0L,
                durationMs = 2_700_000L,
                capturedAtMs = System.currentTimeMillis(),
            )

            source.enrich("com.disney.disneyplus", tick, builder)

            val snapshot = builder.build()
            assertTrue(snapshot.text.contains("watchNext.title: The Mandalorian"))
            assertFalse(snapshot.text.contains("Stranger Things"), "Netflix row leaked into Disney lookup")
        }

        @Test
        fun `picks the most recent row when a package has multiple matching entries`() = runTest {
            val older = freshMs - 90_000L
            val newer = freshMs - 1_000L
            givenEpisodeQuery(
                buildEpisodeCursor(
                    EpisodeRow(
                        packageName = "com.netflix.ninja",
                        title = "Old Episode",
                        season = "1",
                        episode = "1",
                        lastEngagementMs = older,
                    ),
                    EpisodeRow(
                        packageName = "com.netflix.ninja",
                        title = "New Episode",
                        season = "1",
                        episode = "2",
                        lastEngagementMs = newer,
                    ),
                ),
            )
            val tick = PlaybackTick(
                state = PlaybackTick.STATE_PLAYING,
                positionMs = 0L,
                durationMs = 2_700_000L,
                capturedAtMs = System.currentTimeMillis(),
            )

            source.enrich("com.netflix.ninja", tick, builder)

            val snapshot = builder.build()
            assertTrue(snapshot.text.contains("watchNext.title: New Episode"))
            assertFalse(snapshot.text.contains("Old Episode"))
        }

        @Test
        fun `handles missing season column gracefully — no marker emitted`() = runTest {
            givenEpisodeQuery(
                buildEpisodeCursor(
                    EpisodeRow(
                        packageName = "com.example.streaming",
                        title = "My Show",
                        season = null,
                        episode = "3",
                        episodeTitle = "Episode Title",
                        lastEngagementMs = freshMs,
                    ),
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
            } throws SecurityException("provider rejected query")
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
        fun `excludes stale rows from the distinct-package count`() {
            val stale = System.currentTimeMillis() - WatchNextMetadataSource.ROW_FRESHNESS_MS - 1_000L
            givenCountQuery(
                buildEpisodeCursor(
                    EpisodeRow(packageName = "com.netflix.ninja", lastEngagementMs = freshMs),
                    EpisodeRow(packageName = "com.disney.disneyplus", lastEngagementMs = stale),
                ),
            )

            val result = source.countPublishingApps()

            assertEquals(WatchNextMetadataSource.CountResult.Success(1), result)
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
                contentResolver.query(any(), any(), isNull<String>(), isNull<Array<String>>(), isNull<String>())
            } returns null

            val result = source.countPublishingApps()

            assertEquals(WatchNextMetadataSource.CountResult.Success(0), result)
        }

        @Test
        fun `returns PermissionDenied without querying when checkSelfPermission is DENIED`() {
            every {
                ContextCompat.checkSelfPermission(any(), "android.permission.READ_TV_LISTINGS")
            } returns PackageManager.PERMISSION_DENIED

            val result = source.countPublishingApps()

            assertTrue(result is WatchNextMetadataSource.CountResult.PermissionDenied)
            verify(exactly = 0) { contentResolver.query(any(), any(), any(), any(), any()) }
            assertTrue(source.isPermissionDenied(), "denial flag should latch for subsequent ticks")
        }

        @Test
        fun `returns PermissionDenied on SecurityException`() {
            every {
                contentResolver.query(any(), any(), any(), any(), any())
            } throws SecurityException("provider rejected query")

            val result = source.countPublishingApps()

            assertTrue(result is WatchNextMetadataSource.CountResult.PermissionDenied)
        }
    }

    // ── permission denial caching ───────────────────────────────────────────

    @Nested
    @DisplayName("permission denial caching")
    inner class PermissionDenialCaching {

        private val tick = PlaybackTick(
            state = PlaybackTick.STATE_PLAYING,
            positionMs = 0L,
            durationMs = 3_600_000L,
            capturedAtMs = System.currentTimeMillis(),
        )

        @BeforeEach
        fun overridePermissionToDenied() {
            // Override the outer @BeforeEach's GRANTED default: when the cached denial flag
            // is set, the live re-check inside isPermissionCurrentlyDenied() must still
            // report DENIED for the short-circuit to remain in effect.
            every {
                ContextCompat.checkSelfPermission(any(), "android.permission.READ_TV_LISTINGS")
            } returns PackageManager.PERMISSION_DENIED
        }

        @Test
        fun `enrich short-circuits subsequent calls after first SecurityException`() = runTest {
            every {
                contentResolver.query(any(), any(), any(), any(), any())
            } throws SecurityException("provider rejected query")
            val builder1 = MediaSnapshotBuilder("com.netflix.ninja")
            val builder2 = MediaSnapshotBuilder("com.disney.disneyplus")

            source.enrich("com.netflix.ninja", tick, builder1)
            source.enrich("com.disney.disneyplus", tick, builder2)

            assertTrue(source.isPermissionDenied())
            // The first call hit the resolver (and threw), the second short-circuited.
            verify(exactly = 1) { contentResolver.query(any(), any(), any(), any(), any()) }
            assertFalse(builder1.build().text.contains("watchNext."))
            assertFalse(builder2.build().text.contains("watchNext."))
        }

        @Test
        fun `countPublishingApps short-circuits after a prior denial`() = runTest {
            every {
                contentResolver.query(any(), any(), any(), any(), any())
            } throws SecurityException("provider rejected query")

            // First call records the denial via the live pre-check (DENIED).
            val first = source.countPublishingApps()
            // Second call must NOT hit the resolver again.
            val second = source.countPublishingApps()

            assertTrue(first is WatchNextMetadataSource.CountResult.PermissionDenied)
            assertTrue(second is WatchNextMetadataSource.CountResult.PermissionDenied)
            // The pre-check returns DENIED so the resolver is never consulted on either call.
            verify(exactly = 0) { contentResolver.query(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `resetPermissionState re-enables queries after a prior denial`() = runTest {
            every {
                contentResolver.query(any(), any(), any(), any(), any())
            } throws SecurityException("provider rejected query")
            source.countPublishingApps() // caches denial via pre-check
            assertTrue(source.isPermissionDenied())

            // Permission has been granted — diagnostics screen calls reset, then the next
            // query should hit the resolver again instead of short-circuiting.
            source.resetPermissionState()
            assertFalse(source.isPermissionDenied())
            every {
                ContextCompat.checkSelfPermission(any(), "android.permission.READ_TV_LISTINGS")
            } returns PackageManager.PERMISSION_GRANTED
            givenCountQuery(buildPackageCursor("com.netflix.ninja"))

            val result = source.countPublishingApps()

            assertEquals(WatchNextMetadataSource.CountResult.Success(1), result)
        }

        @Test
        fun `enrich auto-heals when READ_TV_LISTINGS was granted out-of-band`() = runTest {
            // Seed the denial flag by having the first call throw SecurityException.
            every {
                contentResolver.query(any(), any(), any(), any(), any())
            } throws SecurityException("provider rejected query")
            source.enrich("com.netflix.ninja", tick, MediaSnapshotBuilder("com.netflix.ninja"))
            assertTrue(source.isPermissionDenied())

            // Simulate the user granting the permission via system Settings (out-of-band).
            every {
                ContextCompat.checkSelfPermission(any(), "android.permission.READ_TV_LISTINGS")
            } returns PackageManager.PERMISSION_GRANTED
            givenEpisodeQuery(
                buildEpisodeCursor(
                    EpisodeRow(
                        packageName = "com.netflix.ninja",
                        title = "Stranger Things",
                        season = "4",
                        episode = "9",
                        lastEngagementMs = freshMs,
                    ),
                ),
            )
            val builder = MediaSnapshotBuilder("com.netflix.ninja")

            source.enrich("com.netflix.ninja", tick, builder)

            // Flag must have been cleared by the live permission check.
            assertFalse(source.isPermissionDenied())
            val snapshot = builder.build()
            assertTrue(
                snapshot.text.contains("watchNext.title: Stranger Things"),
                "enrichment should resume after auto-heal",
            )
        }

        @Test
        fun `countPublishingApps auto-heals when READ_TV_LISTINGS was granted out-of-band`() {
            // Seed the denial flag via the pre-check.
            source.countPublishingApps()
            assertTrue(source.isPermissionDenied())

            // Simulate an out-of-band grant.
            every {
                ContextCompat.checkSelfPermission(any(), "android.permission.READ_TV_LISTINGS")
            } returns PackageManager.PERMISSION_GRANTED
            givenCountQuery(buildPackageCursor("com.netflix.ninja", "com.disney.disneyplus"))

            val result = source.countPublishingApps()

            assertFalse(source.isPermissionDenied())
            assertEquals(WatchNextMetadataSource.CountResult.Success(2), result)
        }
    }
}
