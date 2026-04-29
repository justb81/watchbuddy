package com.justb81.watchbuddy.phone.ui.home

import android.app.Application
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TmdbSeasonSummary
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.MainDispatcherRule
import com.justb81.watchbuddy.phone.TestFixtures
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

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
    private val tokenRefreshManager: TokenRefreshManager = mockk(relaxed = true)
    private val traktApiService: TraktApiService = mockk(relaxed = true)
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
        tokenRefreshManager = tokenRefreshManager,
        traktApiService = traktApiService,
        settingsRepository = settingsRepository,
        companionStateManager = companionStateManager,
        wifiStateProvider = wifiStateProvider
    )

    private fun enriched(title: String) =
        EnrichedShowEntry(entry = TestFixtures.traktWatchedEntry(show = TestFixtures.traktShow(title)))

    private fun enrichedWatched(title: String, lastWatchedAt: String) = EnrichedShowEntry(
        entry = TestFixtures.traktWatchedEntry(show = TestFixtures.traktShow(title)).copy(
            seasons = listOf(
                TraktWatchedSeason(
                    number = 1,
                    episodes = listOf(TraktWatchedEpisode(number = 1, last_watched_at = lastWatchedAt))
                )
            )
        )
    )

    /**
     * Show whose watched-episode count on S1 matches the TMDB aired count —
     * so `ShowProgressCalculator.isCompleted` returns true.
     */
    private fun enrichedCompleted(
        title: String,
        lastWatchedAt: String,
        episodeCount: Int = 3
    ) = EnrichedShowEntry(
        entry = TestFixtures.traktWatchedEntry(show = TestFixtures.traktShow(title)).copy(
            seasons = listOf(
                TraktWatchedSeason(
                    number = 1,
                    episodes = (1..episodeCount).map {
                        TraktWatchedEpisode(number = it, last_watched_at = lastWatchedAt)
                    }
                )
            )
        ),
        tmdb = TmdbProgressHint(
            seasons = listOf(TmdbSeasonSummary(season_number = 1, episode_count = episodeCount))
        )
    )

    /** Show with a TMDB hint that reports more aired episodes than the user has watched. */
    private fun enrichedInProgressWithHint(
        title: String,
        lastWatchedAt: String,
        watchedCount: Int = 1,
        airedCount: Int = 3
    ) = EnrichedShowEntry(
        entry = TestFixtures.traktWatchedEntry(show = TestFixtures.traktShow(title)).copy(
            seasons = listOf(
                TraktWatchedSeason(
                    number = 1,
                    episodes = (1..watchedCount).map {
                        TraktWatchedEpisode(number = it, last_watched_at = lastWatchedAt)
                    }
                )
            )
        ),
        tmdb = TmdbProgressHint(
            seasons = listOf(TmdbSeasonSummary(season_number = 1, episode_count = airedCount))
        )
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
            val httpEx = HttpException(retrofit2.Response.error<Any>(401, "".toResponseBody(null)))
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
            val httpEx = HttpException(retrofit2.Response.error<Any>(403, "".toResponseBody(null)))
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
                throw IOException("DataStore corrupted")
            }

            val vm = createViewModel()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.error)
        }
    }

    @Nested
    @DisplayName("partitionShows — 30-day Continue Watching window (#361)")
    inner class PartitionShowsTest {

        private val now = Instant.parse("2026-04-19T12:00:00Z")

        @Test
        fun `show watched 10 days ago goes into continueWatching`() {
            val vm = createViewModel()
            val tenDaysAgo = now.minus(10, ChronoUnit.DAYS).toString()
            val show = enrichedWatched("Recent", tenDaysAgo)

            val (cw, others) = vm.partitionShows(listOf(show), now)

            assertEquals(listOf(show), cw)
            assertTrue(others.isEmpty())
        }

        @Test
        fun `show watched 31 days ago goes into allShows`() {
            val vm = createViewModel()
            val thirtyOneDaysAgo = now.minus(31, ChronoUnit.DAYS).toString()
            val show = enrichedWatched("Old", thirtyOneDaysAgo)

            val (cw, others) = vm.partitionShows(listOf(show), now)

            assertTrue(cw.isEmpty())
            assertEquals(listOf(show), others)
        }

        @Test
        fun `show with no watch history goes into allShows`() {
            val vm = createViewModel()
            val show = enriched("Never")

            val (cw, others) = vm.partitionShows(listOf(show), now)

            assertTrue(cw.isEmpty())
            assertEquals(listOf(show), others)
        }

        @Test
        fun `allShows is sorted alphabetically case-insensitive`() {
            val vm = createViewModel()
            val shows = listOf(
                enriched("Zebra Show"),
                enriched("apple show"),
                enriched("Mango Show")
            )

            val (_, others) = vm.partitionShows(shows, now)

            assertEquals(listOf("apple show", "Mango Show", "Zebra Show"), others.map { it.entry.show.title })
        }

        @Test
        fun `continueWatching preserves order from input (last-watched DESC)`() {
            val vm = createViewModel()
            val fiveDaysAgo = now.minus(5, ChronoUnit.DAYS).toString()
            val tenDaysAgo = now.minus(10, ChronoUnit.DAYS).toString()
            val showA = enrichedWatched("A", fiveDaysAgo)
            val showB = enrichedWatched("B", tenDaysAgo)

            val (cw, _) = vm.partitionShows(listOf(showA, showB), now)

            assertEquals(listOf(showA, showB), cw)
        }

        @Test
        fun `show watched exactly on cutoff boundary is excluded from continueWatching`() {
            val vm = createViewModel()
            val exactlyThirtyDaysAgo = now.minus(30, ChronoUnit.DAYS).toString()
            val show = enrichedWatched("Edge", exactlyThirtyDaysAgo)

            val (cw, others) = vm.partitionShows(listOf(show), now)

            assertTrue(cw.isEmpty())
            assertEquals(listOf(show), others)
        }

        @Test
        fun `completed show watched recently goes into allShows, not continueWatching (#398)`() {
            val vm = createViewModel()
            val fiveDaysAgo = now.minus(5, ChronoUnit.DAYS).toString()
            val finished = enrichedCompleted("Finished", fiveDaysAgo)

            val (cw, others) = vm.partitionShows(listOf(finished), now)

            assertTrue(cw.isEmpty(), "completed shows must not appear in Continue Watching")
            assertEquals(listOf(finished), others)
        }

        @Test
        fun `completed show watched long ago stays in allShows (#398)`() {
            val vm = createViewModel()
            val fortyDaysAgo = now.minus(40, ChronoUnit.DAYS).toString()
            val finished = enrichedCompleted("Finished long ago", fortyDaysAgo)

            val (cw, others) = vm.partitionShows(listOf(finished), now)

            assertTrue(cw.isEmpty())
            assertEquals(listOf(finished), others)
        }

        @Test
        fun `in-progress show watched recently stays in continueWatching even with hint (#398)`() {
            // Regression: the completed filter must not over-match shows that
            // merely have a TMDB hint attached.
            val vm = createViewModel()
            val fiveDaysAgo = now.minus(5, ChronoUnit.DAYS).toString()
            val show = enrichedInProgressWithHint("Partial", fiveDaysAgo, watchedCount = 1, airedCount = 3)

            val (cw, others) = vm.partitionShows(listOf(show), now)

            assertEquals(listOf(show), cw)
            assertTrue(others.isEmpty())
        }

        @Test
        fun `recently-watched show with no TMDB hint stays in continueWatching (#398)`() {
            // Without a hint, isCompleted returns false — the 30-day window alone decides.
            val vm = createViewModel()
            val fiveDaysAgo = now.minus(5, ChronoUnit.DAYS).toString()
            val show = enrichedWatched("No hint", fiveDaysAgo)

            val (cw, others) = vm.partitionShows(listOf(show), now)

            assertEquals(listOf(show), cw)
            assertTrue(others.isEmpty())
        }

        @Test
        fun `allShows stays alphabetical when completed and old shows are mixed (#398)`() {
            val vm = createViewModel()
            val fiveDaysAgo = now.minus(5, ChronoUnit.DAYS).toString()
            val fortyDaysAgo = now.minus(40, ChronoUnit.DAYS).toString()
            val completedRecent = enrichedCompleted("zebra", fiveDaysAgo)
            val oldUnwatched = enrichedWatched("apple", fortyDaysAgo)
            val neverWatched = enriched("Mango")

            val (_, others) = vm.partitionShows(
                listOf(completedRecent, oldUnwatched, neverWatched),
                now
            )

            assertEquals(listOf("apple", "Mango", "zebra"), others.map { it.entry.show.title })
        }

        @Test
        fun `uiState continueWatching and allShows are populated after loadShows`() = runTest {
            val recentTs = now.minus(5, ChronoUnit.DAYS).toString()
            val oldTs = now.minus(40, ChronoUnit.DAYS).toString()
            val recentShow = enrichedWatched("Recent", recentTs)
            val oldShow = enrichedWatched("Old", oldTs)
            val neverShow = enriched("Never")

            every { tokenRepository.getAccessToken() } returns "token"
            stubShows(listOf(recentShow, oldShow, neverShow))

            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            // Recent show (5 days ago) should be in continueWatching
            // Old show (40 days) and never-watched should be in allShows
            assertEquals(1, state.continueWatching.size)
            assertEquals(2, state.allShows.size)
            assertEquals("Recent", state.continueWatching[0].entry.show.title)
            // allShows sorted alphabetically: "Never" before "Old"
            assertEquals(listOf("Never", "Old"), state.allShows.map { it.entry.show.title })
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
