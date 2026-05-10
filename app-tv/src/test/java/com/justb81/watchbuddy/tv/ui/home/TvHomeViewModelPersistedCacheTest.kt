package com.justb81.watchbuddy.tv.ui.home

import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.data.PersistedShowCacheRepository
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.data.TvShowCache
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TvHomeViewModel — persisted cache")
class TvHomeViewModelPersistedCacheTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private val phoneDiscovery: PhoneDiscoveryManager = mockk()
    private val phoneApiClientFactory: PhoneApiClientFactory = mockk()
    private val tvShowCache: TvShowCache = mockk(relaxed = true)
    private val preferencesRepository: StreamingPreferencesRepository = mockk()
    private val persistedShowCacheRepository: PersistedShowCacheRepository = mockk()
    private val phonesFlow = MutableStateFlow<List<PhoneDiscoveryManager.DiscoveredPhone>>(emptyList())
    private val phoneApiService: PhoneApiService = mockk()

    private val testShows = listOf(
        EnrichedShowEntry(entry = TraktWatchedEntry(TraktShow("Show 1", 2024, TraktIds(trakt = 1)))),
        EnrichedShowEntry(entry = TraktWatchedEntry(TraktShow("Show 2", 2023, TraktIds(trakt = 2)))),
    )

    @BeforeEach
    fun setUp() {
        every { phoneDiscovery.discoveredPhones } returns phonesFlow
        every { phoneDiscovery.startDiscovery() } just runs
        every { phoneDiscovery.stopDiscovery() } just runs
        every { phoneDiscovery.setEnabled(any()) } just runs
        every { phoneDiscovery.getBestPhone() } returns null
        every { preferencesRepository.isPhoneDiscoveryEnabled } returns flowOf(true)
        coEvery { persistedShowCacheRepository.save(any()) } just runs
        coEvery { persistedShowCacheRepository.load() } returns null
    }

    private fun createViewModel() = TvHomeViewModel(
        phoneDiscovery,
        phoneApiClientFactory,
        tvShowCache,
        preferencesRepository,
        persistedShowCacheRepository,
    )

    @Test
    fun `successful load persists shows to repository`() = runTest {
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns "http://192.168.1.1:8765/"
        every { phone.bearerToken } returns null
        every { phoneDiscovery.getBestPhone() } returns phone
        every { phoneApiClientFactory.createClient(any(), anyNullable()) } returns phoneApiService
        coEvery { phoneApiService.getShows(any(), any()) } returns testShows

        createViewModel()
        advanceUntilIdle()

        coVerify { persistedShowCacheRepository.save(any()) }
    }

    @Test
    fun `isShowingStaleCache is false after successful live load`() = runTest {
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns "http://192.168.1.1:8765/"
        every { phone.bearerToken } returns null
        every { phoneDiscovery.getBestPhone() } returns phone
        every { phoneApiClientFactory.createClient(any(), anyNullable()) } returns phoneApiService
        coEvery { phoneApiService.getShows(any(), any()) } returns testShows

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isShowingStaleCache)
    }

    @Test
    fun `persisted cache is shown when phone offline and cache is fresh`() = runTest {
        val savedAtMs = System.currentTimeMillis() - 30 * 60 * 1000L // 30 minutes ago
        coEvery { persistedShowCacheRepository.load() } returns
            PersistedShowCacheRepository.CacheEntry(testShows, savedAtMs)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.noPhoneConnected)
        assertTrue(state.isShowingStaleCache)
        assertEquals(2, state.shows.size)
    }

    @Test
    fun `persisted cache is not shown when cache is expired`() = runTest {
        val expiredTs = System.currentTimeMillis() - 2 * 60 * 60 * 1000L // 2 hours ago
        coEvery { persistedShowCacheRepository.load() } returns
            PersistedShowCacheRepository.CacheEntry(testShows, expiredTs)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.noPhoneConnected)
        assertFalse(state.isShowingStaleCache)
        assertTrue(state.shows.isEmpty())
    }

    @Test
    fun `stale banner shown when phone API error and fresh persisted cache available`() = runTest {
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns "http://192.168.1.1:8765/"
        every { phone.bearerToken } returns null
        every { phoneDiscovery.getBestPhone() } returns phone
        every { phoneApiClientFactory.createClient(any(), anyNullable()) } returns phoneApiService
        coEvery { phoneApiService.getShows(any(), any()) } throws RuntimeException("Connection refused")

        val savedAtMs = System.currentTimeMillis() - 20 * 60 * 1000L // 20 minutes ago
        coEvery { persistedShowCacheRepository.load() } returns
            PersistedShowCacheRepository.CacheEntry(testShows, savedAtMs)

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.phoneApiError)
        assertTrue(state.isShowingStaleCache)
        assertEquals(2, state.shows.size)
    }

    @Test
    fun `isShowingStaleCache resets to false when live data loads after phone reconnects`() = runTest {
        val savedAtMs = System.currentTimeMillis() - 30 * 60 * 1000L
        coEvery { persistedShowCacheRepository.load() } returns
            PersistedShowCacheRepository.CacheEntry(testShows, savedAtMs)

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isShowingStaleCache)

        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns "http://192.168.1.1:8765/"
        every { phone.bearerToken } returns null
        every { phoneDiscovery.getBestPhone() } returns phone
        every { phoneApiClientFactory.createClient(any(), anyNullable()) } returns phoneApiService
        val freshShows = listOf(
            EnrichedShowEntry(entry = TraktWatchedEntry(TraktShow("Fresh Show", 2025, TraktIds(trakt = 99))))
        )
        coEvery { phoneApiService.getShows(any(), any()) } returns freshShows

        viewModel.loadShows()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isShowingStaleCache)
        assertEquals(1, state.shows.size)
        assertEquals("Fresh Show", state.shows[0].entry.show.title)
    }

    @Test
    fun `no cache and no phone shows empty state without stale banner`() = runTest {
        coEvery { persistedShowCacheRepository.load() } returns null

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.noPhoneConnected)
        assertFalse(state.isShowingStaleCache)
        assertTrue(state.shows.isEmpty())
    }

    @Test
    fun `fallback TTL constant is at least 1 hour`() {
        assertTrue(TvHomeViewModel.FALLBACK_CACHE_TTL.toHours() >= 1)
    }
}
