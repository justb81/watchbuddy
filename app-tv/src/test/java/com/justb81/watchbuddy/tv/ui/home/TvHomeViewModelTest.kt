package com.justb81.watchbuddy.tv.ui.home

import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.data.TvShowCache
import com.justb81.watchbuddy.tv.data.UserSessionRepository
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TvHomeViewModel")
class TvHomeViewModelTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private val phoneDiscovery: PhoneDiscoveryManager = mockk()
    private val phoneApiClientFactory: PhoneApiClientFactory = mockk()
    private val userSessionRepository: UserSessionRepository = mockk()
    private val tvShowCache: TvShowCache = mockk(relaxed = true)
    private val phonesFlow = MutableStateFlow<List<PhoneDiscoveryManager.DiscoveredPhone>>(emptyList())
    private val phoneApiService: PhoneApiService = mockk()

    private val testShows = listOf(
        TraktWatchedEntry(TraktShow("Show 1", 2024, TraktIds())),
        TraktWatchedEntry(TraktShow("Show 2", 2023, TraktIds()))
    )

    @BeforeEach
    fun setUp() {
        every { phoneDiscovery.discoveredPhones } returns phonesFlow
        every { userSessionRepository.selectedUserIds } returns flowOf(emptySet())
        every { phoneDiscovery.startDiscovery() } just runs
        every { phoneDiscovery.stopDiscovery() } just runs
        every { phoneDiscovery.getBestPhone() } returns null
    }

    private fun createViewModel(): TvHomeViewModel {
        return TvHomeViewModel(phoneDiscovery, phoneApiClientFactory, userSessionRepository, tvShowCache)
    }

    @Test
    fun `loadShows sets noPhoneConnected when no phone and no cache`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.noPhoneConnected)
        assertFalse(state.phoneApiError)
    }

    @Test
    fun `loadShows sets phoneApiError (not noPhoneConnected) when phone found but API fails`() = runTest {
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns "http://192.168.1.1:8765/"
        every { phoneDiscovery.getBestPhone() } returns phone
        every { phoneApiClientFactory.createClient(any()) } returns phoneApiService
        coEvery { phoneApiService.getShows() } throws RuntimeException("Connection refused")

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.phoneApiError)
        assertFalse(state.noPhoneConnected)
    }

    @Test
    fun `loadShows shows cached data with phoneApiError when phone found but API fails`() = runTest {
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns "http://192.168.1.1:8765/"
        every { phoneApiClientFactory.createClient(any()) } returns phoneApiService

        // First call succeeds and populates cache
        every { phoneDiscovery.getBestPhone() } returns phone
        coEvery { phoneApiService.getShows() } returns testShows
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.shows.size)

        // Second call fails — cached shows should be shown with phoneApiError
        coEvery { phoneApiService.getShows() } throws RuntimeException("Timeout")
        viewModel.loadShows()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.phoneApiError)
        assertFalse(state.noPhoneConnected)
        assertEquals(2, state.shows.size)
    }

    @Test
    fun `loadShows fetches from best phone`() = runTest {
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns "http://192.168.1.1:8765/"
        every { phoneDiscovery.getBestPhone() } returns phone
        every { phoneApiClientFactory.createClient("http://192.168.1.1:8765/") } returns phoneApiService
        coEvery { phoneApiService.getShows() } returns testShows

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.shows.size)
        assertEquals("Show 1", state.shows[0].show.title)
        assertFalse(state.isLoading)
        assertFalse(state.phoneApiError)
        assertFalse(state.noPhoneConnected)
    }

    @Test
    fun `loadShows updates TvShowCache`() = runTest {
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns "http://test:8765/"
        every { phoneDiscovery.getBestPhone() } returns phone
        every { phoneApiClientFactory.createClient(any()) } returns phoneApiService
        coEvery { phoneApiService.getShows() } returns testShows

        createViewModel()
        advanceUntilIdle()

        verify { tvShowCache.updateShows(testShows) }
    }

    @Test
    fun `observePhones updates connected phone count`() = runTest {
        val viewModel = createViewModel()

        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.capability } returns mockk(relaxed = true)
        phonesFlow.value = listOf(phone)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.connectedPhones)
    }

    @Test
    fun `onCleared stops discovery`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Trigger onCleared via reflection
        val method = viewModel.javaClass.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(viewModel)

        verify { phoneDiscovery.stopDiscovery() }
    }

    @Test
    fun `init starts discovery`() = runTest {
        createViewModel()
        verify { phoneDiscovery.startDiscovery() }
    }
}
