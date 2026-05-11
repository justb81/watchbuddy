package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PersistedShowCacheRepositoryTest {

    private fun makeEntry(title: String, traktId: Int) = EnrichedShowEntry(
        entry = TraktWatchedEntry(TraktShow(title, 2024, TraktIds(trakt = traktId)))
    )

    @Test
    fun `load returns null when no data has been saved`() = runTest {
        val repo = mockk<PersistedShowCacheRepository>()
        coEvery { repo.load() } returns null

        assertNull(repo.load())
    }

    @Test
    fun `load returns saved shows and timestamp after save`() = runTest {
        val repo = mockk<PersistedShowCacheRepository>()
        val shows = listOf(makeEntry("Breaking Bad", 1), makeEntry("Better Call Saul", 2))
        val savedAt = System.currentTimeMillis()
        coEvery { repo.load() } returns PersistedShowCacheRepository.CacheEntry(shows, savedAt)

        val result = repo.load()

        assertNotNull(result)
        assertEquals(2, result!!.shows.size)
        assertEquals("Breaking Bad", result.shows[0].entry.show.title)
        assertEquals("Better Call Saul", result.shows[1].entry.show.title)
        assertEquals(savedAt, result.savedAtMs)
    }

    @Test
    fun `load returns null when cached entry is missing`() = runTest {
        val repo = mockk<PersistedShowCacheRepository>()
        coEvery { repo.load() } returns null

        val result = repo.load()
        assertNull(result)
    }
}
