package com.justb81.watchbuddy.phone.ui.home

import android.app.Application
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.phone.MainDispatcherRule
import com.justb81.watchbuddy.phone.TestFixtures
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.network.WifiStateProvider
import com.justb81.watchbuddy.phone.server.ShowRepository
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionStateManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import okhttp3.ResponseBody
import retrofit2.HttpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
    private val showRepository: ShowRepository = mockk(relaxed = true)
    private val tokenRepository: TokenRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val companionStateManager = CompanionStateManager()
    private val wifiStateProvider: WifiStateProvider = mockk(relaxed = true)
    private val wifiFlow = MutableStateFlow(true)
    private val showsFlow = MutableStateFlow<List<EnrichedShowEntry>>(emptyList())

    /**
     * Helper for tests that previously did `coEvery { showRepository.getShows() } returns shows`.
     * Mirrors what the real repo does: both returns the list and emits it to the reactive flow
     * that `HomeViewModel.observeShows()` collects.
     */
    private fun stubShows(shows: List<EnrichedShowEntry>) {
        coEvery { showRepository.getShows() } coAnswers {
            showsFlow.value = shows
            shows
        }
    }

    private fun stubShowsThrows(throwable: Throwable) {
        coEvery { showRepository.getShows() } throws throwable
    }

    @BeforeEach
    fun setUp() {
        every { settingsRepository.settings } returns flowOf(AppSettings())
        every { settingsRepository.getTmdbApiKey() } returns flowOf("")
        every { tokenRepository.getAccessToken() } returns null
        showsFlow.value = emptyList()
        every { showRepository.shows } returns showsFlow
        stubShows(emptyList())
        wifiFlow.value = true
        every { wifiStateProvider.isOnWifi } returns wifiFlow
    }

    private fun createViewModel(): HomeViewModel = HomeViewModel(
        application = application,
        showRepository = showRepository,
        tokenRepository = tokenRepository,
        settingsRepository = settingsRepository,
        companionStateManager = companionStateManager,
        wifiStateProvider = wifiStateProvider
    )

    private fun enriched(title: String) =
        EnrichedShowEntry(entry = TestFixtures.traktWatchedEntry(show = TestFixtures.traktShow(title)))

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
            val shows = listOf(enriched("Breaking Bad"), enriched("The Wire"))
            every { tokenRepository.getAccessToken() } returns "valid-token"
            stubShows(shows)

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertEquals(shows, vm.uiState.value.shows)
            assertNull(vm.uiState.value.error)
        }

        @Test
        fun `sets lastSyncTime after successful load`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            stubShows(emptyList())

            val vm = createViewModel()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.lastSyncTime)
        }

        @Test
        fun `sets error and clears loading when repository throws`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            stubShowsThrows(RuntimeException("Network error"))

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertTrue(vm.uiState.value.shows.isEmpty())
            assertNotNull(vm.uiState.value.error)
        }

        @Test
        fun `shows auth error message on HTTP 401`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            val httpEx = HttpException(retrofit2.Response.error<Any>(401, ResponseBody.create(null, "")))
            stubShowsThrows(httpEx)
            every { application.getString(com.justb81.watchbuddy.R.string.home_sync_failed_auth) } returns "Session expired"

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertEquals("Session expired", vm.uiState.value.error)
        }

        @Test
        fun `shows auth error message on HTTP 403`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            val httpEx = HttpException(retrofit2.Response.error<Any>(403, ResponseBody.create(null, "")))
            stubShowsThrows(httpEx)
            every { application.getString(com.justb81.watchbuddy.R.string.home_sync_failed_auth) } returns "Session expired"

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertEquals("Session expired", vm.uiState.value.error)
        }

        @Test
        fun `clears error on successful reload after failure`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            stubShowsThrows(RuntimeException("fail"))

            val vm = createViewModel()
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.error)

            val shows = listOf(enriched("Test"))
            stubShows(shows)

            vm.loadShows()
            advanceUntilIdle()

            assertNull(vm.uiState.value.error)
            assertEquals(shows, vm.uiState.value.shows)
        }

        @Test
        fun `empty show list is a valid success result`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            stubShows(emptyList())

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
            val initialShows = listOf(enriched("Show A"))
            val updatedShows = listOf(enriched("Show A"), enriched("Show B"))
            every { tokenRepository.getAccessToken() } returns "valid-token"
            stubShows(initialShows)

            val vm = createViewModel()
            advanceUntilIdle()
            assertEquals(initialShows, vm.uiState.value.shows)

            stubShows(updatedShows)
            vm.sync()
            advanceUntilIdle()

            assertEquals(updatedShows, vm.uiState.value.shows)
        }

        @Test
        fun `sync clears error from previous failed load`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            stubShowsThrows(RuntimeException("fail"))

            val vm = createViewModel()
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.error)

            stubShows(emptyList())
            vm.sync()
            advanceUntilIdle()

            assertNull(vm.uiState.value.error)
        }

        @Test
        fun `sync resets isSyncing to false after successful load`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            stubShows(emptyList())

            val vm = createViewModel()
            advanceUntilIdle()

            vm.sync()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isSyncing)
        }

        @Test
        fun `sync resets isSyncing to false after failed load`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            stubShowsThrows(RuntimeException("network error"))

            val vm = createViewModel()
            advanceUntilIdle()

            vm.sync()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isSyncing)
        }

        @Test
        fun `sync calls invalidateCache to bypass TTL`() = runTest {
            every { tokenRepository.getAccessToken() } returns "valid-token"
            stubShows(emptyList())

            val vm = createViewModel()
            advanceUntilIdle()

            vm.sync()
            advanceUntilIdle()

            coVerify(exactly = 1) { showRepository.invalidateCache() }
        }
    }

    @Nested
    @DisplayName("Init resilience — no force close on home screen open")
    inner class InitResilience {

        @Test
        fun `ViewModel creation does not throw when isTokenValid throws SecurityException`() = runTest {
            every { tokenRepository.isTokenValid() } throws
                SecurityException("Keystore operation failed")

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.canWatch)
        }

        @Test
        fun `ViewModel creation does not throw when getAccessToken throws SecurityException`() = runTest {
            every { tokenRepository.getAccessToken() } throws
                SecurityException("Keystore operation failed")

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.canWatch)
            assertNotNull(vm.uiState.value.error)
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

    @Nested
    @DisplayName("observeCompanionState error handling")
    inner class CompanionStateErrorTest {

        @Test
        fun `sets error when settings property throws on access`() = runTest {
            every { settingsRepository.settings } throws RuntimeException("Settings unavailable")

            val vm = createViewModel()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.error)
        }

        @Test
        fun `sets error when settings flow emits error during collection`() = runTest {
            every { settingsRepository.settings } returns flow {
                throw RuntimeException("DataStore corrupted")
            }

            val vm = createViewModel()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.error)
        }
    }

    @Nested
    @DisplayName("Wi-Fi gate for companion toggle (#278)")
    inner class WifiGate {

        @Test
        fun `canStartCompanion is false when off Wi-Fi even with Trakt and TMDB ready`() = runTest {
            every { tokenRepository.isTokenValid() } returns true
            every { settingsRepository.getTmdbApiKey() } returns flowOf("tmdb-key")
            wifiFlow.value = false

            val vm = createViewModel()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.canWatch)
            assertFalse(vm.uiState.value.isOnWifi)
            assertFalse(vm.uiState.value.canStartCompanion)
        }

        @Test
        fun `canStartCompanion is true when Trakt, TMDB, and Wi-Fi are all ready`() = runTest {
            every { tokenRepository.isTokenValid() } returns true
            every { settingsRepository.getTmdbApiKey() } returns flowOf("tmdb-key")
            wifiFlow.value = true

            val vm = createViewModel()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.canStartCompanion)
        }

        @Test
        fun `toggleWatchingTv(true) is a no-op when off Wi-Fi`() = runTest {
            wifiFlow.value = false
            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleWatchingTv(true)
            advanceUntilIdle()

            coVerify(exactly = 0) { settingsRepository.setCompanionEnabled(true) }
        }

        @Test
        fun `toggleWatchingTv(true) is allowed on Wi-Fi`() = runTest {
            wifiFlow.value = true
            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleWatchingTv(true)
            advanceUntilIdle()

            coVerify(exactly = 1) { settingsRepository.setCompanionEnabled(true) }
        }

        @Test
        fun `running companion auto-stops when Wi-Fi is lost`() = runTest {
            every { settingsRepository.settings } returns flowOf(AppSettings(companionEnabled = true))
            wifiFlow.value = true

            val vm = createViewModel()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isWatchingTv)

            wifiFlow.value = false
            advanceUntilIdle()

            coVerify { settingsRepository.setCompanionEnabled(false) }
        }

        @Test
        fun `canStartCompanion recovers when Wi-Fi returns`() = runTest {
            every { tokenRepository.isTokenValid() } returns true
            every { settingsRepository.getTmdbApiKey() } returns flowOf("tmdb-key")
            wifiFlow.value = false

            val vm = createViewModel()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.canStartCompanion)

            wifiFlow.value = true
            advanceUntilIdle()

            assertTrue(vm.uiState.value.canStartCompanion)
        }
    }
}
