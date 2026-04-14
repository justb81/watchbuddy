package com.justb81.watchbuddy.phone.ui.settings

import android.app.Application
import androidx.work.ExistingWorkPolicy
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
            }

            val vm = createViewModel()
            downloadWorkInfoFlow.value = listOf(runningInfo)
            advanceUntilIdle()
            downloadWorkInfoFlow.value = listOf(failedInfo)
            advanceUntilIdle()

            assertNull(vm.uiState.value.llmDownloadProgress)
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
                    any()
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
                workManager.enqueueUniqueWork(any(), any(), any())
            }
        }

        @Test
        fun `uses custom base URL when set`() = runTest {
            every { llmOrchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
                backend = LlmBackend.LITERT,
                modelVariant = LlmOrchestrator.ModelVariant.GEMMA4_E2B,
                qualityScore = 70
            )

            val vm = createViewModel()
            vm.setModelBaseUrl("https://my-server.example.com")
            vm.downloadModel()
            advanceUntilIdle()

            verify {
                workManager.enqueueUniqueWork(
                    ModelDownloadWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    any()
                )
            }
        }
    }
}
