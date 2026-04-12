package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TvShowCache")
class TvShowCacheTest {

    private lateinit var cache: TvShowCache

    private val shows = listOf(
        TraktWatchedEntry(TraktShow("Show 1", 2024, TraktIds())),
        TraktWatchedEntry(TraktShow("Show 2", 2023, TraktIds()))
    )

    @BeforeEach
    fun setUp() {
        cache = TvShowCache()
    }

    @Test
    fun `getCachedShows returns empty list initially`() {
        assertTrue(cache.getCachedShows().isEmpty())
    }

    @Test
    fun `updateShows stores shows`() {
        cache.updateShows(shows)
        assertEquals(2, cache.getCachedShows().size)
        assertEquals("Show 1", cache.getCachedShows()[0].show.title)
    }

    @Test
    fun `getCachedShows returns stored shows`() {
        cache.updateShows(shows)
        val result = cache.getCachedShows()
        assertEquals(shows, result)
    }

    @Test
    fun `updateShows replaces previous shows`() {
        cache.updateShows(shows)
        val newShows = listOf(TraktWatchedEntry(TraktShow("New Show", 2025, TraktIds())))
        cache.updateShows(newShows)
        assertEquals(1, cache.getCachedShows().size)
        assertEquals("New Show", cache.getCachedShows()[0].show.title)
    }

    @Test
    fun `updateShows with empty list clears cache`() {
        cache.updateShows(shows)
        cache.updateShows(emptyList())
        assertTrue(cache.getCachedShows().isEmpty())
    }
}
