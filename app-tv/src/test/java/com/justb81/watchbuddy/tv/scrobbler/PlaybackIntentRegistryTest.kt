package com.justb81.watchbuddy.tv.scrobbler

import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PlaybackIntentRegistry")
class PlaybackIntentRegistryTest {

    private lateinit var registry: PlaybackIntentRegistry

    private val disneyPkg = "com.disney.disneyplus"
    private val netflixPkg = "com.netflix.mediaclient"
    private val strangersIds = TraktIds(trakt = 104439, tmdb = 66732)
    private val witcherIds = TraktIds(trakt = 140799, tmdb = 71912)
    private val nowMs get() = System.currentTimeMillis()

    private fun makeIntent(
        showTitle: String = "Stranger Things",
        showIds: TraktIds = strangersIds,
        season: Int = 4,
        episode: Int = 1,
        pkg: String = disneyPkg,
        capturedAtMs: Long = nowMs,
    ) = PlaybackIntent(
        showIds = showIds,
        showTitle = showTitle,
        season = season,
        episode = episode,
        providerPackageName = pkg,
        capturedAtMs = capturedAtMs,
    )

    @BeforeEach
    fun setUp() {
        registry = PlaybackIntentRegistry()
    }

    @Nested
    @DisplayName("peek")
    inner class Peek {

        @Test
        fun `returns null when no intent recorded`() {
            assertNull(registry.peek(disneyPkg))
        }

        @Test
        fun `returns intent within TTL`() {
            registry.record(makeIntent())
            assertNotNull(registry.peek(disneyPkg))
        }

        @Test
        fun `returns null after TTL expires`() {
            val expired = makeIntent(capturedAtMs = nowMs - PlaybackIntentRegistry.TTL_MS - 1)
            registry.record(expired)
            assertNull(registry.peek(disneyPkg))
        }

        @Test
        fun `evicts expired intent on peek`() {
            val expired = makeIntent(capturedAtMs = nowMs - PlaybackIntentRegistry.TTL_MS - 1)
            registry.record(expired)
            registry.peek(disneyPkg) // triggers eviction
            // Record a fresh intent for the same package — should succeed
            registry.record(makeIntent())
            assertNotNull(registry.peek(disneyPkg))
        }
    }

    @Nested
    @DisplayName("last-write-wins per package")
    inner class LastWriteWins {

        @Test
        fun `second record for same package overwrites first`() {
            registry.record(makeIntent(showTitle = "Stranger Things", episode = 1))
            registry.record(makeIntent(showTitle = "The Witcher", showIds = witcherIds, episode = 2))
            val retrieved = registry.peek(disneyPkg)
            assertNotNull(retrieved)
            assertEquals("The Witcher", retrieved!!.showTitle)
            assertEquals(2, retrieved.episode)
        }

        @Test
        fun `different packages coexist independently`() {
            registry.record(makeIntent(showTitle = "Stranger Things", pkg = disneyPkg))
            registry.record(makeIntent(showTitle = "The Witcher", showIds = witcherIds, pkg = netflixPkg))

            val disneyIntent = registry.peek(disneyPkg)
            val netflixIntent = registry.peek(netflixPkg)

            assertNotNull(disneyIntent)
            assertNotNull(netflixIntent)
            assertEquals("Stranger Things", disneyIntent!!.showTitle)
            assertEquals("The Witcher", netflixIntent!!.showTitle)
        }
    }

    @Nested
    @DisplayName("consumeIntent")
    inner class ConsumeIntent {

        @Test
        fun `removes intent after consume`() {
            registry.record(makeIntent())
            registry.consumeIntent(disneyPkg)
            assertNull(registry.peek(disneyPkg))
        }

        @Test
        fun `consume for one package does not affect another`() {
            registry.record(makeIntent(pkg = disneyPkg))
            registry.record(makeIntent(pkg = netflixPkg))
            registry.consumeIntent(disneyPkg)
            assertNull(registry.peek(disneyPkg))
            assertNotNull(registry.peek(netflixPkg))
        }

        @Test
        fun `consume on unknown package is a no-op`() {
            registry.consumeIntent(disneyPkg) // no intent stored
            assertNull(registry.peek(disneyPkg))
        }
    }

    @Nested
    @DisplayName("intentStats")
    inner class IntentStatsTests {

        @Test
        fun `initial stats are all zero`() {
            val stats = registry.intentStats()
            assertEquals(0, stats.hits)
            assertEquals(0, stats.fallthroughs)
            assertEquals(0, stats.overriddenByManualMark)
        }

        @Test
        fun `recordHit increments hits counter`() {
            registry.recordHit()
            registry.recordHit()
            assertEquals(2, registry.intentStats().hits)
        }

        @Test
        fun `recordFallthrough increments fallthroughs counter`() {
            registry.recordFallthrough()
            assertEquals(1, registry.intentStats().fallthroughs)
        }

        @Test
        fun `recordOverriddenByManualMark increments override counter`() {
            registry.recordOverriddenByManualMark()
            assertEquals(1, registry.intentStats().overriddenByManualMark)
        }

        @Test
        fun `counters are independent`() {
            registry.recordHit()
            registry.recordFallthrough()
            registry.recordFallthrough()
            val stats = registry.intentStats()
            assertEquals(1, stats.hits)
            assertEquals(2, stats.fallthroughs)
            assertEquals(0, stats.overriddenByManualMark)
        }
    }
}
