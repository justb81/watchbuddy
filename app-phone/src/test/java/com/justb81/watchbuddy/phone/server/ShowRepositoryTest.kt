package com.justb81.watchbuddy.phone.server

import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ShowRepository")
class ShowRepositoryTest {

    private val traktApi: TraktApiService = mockk()
    private val tokenRefreshManager: TokenRefreshManager = mockk()
    private lateinit var repository: ShowRepository

    private val testShows = listOf(
        TraktWatchedEntry(TraktShow("Show 1", 2024, TraktIds())),
        TraktWatchedEntry(TraktShow("Show 2", 2023, TraktIds()))
    )

    @BeforeEach
    fun setUp() {
        repository = ShowRepository(traktApi, tokenRefreshManager)
    }

    @Test
    fun `getShows fetches from API on first call`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows("Bearer test-token") } returns testShows

        val result = repository.getShows()
        assertEquals(2, result.size)
        assertEquals("Show 1", result[0].show.title)
        coVerify(exactly = 1) { traktApi.getWatchedShows(any()) }
    }

    @Test
    fun `getShows returns cached result on second call`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns testShows

        repository.getShows()
        repository.getShows()
        // API should only be called once due to caching
        coVerify(exactly = 1) { traktApi.getWatchedShows(any()) }
    }

    @Test
    fun `getShows returns empty list when token refresh fails`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns null

        val result = repository.getShows()
        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { traktApi.getWatchedShows(any()) }
    }

    @Test
    fun `getShows calls API with Bearer token`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "my-secret-token"
        coEvery { traktApi.getWatchedShows("Bearer my-secret-token") } returns testShows

        repository.getShows()
        coVerify { traktApi.getWatchedShows("Bearer my-secret-token") }
    }

    @Test
    fun `getShows returns empty list when API throws with no prior cache`() = runTest {
        every { tokenRepository.getAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("Network error")

        val result = repository.getShows()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getShows returns stale cached data when API throws after a successful fetch`() = runTest {
        every { tokenRepository.getAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns testShows

        // Prime the cache with a successful fetch
        repository.getShows()

        // Simulate cache TTL expiry by resetting lastFetch to 0 via reflection
        ShowRepository::class.java.getDeclaredField("lastFetch").apply {
            isAccessible = true
            setLong(repository, 0L)
        }

        // API now throws on the refresh attempt
        coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("Trakt unavailable")

        // Stale cached data should be returned instead of propagating the exception
        val result = repository.getShows()

        assertEquals(2, result.size)
        // API was retried (two total calls: initial fetch + failed refresh attempt)
        coVerify(exactly = 2) { traktApi.getWatchedShows(any()) }
    }

    @Test
    fun `getShows retries API on next call after a failure`() = runTest {
        every { tokenRepository.getAccessToken() } returns "test-token"
        // First call: API throws
        coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("Network error")
        repository.getShows()

        // Second call: API succeeds
        coEvery { traktApi.getWatchedShows(any()) } returns testShows
        val result = repository.getShows()

        // Both calls should have hit the API because lastFetch was not updated on failure
        coVerify(exactly = 2) { traktApi.getWatchedShows(any()) }
        assertEquals(2, result.size)
    }
}
