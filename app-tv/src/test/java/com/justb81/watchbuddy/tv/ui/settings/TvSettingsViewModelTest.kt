package com.justb81.watchbuddy.tv.ui.settings

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TvSettingsViewModel")
class TvSettingsViewModelTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private val repository: StreamingPreferencesRepository = mockk()
    private lateinit var viewModel: TvSettingsViewModel

    @BeforeEach
    fun setUp() {
        DiagnosticLog.clear()
        every { repository.isPhoneDiscoveryEnabled } returns flowOf(true)
        every { repository.isAutostartEnabled } returns flowOf(false)
        coEvery { repository.setPhoneDiscoveryEnabled(any()) } just runs
        coEvery { repository.setAutostartEnabled(any()) } just runs
        viewModel = TvSettingsViewModel(repository)
    }

    @AfterEach
    fun tearDown() {
        DiagnosticLog.clear()
    }

    @Test
    fun `initial uiState reflects repository defaults`() = runTest {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state.isPhoneDiscoveryEnabled)
        assertFalse(state.isAutostartEnabled)
    }

    @Test
    fun `initial uiState reflects repository values when discovery is disabled`() = runTest {
        every { repository.isPhoneDiscoveryEnabled } returns flowOf(false)
        viewModel = TvSettingsViewModel(repository)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isPhoneDiscoveryEnabled)
    }

    @Test
    fun `initial uiState reflects repository values when autostart is enabled`() = runTest {
        every { repository.isAutostartEnabled } returns flowOf(true)
        viewModel = TvSettingsViewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isAutostartEnabled)
    }

    @Nested
    @DisplayName("setPhoneDiscoveryEnabled")
    inner class SetPhoneDiscoveryEnabledTest {

        @Test
        fun `updates uiState optimistically`() {
            viewModel.setPhoneDiscoveryEnabled(false)
            assertFalse(viewModel.uiState.value.isPhoneDiscoveryEnabled)
        }

        @Test
        fun `persists to repository`() = runTest {
            viewModel.setPhoneDiscoveryEnabled(false)
            advanceUntilIdle()
            coVerify { repository.setPhoneDiscoveryEnabled(false) }
        }

        @Test
        fun `persists enabled=true to repository`() = runTest {
            viewModel.setPhoneDiscoveryEnabled(true)
            advanceUntilIdle()
            coVerify { repository.setPhoneDiscoveryEnabled(true) }
        }
    }

    @Nested
    @DisplayName("setAutostartEnabled")
    inner class SetAutostartEnabledTest {

        @Test
        fun `updates uiState optimistically`() {
            viewModel.setAutostartEnabled(true)
            assertTrue(viewModel.uiState.value.isAutostartEnabled)
        }

        @Test
        fun `persists to repository`() = runTest {
            viewModel.setAutostartEnabled(true)
            advanceUntilIdle()
            coVerify { repository.setAutostartEnabled(true) }
        }
    }

    @Nested
    @DisplayName("ErrorLogging")
    inner class ErrorLoggingTest {

        @Test
        fun `flow observation failure logs via DiagnosticLog`() = runTest {
            val error = RuntimeException("DataStore read failed")
            every { repository.isPhoneDiscoveryEnabled } returns flow { throw error }

            viewModel = TvSettingsViewModel(repository)
            advanceUntilIdle()

            val errors = DiagnosticLog.snapshot().filter { it.level == DiagnosticLog.Level.ERROR }
            assertTrue(errors.isNotEmpty(), "Expected an error entry in DiagnosticLog")
            assertTrue(
                errors.any { it.message.contains("settings prefs observation failed") },
                "Expected 'settings prefs observation failed' in log"
            )
        }

        @Test
        fun `write failure logs via DiagnosticLog`() = runTest {
            coEvery { repository.setPhoneDiscoveryEnabled(any()) } throws RuntimeException("write failed")

            viewModel.setPhoneDiscoveryEnabled(false)
            advanceUntilIdle()

            val errors = DiagnosticLog.snapshot().filter { it.level == DiagnosticLog.Level.ERROR }
            assertTrue(errors.isNotEmpty(), "Expected an error entry in DiagnosticLog")
            assertTrue(
                errors.any { it.message.contains("settings prefs write failed") },
                "Expected 'settings prefs write failed' in log"
            )
        }
    }
}
