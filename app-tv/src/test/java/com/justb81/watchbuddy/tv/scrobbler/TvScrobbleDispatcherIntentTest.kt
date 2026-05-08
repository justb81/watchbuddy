package com.justb81.watchbuddy.tv.scrobbler

import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntent
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntentStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [PlaybackIntentRegistry] consumption semantics as exercised during scrobble dispatch.
 *
 * These tests verify the registry contract relied on by [MediaSessionScrobbler]:
 *   - Intent is consumed after a Phase-0 auto-scrobble (preventing the same intent from
 *     triggering a second episode auto-scrobble on the next poll cycle).
 *   - Intent is NOT consumed on a fallthrough (the intent stays available for the next
 *     poll cycle and may appear in the ambiguous prompt).
 *   - Counters are updated correctly for both outcomes.
 */
@DisplayName("PlaybackIntentRegistry — scrobble-dispatch consumption contract")
class TvScrobbleDispatcherIntentTest {

    private lateinit var registry: PlaybackIntentRegistry
    private lateinit var testScope: CoroutineScope

    private val disneyPkg = "com.disney.disneyplus"
    private val nowMs get() = System.currentTimeMillis()

    private fun makeIntent(
        pkg: String = disneyPkg,
        capturedAtMs: Long = nowMs,
    ) = PlaybackIntent(
        showIds = TraktIds(trakt = 104439, tmdb = 66732),
        showTitle = "Stranger Things",
        season = 4,
        episode = 1,
        providerPackageName = pkg,
        capturedAtMs = capturedAtMs,
    )

    @BeforeEach
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob())
        registry = PlaybackIntentRegistry(testScope)
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    @Nested
    @DisplayName("Phase-0 auto-scrobble path")
    inner class AutoScrobblePath {

        @Test
        fun `intent is consumed after recordHit + consumeIntent`() {
            registry.record(makeIntent())
            assertNotNull(registry.peek(disneyPkg))

            // Simulate what processPlayingMedia does on a Phase-0 hit
            registry.recordHit()
            registry.consumeIntent(disneyPkg)

            assertNull(registry.peek(disneyPkg))
            assertEquals(1, registry.intentStats().hits)
        }

        @Test
        fun `second poll after consume finds no intent`() {
            registry.record(makeIntent())
            registry.recordHit()
            registry.consumeIntent(disneyPkg)

            // Second poll should not see the old intent
            assertNull(registry.peek(disneyPkg))
        }

        @Test
        fun `consume of one package does not remove intent for a different package`() {
            val netflixPkg = "com.netflix.mediaclient"
            registry.record(makeIntent(pkg = disneyPkg))
            registry.record(makeIntent(pkg = netflixPkg))

            registry.recordHit()
            registry.consumeIntent(disneyPkg)

            assertNull(registry.peek(disneyPkg))
            assertNotNull(registry.peek(netflixPkg))
        }
    }

    @Nested
    @DisplayName("Phase-0 fallthrough path")
    inner class FallthroughPath {

        @Test
        fun `intent is NOT consumed on fallthrough — stays for next poll cycle`() {
            registry.record(makeIntent())
            assertNotNull(registry.peek(disneyPkg))

            // Simulate what processPlayingMedia does on a Phase-0 fallthrough
            registry.recordFallthrough()
            // NOTE: consumeIntent is NOT called on fallthrough

            assertNotNull(registry.peek(disneyPkg))
            assertEquals(1, registry.intentStats().fallthroughs)
        }

        @Test
        fun `multiple fallthroughs accumulate in counter`() {
            registry.record(makeIntent())

            repeat(3) { registry.recordFallthrough() }

            assertEquals(3, registry.intentStats().fallthroughs)
        }
    }

    @Nested
    @DisplayName("stats snapshot")
    inner class StatsSnapshot {

        @Test
        fun `stats reflect independent hit and fallthrough counts`() {
            registry.recordHit()
            registry.recordHit()
            registry.recordFallthrough()

            val stats: PlaybackIntentStats = registry.intentStats()
            assertEquals(2, stats.hits)
            assertEquals(1, stats.fallthroughs)
            assertEquals(0, stats.overriddenByManualMark)
        }

        @Test
        fun `overriddenByManualMark starts at zero and is incremented explicitly`() {
            assertEquals(0, registry.intentStats().overriddenByManualMark)
            registry.recordOverriddenByManualMark()
            assertEquals(1, registry.intentStats().overriddenByManualMark)
        }
    }

    @Nested
    @DisplayName("TTL boundary")
    inner class TtlBoundary {

        @Test
        fun `intent just within TTL is available`() {
            val justInsideTtl = nowMs - PlaybackIntentRegistry.TTL_MS + 1_000
            registry.record(makeIntent(capturedAtMs = justInsideTtl))
            assertNotNull(registry.peek(disneyPkg))
        }

        @Test
        fun `intent just outside TTL is evicted on peek`() {
            val justOutsideTtl = nowMs - PlaybackIntentRegistry.TTL_MS - 1
            registry.record(makeIntent(capturedAtMs = justOutsideTtl))
            assertNull(registry.peek(disneyPkg))
        }
    }
}
