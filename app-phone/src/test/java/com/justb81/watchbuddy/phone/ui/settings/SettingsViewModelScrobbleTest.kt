package com.justb81.watchbuddy.phone.ui.settings

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.MainDispatcherRule
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import com.justb81.watchbuddy.phone.server.DeviceCapabilityProvider
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
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
@DisplayName("SettingsViewModel — Auto-Scrobble")
class SettingsViewModelScrobbleTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private val application: Application = mockk(relaxed = true)
    private val llmOrchestrator: LlmOrchestrator = mockk(relaxed = true)
    private val traktApi: TraktApiService = mockk(relaxed = true)
    private val tokenRepository: TokenRepository = mockk(relaxed = true)
    private val deviceCapabilityProvider: DeviceCapabilityProvider = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { workManager.getWorkInfosForUniqueWorkFlow(any()) } returns MutableStateFlow(emptyList())
        every { settingsRepository.settings } returns flowOf(AppSettings())
        every { settingsRepository.getClientSecret() } returns ""
        every { settingsRepository.modelReady } returns MutableStateFlow(false)
        every { settingsRepository.hasDefaultTmdbApiKey() } returns false
        every { tokenRepository.getAccessToken() } returns null
        every { llmOrchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
            backend = LlmBackend.NONE, modelVariant = null, qualityScore = 0
        )
        every { context.packageName } returns "com.justb81.watchbuddy"
    }

    private fun createViewModel(): SettingsViewModel = SettingsViewModel(
        application = application,
        workManager = workManager,
        llmOrchestrator = llmOrchestrator,
        traktApi = traktApi,
        tokenRepository = tokenRepository,
        deviceCapabilityProvider = deviceCapabilityProvider,
        settingsRepository = settingsRepository,
        managedBackendAvailable = true
    )

    // ── toggleAutoScrobble() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("toggleAutoScrobble()")
    inner class ToggleAutoScrobbleTest {

        @Test
        fun `initial autoScrobbleEnabled state is false`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.autoScrobbleEnabled)
        }

        @Test
        fun `toggle flips autoScrobbleEnabled from false to true`() = runTest {
            coEvery { settingsRepository.setAutoScrobbleEnabled(any()) } just runs
            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleAutoScrobble(context)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.autoScrobbleEnabled)
        }

        @Test
        fun `toggle flips autoScrobbleEnabled from true to false`() = runTest {
            coEvery { settingsRepository.setAutoScrobbleEnabled(any()) } just runs
            every { settingsRepository.settings } returns flowOf(AppSettings(autoScrobbleEnabled = true))
            val vm = createViewModel()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.autoScrobbleEnabled)

            vm.toggleAutoScrobble(context)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.autoScrobbleEnabled)
        }

        @Test
        fun `persists the new enabled state to SettingsRepository`() = runTest {
            coEvery { settingsRepository.setAutoScrobbleEnabled(any()) } just runs
            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleAutoScrobble(context)
            advanceUntilIdle()

            coVerify { settingsRepository.setAutoScrobbleEnabled(true) }
        }

        @Test
        fun `reverts optimistic toggle when persistence throws`() = runTest {
            coEvery { settingsRepository.setAutoScrobbleEnabled(any()) } throws RuntimeException("IO error")
            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleAutoScrobble(context)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.autoScrobbleEnabled)
        }

        @Test
        fun `loads persisted autoScrobbleEnabled=true on ViewModel init`() = runTest {
            every { settingsRepository.settings } returns flowOf(AppSettings(autoScrobbleEnabled = true))
            val vm = createViewModel()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.autoScrobbleEnabled)
        }
    }
}
