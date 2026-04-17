package com.justb81.watchbuddy.phone.server

import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ShowRepository")
class ShowRepositoryTest {

    private val traktApi: TraktApiService = mockk()
    private val tokenRefreshManager: TokenRefreshManager = mockk()
    private val tmdbApiService: TmdbApiService = mockk()
    private val settingsRepository: SettingsRepository = mockk()
    private lateinit var repository: ShowRepository

    private val testShows = listOf(
        TraktWatchedEntry(TraktShow("Show 1", 2024, TraktIds(trakt = 1, tmdb = 100))),
        TraktWatchedEntry(TraktShow("Show 2", 2023, TraktIds(trakt = 2, tmdb = 200)))
    )

    @BeforeEach
    fun setUp() {
        every { settingsRepository.getTmdbApiKey() } returns flowOf("")
        repository = ShowRepository(traktApi, tokenRefreshManager, tmdbApiService, settingsRepository)
    }

    @Test
    fun `getShows fetches from API on first call`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows("Bearer test-token") } returns testShows

        val result = repository.getShows()
        assertEquals(2, result.size)
        assertEquals("Show 1", result[0].entry.show.title)
        coVerify(exactly = 1) { traktApi.getWatchedShows(any()) }
    }

    @Test
    fun `getShows returns cached result on second call`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns testShows

        repository.getShows()
        repository.getShows()
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
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("Network error")

        val result = repository.getShows()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getShows returns stale cached data when API throws after a successful fetch`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns testShows

        repository.getShows()

        ShowRepository::class.java.getDeclaredField("lastFetch").apply {
            isAccessible = true
            setLong(repository, 0L)
        }

        coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("Trakt unavailable")

        val result = repository.getShows()

        assertEquals(2, result.size)
        coVerify(exactly = 2) { traktApi.getWatchedShows(any()) }
    }

    @Test
    fun `getShows retries API on next call after a failure`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("Network error")
        repository.getShows()

        coEvery { traktApi.getWatchedShows(any()) } returns testShows
        val result = repository.getShows()

        coVerify(exactly = 2) { traktApi.getWatchedShows(any()) }
        assertEquals(2, result.size)
    }

    @Test
    fun `getShows enriches entries with TMDB poster path when API key is set`() = runTest {
        every { settingsRepository.getTmdbApiKey() } returns flowOf("api-key")
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns testShows
        coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns TmdbShow(100, "Show 1", poster_path = "/one.jpg")
        coEvery { tmdbApiService.getShow(200, "api-key", any()) } returns TmdbShow(200, "Show 2", poster_path = "/two.jpg")

        val result = repository.getShows()

        assertEquals("/one.jpg", result[0].posterPath)
        assertEquals("/two.jpg", result[1].posterPath)
    }

    @Test
    fun `getShows tolerates per-show TMDB failures`() = runTest {
        every { settingsRepository.getTmdbApiKey() } returns flowOf("api-key")
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns testShows
        coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns TmdbShow(100, "Show 1", poster_path = "/one.jpg")
        coEvery { tmdbApiService.getShow(200, "api-key", any()) } throws RuntimeException("TMDB down for Show 2")

        val result = repository.getShows()

        assertEquals(2, result.size)
        assertEquals("/one.jpg", result[0].posterPath)
        assertNull(result[1].posterPath)
        assertNull(result[1].tmdb)
    }
}
