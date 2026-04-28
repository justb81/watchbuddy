package com.justb81.watchbuddy.phone.ui.home

import android.app.Application
import com.justb81.watchbuddy.core.model.AmbiguousCandidate
import com.justb81.watchbuddy.core.model.AmbiguousScrobbleEvent
import com.justb81.watchbuddy.core.model.PlaybackTick
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.MainDispatcherRule
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.network.WifiStateProvider
import com.justb81.watchbuddy.phone.server.ShowRepository
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionStateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("HomeViewModel — Ambiguous scrobble prompt (#474)")
class HomeViewModelAmbiguousTest {

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
    private val wifiStateProvider: WifiStateProvider = mockk(relaxed = true)

    // Use a real CompanionStateManager so flows propagate naturally.
    private val companionStateManager = CompanionStateManager()

    @BeforeEach
    fun setUp() {
        every { settingsRepository.settings } returns flowOf(AppSettings())
        every { settingsRepository.getTmdbApiKey() } returns flowOf("")
        every { tokenRepository.getAccessToken() } returns null
        every { showRepository.shows } returns MutableStateFlow(emptyList())
        coEvery { showRepository.getShows() } returns emptyList()
        every { wifiStateProvider.isOnWifi } returns MutableStateFlow(true)
    }

    private fun createViewModel() = HomeViewModel(
        application = application,
        showRepository = showRepository,
        tokenRepository = tokenRepository,
        tokenRefreshManager = tokenRefreshManager,
        traktApiService = traktApiService,
        settingsRepository = settingsRepository,
        companionStateManager = companionStateManager,
        wifiStateProvider = wifiStateProvider,
    )

    private fun makeEvent(sessionKey: String = "sess-1") = AmbiguousScrobbleEvent(
        sessionKey = sessionKey,
        packageName = "com.netflix.mediaclient",
        candidates = listOf(
            AmbiguousCandidate(
                show = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1)),
                episode = TraktEpisode(season = 1, number = 1),
                score = 0.55f,
                sourceLabel = "library",
            )
        ),
        tick = PlaybackTick(state = PlaybackTick.STATE_PLAYING, positionMs = 1000L, durationMs = 45 * 60_000L, capturedAtMs = 0L),
        capturedAtMs = System.currentTimeMillis(),
    )

    @Nested
    @DisplayName("pendingAmbiguousPrompt state")
    inner class PendingPromptState {

        @Test
        fun `initial state has null pendingAmbiguousPrompt`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()
            assertNull(vm.uiState.value.pendingAmbiguousPrompt)
        }

        @Test
        fun `pendingPrompt set in CompanionStateManager appears in uiState`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            val event = makeEvent()
            companionStateManager.onAmbiguousPrompt(event)
            advanceUntilIdle()

            assertEquals(event, vm.uiState.value.pendingAmbiguousPrompt)
        }

        @Test
        fun `pendingPrompt cleared from CompanionStateManager clears uiState`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            val event = makeEvent()
            companionStateManager.onAmbiguousPrompt(event)
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.pendingAmbiguousPrompt)

            companionStateManager.clearPrompt(event.sessionKey)
            advanceUntilIdle()

            assertNull(vm.uiState.value.pendingAmbiguousPrompt)
        }
    }

    @Nested
    @DisplayName("selectCandidate()")
    inner class SelectCandidate {

        @Test
        fun `resolves prompt and clears pendingAmbiguousPrompt`() = runTest {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token-abc"
            coEvery { traktApiService.scrobbleStart(any(), any()) } returns mockk(relaxed = true)

            val vm = createViewModel()
            advanceUntilIdle()

            val event = makeEvent()
            companionStateManager.onAmbiguousPrompt(event)
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.pendingAmbiguousPrompt)

            vm.selectCandidate(event, event.candidates.first())
            advanceUntilIdle()

            assertNull(vm.uiState.value.pendingAmbiguousPrompt)
        }

        @Test
        fun `selectCandidate with missing episode does nothing`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            val noEpisodeCandidate = AmbiguousCandidate(
                show = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1)),
                episode = null,
                score = 0.55f,
                sourceLabel = "library",
            )
            val event = makeEvent().copy(candidates = listOf(noEpisodeCandidate))
            companionStateManager.onAmbiguousPrompt(event)
            advanceUntilIdle()

            vm.selectCandidate(event, noEpisodeCandidate)
            advanceUntilIdle()

            // Prompt stays visible since there's no episode to scrobble
            assertNotNull(vm.uiState.value.pendingAmbiguousPrompt)
        }

        @Test
        fun `selectCandidate with missing traktId does nothing`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            val noIdCandidate = AmbiguousCandidate(
                show = TraktShow("Breaking Bad", 2008, TraktIds(trakt = null)),
                episode = TraktEpisode(season = 1, number = 1),
                score = 0.55f,
                sourceLabel = "library",
            )
            val event = makeEvent().copy(candidates = listOf(noIdCandidate))
            companionStateManager.onAmbiguousPrompt(event)
            advanceUntilIdle()

            vm.selectCandidate(event, noIdCandidate)
            advanceUntilIdle()

            // Prompt stays visible since there's no Trakt ID to resolve against
            assertNotNull(vm.uiState.value.pendingAmbiguousPrompt)
        }

        @Test
        fun `selectCandidate clears prompt even when Trakt call fails`() = runTest {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token-abc"
            coEvery { traktApiService.scrobbleStart(any(), any()) } throws RuntimeException("network error")

            val vm = createViewModel()
            advanceUntilIdle()

            val event = makeEvent()
            companionStateManager.onAmbiguousPrompt(event)
            advanceUntilIdle()

            vm.selectCandidate(event, event.candidates.first())
            advanceUntilIdle()

            // resolvePrompt is in finally block, so prompt clears even on error
            assertNull(vm.uiState.value.pendingAmbiguousPrompt)
        }

        @Test
        fun `selectCandidate clears prompt even when no valid access token`() = runTest {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns null

            val vm = createViewModel()
            advanceUntilIdle()

            val event = makeEvent()
            companionStateManager.onAmbiguousPrompt(event)
            advanceUntilIdle()

            vm.selectCandidate(event, event.candidates.first())
            advanceUntilIdle()

            // return@launch inside try-finally still triggers finally, so resolvePrompt clears the prompt
            assertNull(vm.uiState.value.pendingAmbiguousPrompt)
        }
    }
}
