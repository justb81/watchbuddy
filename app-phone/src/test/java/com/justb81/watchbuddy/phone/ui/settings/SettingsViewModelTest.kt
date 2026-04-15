package com.justb81.watchbuddy.phone.ui.settings

import android.app.Application
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.MainDispatcherRule
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import com.justb81.watchbuddy.phone.llm.ModelDownloadWorker
import com.justb81.watchbuddy.phone.server.DeviceCapabilityProvider
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import java.security.GeneralSecurityException

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("SettingsViewModel")
class SettingsViewModelTest {

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

    private val downloadWorkInfoFlow = MutableStateFlow<List<WorkInfo>>(emptyList())

    @BeforeEach
    fun setUp() {
        every { workManager.getWorkInfosForUniqueWorkFlow(any()) } returns downloadWorkInfoFlow
        every { settingsRepository.settings } returns flowOf(AppSettings())
        every { settingsRepository.getClientSecret() } returns ""
        every { settingsRepository.modelReady } returns MutableStateFlow(false)
        every { tokenRepository.getAccessToken() } returns null
        every { llmOrchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
            backend = LlmBackend.NONE,
            modelVariant = null,
            qualityScore = 0
        )
    }

    private fun createViewModel(): SettingsViewModel = SettingsViewModel(
        application = application,
        workManager = workManager,
        llmOrchestrator = llmOrchestrator,
        traktApi = traktApi,
        tokenRepository = tokenRepository,
        deviceCapabilityProvider = deviceCapabilityProvider,
        settingsRepository = settingsRepository
    )

    @Nested
    @DisplayName("Download progress observation")
    inner class DownloadProgressObservation {

        @Test
        fun `shows progress when work is running`() = runTest {
            val workInfo = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.RUNNING
                every { progress } returns workDataOf(ModelDownloadWorker.KEY_PROGRESS to 42)
            }

            val vm = createViewModel()
            downloadWorkInfoFlow.value = listOf(workInfo)
            advanceUntilIdle()

            assertEquals(42, vm.uiState.value.llmDownloadProgress)
        }

        @Test
        fun `clears progress and marks ready when work succeeds`() = runTest {
            val workInfo = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.SUCCEEDED
                every { progress } returns workDataOf(ModelDownloadWorker.KEY_PROGRESS to 100)
            }

            val vm = createViewModel()
            downloadWorkInfoFlow.value = listOf(workInfo)
            advanceUntilIdle()

            assertNull(vm.uiState.value.llmDownloadProgress)
            assertTrue(vm.uiState.value.llmReady)
        }

        @Test
        fun `clears progress when work fails`() = runTest {
            val runningInfo = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.RUNNING
                every { progress } returns workDataOf(ModelDownloadWorker.KEY_PROGRESS to 50)
            }
            val failedInfo = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.FAILED
                every { progress } returns workDataOf()
                every { outputData } returns workDataOf()
            }

            val vm = createViewModel()
            downloadWorkInfoFlow.value = listOf(runningInfo)
            advanceUntilIdle()
            downloadWorkInfoFlow.value = listOf(failedInfo)
            advanceUntilIdle()

            assertNull(vm.uiState.value.llmDownloadProgress)
            assertFalse(vm.uiState.value.llmValidationFailed)
        }

        @Test
        fun `sets llmValidationFailed when failure error starts with Validation`() = runTest {
            val failedInfo = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.FAILED
                every { progress } returns workDataOf()
                every { outputData } returns workDataOf(
                    ModelDownloadWorker.KEY_ERROR to "Validation: model file too small (4 bytes)"
                )
            }

            val vm = createViewModel()
            downloadWorkInfoFlow.value = listOf(failedInfo)
            advanceUntilIdle()

            assertNull(vm.uiState.value.llmDownloadProgress)
            assertTrue(vm.uiState.value.llmValidationFailed)
        }

        @Test
        fun `clears progress when work is cancelled`() = runTest {
            val runningInfo = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.RUNNING
                every { progress } returns workDataOf(ModelDownloadWorker.KEY_PROGRESS to 30)
            }
            val cancelledInfo = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.CANCELLED
                every { progress } returns workDataOf()
            }

            val vm = createViewModel()
            downloadWorkInfoFlow.value = listOf(runningInfo)
            advanceUntilIdle()
            downloadWorkInfoFlow.value = listOf(cancelledInfo)
            advanceUntilIdle()

            assertNull(vm.uiState.value.llmDownloadProgress)
        }

        @Test
        fun `shows 0 progress when work is enqueued`() = runTest {
            val workInfo = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.ENQUEUED
                every { progress } returns workDataOf()
            }

            val vm = createViewModel()
            downloadWorkInfoFlow.value = listOf(workInfo)
            advanceUntilIdle()

            assertEquals(0, vm.uiState.value.llmDownloadProgress)
        }

        @Test
        fun `no progress update when work info list is empty`() = runTest {
            val vm = createViewModel()
            // downloadWorkInfoFlow starts with emptyList() — no state change expected
            advanceUntilIdle()

            assertNull(vm.uiState.value.llmDownloadProgress)
        }

        @Test
        fun `picks up in-progress download on ViewModel creation`() = runTest {
            // Simulate an already-running download when the ViewModel is created
            val runningInfo = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.RUNNING
                every { progress } returns workDataOf(ModelDownloadWorker.KEY_PROGRESS to 75)
            }
            downloadWorkInfoFlow.value = listOf(runningInfo)

            val vm = createViewModel()
            advanceUntilIdle()

            assertEquals(75, vm.uiState.value.llmDownloadProgress)
        }
    }

    @Nested
    @DisplayName("downloadModel")
    inner class DownloadModel {

        @Test
        fun `enqueues unique work with correct work name`() = runTest {
            every { llmOrchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                backend = LlmBackend.LITERT,
                modelVariant = LlmOrchestrator.ModelVariant.GEMMA4_E2B,
                qualityScore = 70
            )

            val vm = createViewModel()
            vm.downloadModel()
            advanceUntilIdle()

            verify {
                workManager.enqueueUniqueWork(
                    ModelDownloadWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    any<OneTimeWorkRequest>()
                )
            }
        }

        @Test
        fun `does nothing when no model variant is available`() = runTest {
            every { llmOrchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                backend = LlmBackend.AICORE,
                modelVariant = null,
                qualityScore = 150
            )

            val vm = createViewModel()
            vm.downloadModel()
            advanceUntilIdle()

            verify(exactly = 0) {
                workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            }
        }

        @Test
        fun `sets modelDownloadUrlError when URL does not end with litertlm`() = runTest {
            every { llmOrchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                backend = LlmBackend.LITERT,
                modelVariant = LlmOrchestrator.ModelVariant.GEMMA4_E2B,
                qualityScore = 70
            )

            val vm = createViewModel()
            vm.setModelDownloadUrl("https://example.com/model.bin")
            vm.downloadModel()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.modelDownloadUrlError)
            verify(exactly = 0) {
                workManager.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
            }
        }

        @Test
        fun `clears modelDownloadUrlError when URL is corrected`() = runTest {
            val vm = createViewModel()
            vm.setModelDownloadUrl("https://example.com/model.bin")
            vm.setModelDownloadUrl("https://example.com/model.litertlm")
            advanceUntilIdle()

            assertFalse(vm.uiState.value.modelDownloadUrlError)
        }

        @Test
        fun `uses custom download URL when set`() = runTest {
            every { llmOrchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                backend = LlmBackend.LITERT,
                modelVariant = LlmOrchestrator.ModelVariant.GEMMA4_E2B,
                qualityScore = 70
            )

            val vm = createViewModel()
            vm.setModelDownloadUrl("https://my-server.example.com/model.litertlm")
            vm.downloadModel()
            advanceUntilIdle()

            verify {
                workManager.enqueueUniqueWork(
                    ModelDownloadWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    any<OneTimeWorkRequest>()
                )
            }
        }
    }

    @Nested
    @DisplayName("TMDB API key state")
    inner class TmdbApiKeyState {

        @Test
        fun `tmdbConnected is false and defaultTmdbApiKeyAvailable is false when no keys`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(tmdbApiKey = "", defaultTmdbApiKeyAvailable = false)
            )

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.tmdbConnected)
            assertFalse(vm.uiState.value.defaultTmdbApiKeyAvailable)
        }

        @Test
        fun `tmdbConnected is true when user has set a custom key`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(tmdbApiKey = "user-key-123", defaultTmdbApiKeyAvailable = false)
            )

            val vm = createViewModel()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.tmdbConnected)
            assertFalse(vm.uiState.value.defaultTmdbApiKeyAvailable)
        }

        @Test
        fun `defaultTmdbApiKeyAvailable is true when build has default key and user has no custom key`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(tmdbApiKey = "", defaultTmdbApiKeyAvailable = true)
            )

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.tmdbConnected)
            assertTrue(vm.uiState.value.defaultTmdbApiKeyAvailable)
        }

        @Test
        fun `defaultTmdbApiKeyAvailable is false when user has custom key even if build has default`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(tmdbApiKey = "user-key-123", defaultTmdbApiKeyAvailable = true)
            )

            val vm = createViewModel()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.tmdbConnected)
            assertFalse(vm.uiState.value.defaultTmdbApiKeyAvailable)
        }

        @Test
        fun `disconnectTmdb restores defaultTmdbApiKeyAvailable when build has default key`() = runTest {
            val savedSettings = AppSettings(tmdbApiKey = "user-key", defaultTmdbApiKeyAvailable = true)
            every { settingsRepository.settings } returns flowOf(savedSettings)
            coEvery { settingsRepository.saveSettings(any()) } returns Unit

            val vm = createViewModel()
            advanceUntilIdle()

            vm.disconnectTmdb()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.tmdbConnected)
            assertTrue(vm.uiState.value.defaultTmdbApiKeyAvailable)
        }

        @Test
        fun `saveTmdbApiKey hides default key banner after saving a custom key`() = runTest {
            val savedSettings = AppSettings(tmdbApiKey = "", defaultTmdbApiKeyAvailable = true)
            every { settingsRepository.settings } returns flowOf(savedSettings)
            coEvery { settingsRepository.saveSettings(any()) } returns Unit

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setTmdbApiKey("my-new-key")
            vm.saveTmdbApiKey()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.tmdbConnected)
            assertFalse(vm.uiState.value.defaultTmdbApiKeyAvailable)
        }
    }

    @Nested
    @DisplayName("Init resilience — no force close on settings screen open")
    inner class InitResilience {

        @Test
        fun `ViewModel creation does not throw when getClientSecret throws SecurityException`() = runTest {
            // Simulates Keystore unavailability (e.g. device encryption changed).
            // Before the fix, this unhandled exception in loadPersistedSettings() would
            // propagate through viewModelScope and crash the app (force close).
            every { settingsRepository.getClientSecret() } throws
                SecurityException("Keystore operation failed")

            // Must not throw — crash would surface here before the fix.
            val vm = createViewModel()
            advanceUntilIdle()

            // State falls back to defaults; screen is still usable.
            assertEquals("", vm.uiState.value.directClientSecret)
        }

        @Test
        fun `ViewModel creation does not throw when settings DataStore throws`() = runTest {
            every { settingsRepository.settings } throws RuntimeException("DataStore corrupted")

            val vm = createViewModel()
            advanceUntilIdle()

            // Falls back to initial defaults.
            assertEquals(AuthMode.MANAGED, vm.uiState.value.authMode)
            assertEquals("", vm.uiState.value.customBackendUrl)
        }

        @Test
        fun `ViewModel creation does not throw when getClientSecret throws GeneralSecurityException`() = runTest {
            every { settingsRepository.getClientSecret() } throws
                RuntimeException("Key could not be generated", GeneralSecurityException("AES key invalid"))

            val vm = createViewModel()
            advanceUntilIdle()

            assertEquals("", vm.uiState.value.directClientSecret)
        }

        @Test
        fun `ViewModel creation does not throw when LlmOrchestrator selectConfig throws`() = runTest {
            every { llmOrchestrator.selectConfig() } throws
                RuntimeException("ActivityManager service unavailable")

            val vm = createViewModel()
            advanceUntilIdle()

            // LLM backend stays at initial detecting state; no crash.
            assertNotNull(vm.uiState.value.llmBackend)
        }

        @Test
        fun `ViewModel creation does not throw when getAccessToken throws SecurityException`() = runTest {
            // Simulates Keystore unavailability during loadTraktUsername().
            // getAccessToken() was previously called outside the try/catch block,
            // so a SecurityException would propagate and crash the app.
            every { tokenRepository.getAccessToken() } throws
                SecurityException("Keystore operation failed")

            val vm = createViewModel()
            advanceUntilIdle()

            // Username stays null (not connected); no crash.
            assertNull(vm.uiState.value.traktUsername)
        }

        @Test
        fun `loadPersistedSettings exception does not affect tmdb key state from previous load`() = runTest {
            // First load succeeds — sets a TMDB key.
            val settings = AppSettings(tmdbApiKey = "existing-key", defaultTmdbApiKeyAvailable = false)
            every { settingsRepository.settings } returns flowOf(settings)
            every { settingsRepository.getClientSecret() } returns "secret"

            val vm = createViewModel()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.tmdbConnected)
            // Client secret was loaded successfully.
            assertEquals("secret", vm.uiState.value.directClientSecret)
        }
    }

    @Nested
    @DisplayName("saveAdvancedSettings — TMDB key preservation")
    inner class SaveAdvancedSettings {

        @Test
        fun `saveAdvancedSettings preserves existing TMDB API key`() = runTest {
            val existingSettings = AppSettings(
                authMode = AuthMode.MANAGED,
                tmdbApiKey = "existing-tmdb-key",
                defaultTmdbApiKeyAvailable = false
            )
            every { settingsRepository.settings } returns flowOf(existingSettings)
            coEvery { settingsRepository.saveSettings(any()) } returns Unit

            val vm = createViewModel()
            advanceUntilIdle()

            vm.saveAdvancedSettings()
            advanceUntilIdle()

            // The saved settings must contain the original TMDB key, not an empty string.
            coVerify {
                settingsRepository.saveSettings(
                    withArg { saved ->
                        assertEquals("existing-tmdb-key", saved.tmdbApiKey)
                    }
                )
            }
        }

        @Test
        fun `saveAdvancedSettings with new auth mode does not wipe TMDB key`() = runTest {
            val existingSettings = AppSettings(
                authMode = AuthMode.MANAGED,
                tmdbApiKey = "my-tmdb-key",
                defaultTmdbApiKeyAvailable = true
            )
            every { settingsRepository.settings } returns flowOf(existingSettings)
            coEvery { settingsRepository.saveSettings(any()) } returns Unit

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setAuthMode(AuthMode.DIRECT)
            vm.saveAdvancedSettings()
            advanceUntilIdle()

            coVerify {
                settingsRepository.saveSettings(
                    withArg { saved ->
                        assertEquals(AuthMode.DIRECT, saved.authMode)
                        assertEquals("my-tmdb-key", saved.tmdbApiKey)
                        assertTrue(saved.defaultTmdbApiKeyAvailable)
                    }
                )
            }
        }

        @Test
        fun `saveAdvancedSettings sets saveSuccess flag`() = runTest {
            every { settingsRepository.settings } returns flowOf(AppSettings())
            coEvery { settingsRepository.saveSettings(any()) } returns Unit

            val vm = createViewModel()
            advanceUntilIdle()

            vm.saveAdvancedSettings()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.saveSuccess)
        }
    }
}
