package com.justb81.watchbuddy.core.tmdb

import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.core.model.TmdbShow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TmdbCache")
class TmdbCacheTest {

    private lateinit var cache: TmdbCache

    @BeforeEach
    fun setUp() {
        cache = TmdbCache()
    }

    @Nested
    @DisplayName("show cache")
    inner class ShowCacheTest {

        @Test
        fun `returns null for unknown show`() {
            assertNull(cache.getShow(1))
        }

        @Test
        fun `returns show after put`() {
            val show = TmdbShow(1, "Breaking Bad")
            cache.putShow(1, show)
            assertEquals(show, cache.getShow(1))
        }

        @Test
        fun `overwrites existing entry on put`() {
            val original = TmdbShow(1, "Old Name")
            val updated = TmdbShow(1, "New Name")
            cache.putShow(1, original)
            cache.putShow(1, updated)
            assertEquals(updated, cache.getShow(1))
        }

        @Test
        fun `caches different shows independently`() {
            val show1 = TmdbShow(1, "Show One")
            val show2 = TmdbShow(2, "Show Two")
            cache.putShow(1, show1)
            cache.putShow(2, show2)
            assertEquals(show1, cache.getShow(1))
            assertEquals(show2, cache.getShow(2))
        }

        @Test
        fun `returns null for missing show id while others are present`() {
            cache.putShow(10, TmdbShow(10, "Existing"))
            assertNull(cache.getShow(99))
        }

        @Test
        fun `returns null after clear`() {
            cache.putShow(1, TmdbShow(1, "Test Show"))
            cache.clear()
            assertNull(cache.getShow(1))
        }
    }

    @Nested
    @DisplayName("episode cache")
    inner class EpisodeCacheTest {

        @Test
        fun `returns null for unknown episode`() {
            assertNull(cache.getEpisode(1, 1, 1))
        }

        @Test
        fun `returns episode after put`() {
            val episode = TmdbEpisode(101, "Pilot", "First episode", null, 1, 1)
            cache.putEpisode(100, 1, 1, episode)
            assertEquals(episode, cache.getEpisode(100, 1, 1))
        }

        @Test
        fun `differentiates episodes by series id`() {
            val ep1 = TmdbEpisode(1, "Show A S1E1", null, null, 1, 1)
            val ep2 = TmdbEpisode(2, "Show B S1E1", null, null, 1, 1)
            cache.putEpisode(100, 1, 1, ep1)
            cache.putEpisode(200, 1, 1, ep2)
            assertEquals(ep1, cache.getEpisode(100, 1, 1))
            assertEquals(ep2, cache.getEpisode(200, 1, 1))
        }

        @Test
        fun `differentiates episodes by season number`() {
            val ep1 = TmdbEpisode(1, "S1E1", null, null, 1, 1)
            val ep2 = TmdbEpisode(2, "S2E1", null, null, 2, 1)
            cache.putEpisode(100, 1, 1, ep1)
            cache.putEpisode(100, 2, 1, ep2)
            assertEquals(ep1, cache.getEpisode(100, 1, 1))
            assertEquals(ep2, cache.getEpisode(100, 2, 1))
        }

        @Test
        fun `differentiates episodes by episode number`() {
            val ep1 = TmdbEpisode(1, "S1E1", null, null, 1, 1)
            val ep2 = TmdbEpisode(2, "S1E2", null, null, 1, 2)
            cache.putEpisode(100, 1, 1, ep1)
            cache.putEpisode(100, 1, 2, ep2)
            assertEquals(ep1, cache.getEpisode(100, 1, 1))
            assertEquals(ep2, cache.getEpisode(100, 1, 2))
        }

        @Test
        fun `overwrites existing entry on put`() {
            val original = TmdbEpisode(1, "Original", null, null, 1, 1)
            val updated = TmdbEpisode(1, "Updated", "New overview", null, 1, 1)
            cache.putEpisode(100, 1, 1, original)
            cache.putEpisode(100, 1, 1, updated)
            assertEquals(updated, cache.getEpisode(100, 1, 1))
        }

        @Test
        fun `returns null after clear`() {
            cache.putEpisode(100, 1, 1, TmdbEpisode(1, "Pilot", null, null, 1, 1))
            cache.clear()
            assertNull(cache.getEpisode(100, 1, 1))
        }
    }

    @Nested
    @DisplayName("TTL expiry")
    inner class TtlTest {

        private val baseTime = 1_000_000L
        private var fakeTime = baseTime

        @BeforeEach
        fun setUpClock() {
            fakeTime = baseTime
            cache.timeSource = { fakeTime }
        }

        @Test
        fun `getShow returns entry just before TTL expires`() {
            cache.putShow(1, TmdbShow(1, "Breaking Bad"))
            fakeTime = baseTime + TmdbCache.TTL_MS - 1
            assertNotNull(cache.getShow(1))
        }

        @Test
        fun `getShow returns null at TTL boundary`() {
            cache.putShow(1, TmdbShow(1, "Breaking Bad"))
            fakeTime = baseTime + TmdbCache.TTL_MS
            assertNull(cache.getShow(1))
        }

        @Test
        fun `getShow returns null after TTL expires`() {
            cache.putShow(1, TmdbShow(1, "Breaking Bad"))
            fakeTime = baseTime + TmdbCache.TTL_MS + 1
            assertNull(cache.getShow(1))
        }

        @Test
        fun `getShow removes expired entry from cache`() {
            cache.putShow(1, TmdbShow(1, "Test"))
            fakeTime = baseTime + TmdbCache.TTL_MS + 1
            cache.getShow(1) // triggers eviction
            // A fresh put at current time should work
            cache.putShow(1, TmdbShow(1, "Fresh"))
            assertNotNull(cache.getShow(1))
        }

        @Test
        fun `putShow refreshes TTL on overwrite`() {
            cache.putShow(1, TmdbShow(1, "Original"))
            // Advance close to expiry, then overwrite
            fakeTime = baseTime + TmdbCache.TTL_MS - 100
            cache.putShow(1, TmdbShow(1, "Updated"))
            // Advance past the original TTL — new entry should still be valid
            fakeTime = baseTime + TmdbCache.TTL_MS + 1
            val result = cache.getShow(1)
            assertNotNull(result)
            assertEquals("Updated", result!!.name)
        }

        @Test
        fun `getEpisode returns entry just before TTL expires`() {
            cache.putEpisode(100, 1, 1, TmdbEpisode(1, "Pilot", null, null, 1, 1))
            fakeTime = baseTime + TmdbCache.TTL_MS - 1
            assertNotNull(cache.getEpisode(100, 1, 1))
        }

        @Test
        fun `getEpisode returns null at TTL boundary`() {
            cache.putEpisode(100, 1, 1, TmdbEpisode(1, "Pilot", null, null, 1, 1))
            fakeTime = baseTime + TmdbCache.TTL_MS
            assertNull(cache.getEpisode(100, 1, 1))
        }

        @Test
        fun `getEpisode returns null after TTL expires`() {
            cache.putEpisode(100, 1, 1, TmdbEpisode(1, "Pilot", null, null, 1, 1))
            fakeTime = baseTime + TmdbCache.TTL_MS + 1
            assertNull(cache.getEpisode(100, 1, 1))
        }

        @Test
        fun `TTL expiry is independent per entry`() {
            cache.putShow(1, TmdbShow(1, "Show A"))
            fakeTime = baseTime + 1000
            cache.putShow(2, TmdbShow(2, "Show B"))

            // Advance past Show A's TTL but not Show B's
            fakeTime = baseTime + TmdbCache.TTL_MS + 500
            assertNull(cache.getShow(1))     // Show A expired
            assertNotNull(cache.getShow(2))  // Show B still valid (its TTL is TTL_MS - 500 ms away)
        }
    }

    @Nested
    @DisplayName("clear")
    inner class ClearTest {

        @Test
        fun `clears both show and episode caches`() {
            cache.putShow(1, TmdbShow(1, "Show"))
            cache.putEpisode(1, 1, 1, TmdbEpisode(1, "Ep", null, null, 1, 1))

            cache.clear()

            assertNull(cache.getShow(1))
            assertNull(cache.getEpisode(1, 1, 1))
        }

        @Test
        fun `allows new entries after clear`() {
            cache.putShow(1, TmdbShow(1, "Old"))
            cache.clear()

            val newShow = TmdbShow(1, "New")
            cache.putShow(1, newShow)
            assertEquals(newShow, cache.getShow(1))
        }
    }
}
