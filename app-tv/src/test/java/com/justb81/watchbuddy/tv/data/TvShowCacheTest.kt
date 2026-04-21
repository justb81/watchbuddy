package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TmdbEpisodeSummary
import com.justb81.watchbuddy.core.model.TmdbProgressHint
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

    @Test
    fun `updateEnrichedShows stores shows and hints keyed by trakt id`() {
        val ids = TraktIds(trakt = 42)
        val hint = TmdbProgressHint(
            nextAired = TmdbEpisodeSummary(season_number = 4, episode_number = 7, air_date = "2024-01-01")
        )
        val enriched = EnrichedShowEntry(
            entry = TraktWatchedEntry(TraktShow("Breaking Bad", 2008, ids)),
            tmdb = hint
        )

        cache.updateEnrichedShows(listOf(enriched))

        assertEquals(1, cache.getCachedShows().size)
        assertEquals("Breaking Bad", cache.getCachedShows()[0].show.title)
        assertEquals(hint, cache.getHint(ids))
    }

    @Test
    fun `getHint returns null when no trakt id on lookup`() {
        val hint = TmdbProgressHint(status = "Returning Series")
        val enriched = EnrichedShowEntry(
            entry = TraktWatchedEntry(TraktShow("Anon", 2020, TraktIds(trakt = 1))),
            tmdb = hint
        )
        cache.updateEnrichedShows(listOf(enriched))

        assertNull(cache.getHint(TraktIds(tmdb = 999)))
    }

    @Test
    fun `updateShows clears previously cached hints`() {
        val ids = TraktIds(trakt = 42)
        cache.updateEnrichedShows(listOf(
            EnrichedShowEntry(
                entry = TraktWatchedEntry(TraktShow("Show", 2020, ids)),
                tmdb = TmdbProgressHint(status = "Ended")
            )
        ))

        cache.updateShows(listOf(TraktWatchedEntry(TraktShow("Show", 2020, ids))))

        assertNull(cache.getHint(ids))
    }
}
