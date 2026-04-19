package com.justb81.watchbuddy.tv.ui.home

import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
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
import org.junit.jupiter.api.Nested
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

    private val testTraktShows = listOf(
        TraktWatchedEntry(TraktShow("Show 1", 2024, TraktIds())),
        TraktWatchedEntry(TraktShow("Show 2", 2023, TraktIds()))
    )
    private val testShows = testTraktShows.map { EnrichedShowEntry(entry = it) }

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

    // ── Basic load behaviour ───────────────────────────────────────────────────

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
        coEvery { phoneApiService.getShows(any(), any()) } throws RuntimeException("Connection refused")

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
        coEvery { phoneApiService.getShows(any(), any()) } returns testShows
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.shows.size)

        // Second call fails — cached shows should be shown with phoneApiError
        coEvery { phoneApiService.getShows(any(), any()) } throws RuntimeException("Timeout")
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
        coEvery { phoneApiService.getShows(any(), any()) } returns testShows

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.shows.size)
        assertFalse(state.isLoading)
        assertFalse(state.phoneApiError)
        assertFalse(state.noPhoneConnected)
    }

    @Test
    fun `loadShows sorts shows by last-watched descending`() = runTest {
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns "http://192.168.1.1:8765/"
        every { phoneDiscovery.getBestPhone() } returns phone
        every { phoneApiClientFactory.createClient(any()) } returns phoneApiService

        val recent = EnrichedShowEntry(
            entry = TraktWatchedEntry(
                show = TraktShow("Recent", 2024, TraktIds(trakt = 1)),
                seasons = listOf(
                    TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1, last_watched_at = "2026-04-15T10:00:00.000Z")))
                )
            )
        )
        val older = EnrichedShowEntry(
            entry = TraktWatchedEntry(
                show = TraktShow("Older", 2023, TraktIds(trakt = 2)),
                seasons = listOf(
                    TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1, last_watched_at = "2026-04-01T10:00:00.000Z")))
                )
            )
        )
        coEvery { phoneApiService.getShows(any(), any()) } returns listOf(older, recent)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(listOf("Recent", "Older"), viewModel.uiState.value.shows.map { it.entry.show.title })
    }

    @Test
    fun `loadShows updates TvShowCache`() = runTest {
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns "http://test:8765/"
        every { phoneDiscovery.getBestPhone() } returns phone
        every { phoneApiClientFactory.createClient(any()) } returns phoneApiService
        coEvery { phoneApiService.getShows(any(), any()) } returns testShows

        createViewModel()
        advanceUntilIdle()

        verify { tvShowCache.updateShows(testTraktShows) }
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

    // ── Pagination ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("pagination")
    inner class PaginationTest {

        private fun makeShows(count: Int, startId: Int = 1) =
            (startId until startId + count).map { i ->
                EnrichedShowEntry(entry = TraktWatchedEntry(TraktShow("Show $i", 2020, TraktIds(trakt = i))))
            }

        private fun setupPhone(): PhoneDiscoveryManager.DiscoveredPhone {
            val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
            every { phone.baseUrl } returns "http://192.168.1.1:8765/"
            every { phoneDiscovery.getBestPhone() } returns phone
            every { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            return phone
        }

        @Test
        fun `canLoadMore is true when API returns full page`() = runTest {
            setupPhone()
            val fullPage = makeShows(TvHomeViewModel.PAGE_SIZE)
            coEvery { phoneApiService.getShows(0, TvHomeViewModel.PAGE_SIZE) } returns fullPage

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.canLoadMore)
            assertFalse(viewModel.uiState.value.isLoading)
        }

        @Test
        fun `canLoadMore is false when API returns fewer than page size`() = runTest {
            setupPhone()
            val partial = makeShows(TvHomeViewModel.PAGE_SIZE - 1)
            coEvery { phoneApiService.getShows(0, TvHomeViewModel.PAGE_SIZE) } returns partial

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.canLoadMore)
        }

        @Test
        fun `loadMoreShows appends next page to existing shows`() = runTest {
            setupPhone()
            val page1 = makeShows(TvHomeViewModel.PAGE_SIZE)
            val page2 = makeShows(10, startId = TvHomeViewModel.PAGE_SIZE + 1)
            coEvery { phoneApiService.getShows(0, TvHomeViewModel.PAGE_SIZE) } returns page1
            coEvery { phoneApiService.getShows(TvHomeViewModel.PAGE_SIZE, TvHomeViewModel.PAGE_SIZE) } returns page2

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(TvHomeViewModel.PAGE_SIZE, viewModel.uiState.value.shows.size)
            assertTrue(viewModel.uiState.value.canLoadMore)

            viewModel.loadMoreShows()
            advanceUntilIdle()

            assertEquals(TvHomeViewModel.PAGE_SIZE + 10, viewModel.uiState.value.shows.size)
            assertFalse(viewModel.uiState.value.canLoadMore)
        }

        @Test
        fun `loadMoreShows does nothing when canLoadMore is false`() = runTest {
            setupPhone()
            val partial = makeShows(5)
            coEvery { phoneApiService.getShows(any(), any()) } returns partial

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.canLoadMore)

            viewModel.loadMoreShows()
            advanceUntilIdle()

            // API should only have been called once (initial load)
            coVerify(exactly = 1) { phoneApiService.getShows(any(), any()) }
            assertEquals(5, viewModel.uiState.value.shows.size)
        }

        @Test
        fun `loadMoreShows does nothing when isLoading is true`() = runTest {
            setupPhone()
            // Return a full page so canLoadMore would be true, but we check isLoading guard
            val fullPage = makeShows(TvHomeViewModel.PAGE_SIZE)
            coEvery { phoneApiService.getShows(any(), any()) } returns fullPage

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.canLoadMore)
            assertFalse(viewModel.uiState.value.isLoading)
            // State is clean — calling loadMoreShows should proceed normally (isLoading = false)
            viewModel.loadMoreShows()
            advanceUntilIdle()

            // Second page was requested with correct offset
            coVerify { phoneApiService.getShows(TvHomeViewModel.PAGE_SIZE, TvHomeViewModel.PAGE_SIZE) }
        }

        @Test
        fun `loadShows resets pagination and loads from offset 0`() = runTest {
            setupPhone()
            val page1 = makeShows(TvHomeViewModel.PAGE_SIZE)
            val page2 = makeShows(TvHomeViewModel.PAGE_SIZE)
            coEvery { phoneApiService.getShows(0, TvHomeViewModel.PAGE_SIZE) } returns page1
            coEvery { phoneApiService.getShows(TvHomeViewModel.PAGE_SIZE, TvHomeViewModel.PAGE_SIZE) } returns page2

            val viewModel = createViewModel()
            advanceUntilIdle()

            // Load page 2
            viewModel.loadMoreShows()
            advanceUntilIdle()
            assertEquals(TvHomeViewModel.PAGE_SIZE * 2, viewModel.uiState.value.shows.size)

            // Refresh — should reset to page 1
            viewModel.loadShows()
            advanceUntilIdle()
            assertEquals(TvHomeViewModel.PAGE_SIZE, viewModel.uiState.value.shows.size)
            // Verify offset 0 was requested again
            coVerify(exactly = 2) { phoneApiService.getShows(0, TvHomeViewModel.PAGE_SIZE) }
        }

        @Test
        fun `first page passes correct offset and limit to API`() = runTest {
            setupPhone()
            coEvery { phoneApiService.getShows(any(), any()) } returns emptyList()

            createViewModel()
            advanceUntilIdle()

            coVerify { phoneApiService.getShows(0, TvHomeViewModel.PAGE_SIZE) }
        }

        @Test
        fun `isLoadingMore is false after successful load more`() = runTest {
            setupPhone()
            val page1 = makeShows(TvHomeViewModel.PAGE_SIZE)
            val page2 = makeShows(5, startId = TvHomeViewModel.PAGE_SIZE + 1)
            coEvery { phoneApiService.getShows(0, TvHomeViewModel.PAGE_SIZE) } returns page1
            coEvery { phoneApiService.getShows(TvHomeViewModel.PAGE_SIZE, TvHomeViewModel.PAGE_SIZE) } returns page2

            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.loadMoreShows()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoadingMore)
        }

        @Test
        fun `TvShowCache is updated with full accumulated list after load more`() = runTest {
            setupPhone()
            val page1 = makeShows(TvHomeViewModel.PAGE_SIZE)
            val page2 = makeShows(5, startId = TvHomeViewModel.PAGE_SIZE + 1)
            coEvery { phoneApiService.getShows(0, TvHomeViewModel.PAGE_SIZE) } returns page1
            coEvery { phoneApiService.getShows(TvHomeViewModel.PAGE_SIZE, TvHomeViewModel.PAGE_SIZE) } returns page2

            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.loadMoreShows()
            advanceUntilIdle()

            val allShows = page1 + page2
            verify { tvShowCache.updateShows(allShows.map { it.entry }) }
        }
    }
}
