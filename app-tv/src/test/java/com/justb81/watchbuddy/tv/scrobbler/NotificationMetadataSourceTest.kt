package com.justb81.watchbuddy.tv.scrobbler

import com.justb81.watchbuddy.core.model.PlaybackTick
import com.justb81.watchbuddy.core.scrobbler.MediaSnapshotBuilder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("NotificationMetadataSource")
class NotificationMetadataSourceTest {

    private lateinit var source: NotificationMetadataSource
    private val nowMs get() = System.currentTimeMillis()

    private fun snippet(
        pkg: String = "com.netflix.ninja",
        title: String? = "Stranger Things",
        text: String? = "S4:E1 Chapter One: The Hellfire Club",
        subText: String? = "Netflix",
        bigText: String? = null,
        infoText: String? = null,
        postedAtMs: Long = nowMs,
    ) = NotificationSnippet(pkg, title, text, subText, bigText, infoText, postedAtMs)

    private fun playingTick() = PlaybackTick(
        state = PlaybackTick.STATE_PLAYING,
        positionMs = 60_000L,
        durationMs = 2_700_000L,
        capturedAtMs = nowMs,
    )

    private fun pausedTick() = PlaybackTick(
        state = PlaybackTick.STATE_PAUSED,
        positionMs = 60_000L,
        durationMs = 2_700_000L,
        capturedAtMs = nowMs,
    )

    @BeforeEach
    fun setUp() {
        source = NotificationMetadataSource()
    }

    // ── onPosted / onRemoved ──────────────────────────────────────────────────

    @Nested
    @DisplayName("onPosted / onRemoved")
    inner class MapManagementTest {

        @Test
        fun `onPosted stores snippet under package name`() {
            source.onPosted(snippet(pkg = "com.netflix.ninja"))
            assertTrue(source.byPackage.containsKey("com.netflix.ninja"))
        }

        @Test
        fun `onPosted overwrites existing snippet for same package`() {
            source.onPosted(snippet(pkg = "com.netflix.ninja", title = "Old Show"))
            source.onPosted(snippet(pkg = "com.netflix.ninja", title = "New Show"))
            assertEquals("New Show", source.byPackage["com.netflix.ninja"]?.title)
        }

        @Test
        fun `onRemoved evicts snippet for the given package`() {
            source.onPosted(snippet(pkg = "com.netflix.ninja"))
            source.onRemoved("com.netflix.ninja")
            assertFalse(source.byPackage.containsKey("com.netflix.ninja"))
        }

        @Test
        fun `onRemoved on unknown package is a no-op`() {
            source.onRemoved("com.unknown.package")
            assertTrue(source.byPackage.isEmpty())
        }

        @Test
        fun `onRemoved for one package does not affect another`() {
            source.onPosted(snippet(pkg = "com.netflix.ninja"))
            source.onPosted(snippet(pkg = "com.disney.disneyplus"))
            source.onRemoved("com.netflix.ninja")
            assertTrue(source.byPackage.containsKey("com.disney.disneyplus"))
        }
    }

    // ── enrich() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("enrich()")
    inner class EnrichTest {

        @Test
        fun `returns early without adding lines when tick is not playing`() = runTest {
            source.onPosted(snippet(pkg = "com.netflix.ninja"))
            val builder = MediaSnapshotBuilder("com.netflix.ninja")

            source.enrich("com.netflix.ninja", pausedTick(), builder)

            assertFalse(builder.build().text.contains("notification."))
        }

        @Test
        fun `returns early when no snippet is stored for the package`() = runTest {
            val builder = MediaSnapshotBuilder("com.unknown.app")

            source.enrich("com.unknown.app", playingTick(), builder)

            assertFalse(builder.build().text.contains("notification."))
        }

        @Test
        fun `rejects snippet older than FRESHNESS_MS`() = runTest {
            val staleMs = nowMs - NotificationMetadataSource.FRESHNESS_MS - 1_000L
            source.onPosted(snippet(pkg = "com.netflix.ninja", postedAtMs = staleMs))
            val builder = MediaSnapshotBuilder("com.netflix.ninja")

            source.enrich("com.netflix.ninja", playingTick(), builder)

            assertFalse(
                builder.build().text.contains("notification."),
                "stale snippet must produce no notification lines",
            )
        }

        @Test
        fun `happy path — appends all five tagged lines and records source`() = runTest {
            source.onPosted(
                snippet(
                    pkg = "com.netflix.ninja",
                    title = "Stranger Things",
                    text = "S4:E1 Chapter One: The Hellfire Club",
                    subText = "Netflix",
                    bigText = "Extended description",
                    infoText = "Season 4",
                ),
            )
            val builder = MediaSnapshotBuilder("com.netflix.ninja")

            source.enrich("com.netflix.ninja", playingTick(), builder)

            val snapshot = builder.build()
            assertTrue(snapshot.text.contains("notification.title: Stranger Things"))
            assertTrue(snapshot.text.contains("notification.text: S4:E1 Chapter One: The Hellfire Club"))
            assertTrue(snapshot.text.contains("notification.subText: Netflix"))
            assertTrue(snapshot.text.contains("notification.bigText: Extended description"))
            assertTrue(snapshot.text.contains("notification.infoText: Season 4"))
            assertTrue(snapshot.sources.contains("notification"))
        }

        @Test
        fun `null fields are skipped — builder does not add null lines`() = runTest {
            source.onPosted(
                snippet(
                    pkg = "com.amazon.amazonvideo.livingroom",
                    title = "The Boys",
                    text = null,
                    subText = null,
                    bigText = null,
                    infoText = null,
                ),
            )
            val builder = MediaSnapshotBuilder("com.amazon.amazonvideo.livingroom")

            source.enrich("com.amazon.amazonvideo.livingroom", playingTick(), builder)

            val snapshot = builder.build()
            assertTrue(snapshot.text.contains("notification.title: The Boys"))
            assertFalse(snapshot.text.contains("notification.text:"))
            assertFalse(snapshot.text.contains("notification.subText:"))
            assertFalse(snapshot.text.contains("notification.bigText:"))
            assertFalse(snapshot.text.contains("notification.infoText:"))
        }

        @Test
        fun `snippet exactly at freshness boundary is accepted`() = runTest {
            val boundaryMs = nowMs - NotificationMetadataSource.FRESHNESS_MS + 500L
            source.onPosted(snippet(pkg = "com.netflix.ninja", postedAtMs = boundaryMs))
            val builder = MediaSnapshotBuilder("com.netflix.ninja")

            source.enrich("com.netflix.ninja", playingTick(), builder)

            assertTrue(builder.build().text.contains("notification.title:"))
        }

        @Test
        fun `stopped tick is also rejected by isPlaying gate`() = runTest {
            source.onPosted(snippet(pkg = "com.netflix.ninja"))
            val builder = MediaSnapshotBuilder("com.netflix.ninja")
            val stoppedTick = PlaybackTick(
                state = PlaybackTick.STATE_STOPPED,
                positionMs = -1L,
                durationMs = -1L,
                capturedAtMs = nowMs,
            )

            source.enrich("com.netflix.ninja", stoppedTick, builder)

            assertFalse(builder.build().text.contains("notification."))
        }
    }

    // ── recentlyObservedCount() ───────────────────────────────────────────────

    @Nested
    @DisplayName("recentlyObservedCount()")
    inner class RecentlyObservedCountTest {

        @Test
        fun `returns 0 when no snippets are stored`() {
            assertEquals(0, source.recentlyObservedCount())
        }

        @Test
        fun `counts packages with snippets within DIAGNOSTICS_WINDOW_MS`() {
            source.onPosted(snippet(pkg = "com.netflix.ninja"))
            source.onPosted(snippet(pkg = "com.disney.disneyplus"))

            assertEquals(2, source.recentlyObservedCount())
        }

        @Test
        fun `excludes packages with snippets older than DIAGNOSTICS_WINDOW_MS`() {
            val staleMs = nowMs - NotificationMetadataSource.DIAGNOSTICS_WINDOW_MS - 1_000L
            source.onPosted(snippet(pkg = "com.netflix.ninja", postedAtMs = staleMs))
            source.onPosted(snippet(pkg = "com.disney.disneyplus")) // fresh

            assertEquals(1, source.recentlyObservedCount())
        }

        @Test
        fun `returns 0 after all snippets are removed`() {
            source.onPosted(snippet(pkg = "com.netflix.ninja"))
            source.onRemoved("com.netflix.ninja")

            assertEquals(0, source.recentlyObservedCount())
        }

        @Test
        fun `counts each package at most once even if same package posted multiple times`() {
            source.onPosted(snippet(pkg = "com.netflix.ninja", title = "Show A"))
            source.onPosted(snippet(pkg = "com.netflix.ninja", title = "Show B"))

            assertEquals(1, source.recentlyObservedCount())
        }
    }
}
