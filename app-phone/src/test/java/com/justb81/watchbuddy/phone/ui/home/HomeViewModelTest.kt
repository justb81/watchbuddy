package com.justb81.watchbuddy.phone.ui.home

import android.app.Application
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.MainDispatcherRule
import com.justb81.watchbuddy.phone.TestFixtures
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionStateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@DisplayName("HomeViewModel")
class HomeViewModelTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private val application: Application = mockk(relaxed = true)
    private val traktApi: TraktApiService = mockk(relaxed = true)
    private val tokenRepository: TokenRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk(relaxed = true)
    private val companionStateManager = CompanionStateManager()

    @BeforeEach
    fun setUp() {
        every { settingsRepository.settings } returns flowOf(AppSettings())
        every { settingsRepository.getTmdbApiKey() } returns flowOf("")
        every { tokenRepository.getAccessToken() } returns null
    }

    private fun createViewModel(): HomeViewModel = HomeViewModel(
        application = application,
        traktApi = traktApi,
        tokenRepository = tokenRepository,
        settingsRepository = settingsRepository,
        tmdbApiService = tmdbApiService,
        companionStateManager = companionStateManager
    )

    @Nested
    @DisplayName("loadShows")
    inner class LoadShowsTest {

        @Test
        fun `sets error and clears loading when no access token`() = runTest {
            every { tokenRepository.getAccessToken() } returns null

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertTrue(vm.uiState.value.shows.isEmpty())
            assertNotNull(vm.uiState.value.error)
        }

        @Test
        fun `loads shows successfully when token is available`() = runTest {
            val shows = listOf(
                TestFixtures.traktWatchedEntry(show = TestFixtures.traktShow("Breaking Bad")),
                TestFixtures.traktWatchedEntry(show = TestFixtures.traktShow("The Wire"))
            )
            every { tokenRepository.getAccessToken() } returns "valid-token"
            coEvery { traktApi.getWatchedShows("Bearer valid-token") } returns shows

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertEquals(shows, vm.uiState.value.shows)
            assertNull(vm.uiState.value.error)
        }

        @Test
        fun `sets lastSyncTime after successful load`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            coEvery { traktApi.getWatchedShows(any()) } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.lastSyncTime)
        }

        @Test
        fun `sets error and clears loading when API throws`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("Network error")

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertTrue(vm.uiState.value.shows.isEmpty())
            assertNotNull(vm.uiState.value.error)
        }

        @Test
        fun `clears error on successful reload after failure`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("fail")

            val vm = createViewModel()
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.error)

            val shows = listOf(TestFixtures.traktWatchedEntry())
            coEvery { traktApi.getWatchedShows(any()) } returns shows

            vm.loadShows()
            advanceUntilIdle()

            assertNull(vm.uiState.value.error)
            assertEquals(shows, vm.uiState.value.shows)
        }

        @Test
        fun `empty show list is a valid success result`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            coEvery { traktApi.getWatchedShows(any()) } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertTrue(vm.uiState.value.shows.isEmpty())
            assertNull(vm.uiState.value.error)
        }
    }

    @Nested
    @DisplayName("sync")
    inner class SyncTest {

        @Test
        fun `sync reloads and reflects updated show list`() = runTest {
            val initialShows = listOf(TestFixtures.traktWatchedEntry(show = TestFixtures.traktShow("Show A")))
            val updatedShows = listOf(
                TestFixtures.traktWatchedEntry(show = TestFixtures.traktShow("Show A")),
                TestFixtures.traktWatchedEntry(show = TestFixtures.traktShow("Show B"))
            )
            every { tokenRepository.getAccessToken() } returns "valid-token"
            coEvery { traktApi.getWatchedShows(any()) } returns initialShows

            val vm = createViewModel()
            advanceUntilIdle()
            assertEquals(initialShows, vm.uiState.value.shows)

            coEvery { traktApi.getWatchedShows(any()) } returns updatedShows
            vm.sync()
            advanceUntilIdle()

            assertEquals(updatedShows, vm.uiState.value.shows)
        }

        @Test
        fun `sync clears error from previous failed load`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("fail")

            val vm = createViewModel()
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.error)

            coEvery { traktApi.getWatchedShows(any()) } returns emptyList()
            vm.sync()
            advanceUntilIdle()

            assertNull(vm.uiState.value.error)
        }
    }

    @Nested
    @DisplayName("companion service auto-start")
    inner class CompanionServiceTest {

        @Test
        fun `does not crash when companionEnabled is true`() = runTest {
            every { settingsRepository.settings } returns flowOf(AppSettings(companionEnabled = true))

            val vm = createViewModel()
            advanceUntilIdle()

            // Application is a relaxed mock — startForegroundService() is a no-op.
            // Reaching here without an exception confirms the code path executes safely.
            assertNotNull(vm)
        }

        @Test
        fun `does not crash when companionEnabled is false`() = runTest {
            every { settingsRepository.settings } returns flowOf(AppSettings(companionEnabled = false))

            val vm = createViewModel()
            advanceUntilIdle()

            assertNotNull(vm)
        }
    }
}
