package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.model.WatchProviderEntry
import com.justb81.watchbuddy.core.model.WatchProviderResponse
import com.justb81.watchbuddy.core.model.WatchProviderResult
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.tv.discovery.InstalledAppsProbe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("WatchProvidersRepository")
class WatchProvidersRepositoryTest {

    private val tmdbApi: TmdbApiService = mockk()
    private val installedAppsProbe: InstalledAppsProbe = mockk()
    private val lastUsedRepo: LastUsedProviderRepository = mockk()
    private lateinit var repository: WatchProvidersRepository

    private val netflixEntry = WatchProviderEntry(
        providerId = 8,
        providerName = "Netflix",
        displayPriority = 1,
    )
    private val providerResponse = WatchProviderResponse(
        id = 42,
        results = mapOf(
            "DE" to WatchProviderResult(flatrate = listOf(netflixEntry))
        )
    )

    @BeforeEach
    fun setUp() {
        repository = WatchProvidersRepository(tmdbApi, installedAppsProbe, lastUsedRepo)
        every { installedAppsProbe.getInstalledPackages() } returns emptySet()
        every { lastUsedRepo.getLastUsedProviderId(any()) } returns null
        coEvery { tmdbApi.getWatchProviders(any(), any()) } returns providerResponse
    }

    @Test
    fun `getResolvedProviders fetches from API on first call`() = runTest {
        repository.getResolvedProviders(42, "DE", "api-key", showNonInstalled = true)

        coVerify(exactly = 1) { tmdbApi.getWatchProviders(42, "api-key") }
    }

    @Test
    fun `getResolvedProviders returns cached response on second call within TTL`() = runTest {
        repository.getResolvedProviders(42, "DE", "api-key", showNonInstalled = true)
        repository.getResolvedProviders(42, "DE", "api-key", showNonInstalled = true)

        coVerify(exactly = 1) { tmdbApi.getWatchProviders(any(), any()) }
    }

    @Test
    fun `getResolvedProviders fetches separately for different show ids`() = runTest {
        val response2 = WatchProviderResponse(id = 99, results = emptyMap())
        coEvery { tmdbApi.getWatchProviders(99, any()) } returns response2

        repository.getResolvedProviders(42, "DE", "api-key", showNonInstalled = true)
        repository.getResolvedProviders(99, "DE", "api-key", showNonInstalled = true)

        coVerify(exactly = 1) { tmdbApi.getWatchProviders(42, any()) }
        coVerify(exactly = 1) { tmdbApi.getWatchProviders(99, any()) }
    }

    @Test
    fun `getResolvedProviders fetches separately for different country codes`() = runTest {
        repository.getResolvedProviders(42, "DE", "api-key", showNonInstalled = true)
        repository.getResolvedProviders(42, "US", "api-key", showNonInstalled = true)

        coVerify(exactly = 2) { tmdbApi.getWatchProviders(42, any()) }
    }

    @Test
    fun `getResolvedProviders returns empty list when country has no providers`() = runTest {
        val (providers, pageUrl) = repository.getResolvedProviders(
            42, "XX", "api-key", showNonInstalled = true
        )

        assertEquals(emptyList<Any>(), providers)
        assertEquals(null, pageUrl)
    }

    @Test
    fun `getResolvedProviders propagates network exception`() = runTest {
        coEvery { tmdbApi.getWatchProviders(any(), any()) } throws RuntimeException("Network error")

        var caught: Throwable? = null
        try {
            repository.getResolvedProviders(42, "DE", "api-key", showNonInstalled = true)
        } catch (e: RuntimeException) {
            caught = e
        }

        assertEquals("Network error", caught?.message)
    }
}
