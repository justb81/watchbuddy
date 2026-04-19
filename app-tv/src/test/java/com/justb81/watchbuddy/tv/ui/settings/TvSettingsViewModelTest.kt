package com.justb81.watchbuddy.tv.ui.settings

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
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

    @BeforeEach
    fun setUp() {
        DiagnosticLog.clear()
        every { repository.isPhoneDiscoveryEnabled } returns flowOf(true)
        every { repository.isAutostartEnabled } returns flowOf(false)
        coEvery { repository.setPhoneDiscoveryEnabled(any()) } just runs
        coEvery { repository.setAutostartEnabled(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        DiagnosticLog.clear()
    }

    @Test
    fun `projects preferences into uiState`() = runTest {
        every { repository.isPhoneDiscoveryEnabled } returns flowOf(false)
        every { repository.isAutostartEnabled } returns flowOf(true)

        val vm = TvSettingsViewModel(repository)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isPhoneDiscoveryEnabled)
        assertTrue(vm.uiState.value.isAutostartEnabled)
    }

    @Test
    fun `setPhoneDiscoveryEnabled writes through and optimistically updates state`() = runTest {
        val vm = TvSettingsViewModel(repository)
        advanceUntilIdle()

        vm.setPhoneDiscoveryEnabled(false)
        advanceUntilIdle()

        coVerify { repository.setPhoneDiscoveryEnabled(false) }
        assertFalse(vm.uiState.value.isPhoneDiscoveryEnabled)
    }

    @Test
    fun `setAutostartEnabled writes through and optimistically updates state`() = runTest {
        val vm = TvSettingsViewModel(repository)
        advanceUntilIdle()

        vm.setAutostartEnabled(true)
        advanceUntilIdle()

        coVerify { repository.setAutostartEnabled(true) }
        assertTrue(vm.uiState.value.isAutostartEnabled)
    }

    @Test
    fun `reflects live updates from the repository`() = runTest {
        val discoveryFlow = MutableStateFlow(true)
        every { repository.isPhoneDiscoveryEnabled } returns discoveryFlow

        val vm = TvSettingsViewModel(repository)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isPhoneDiscoveryEnabled)

        discoveryFlow.value = false
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isPhoneDiscoveryEnabled)
    }

    @Test
    fun `observation failure is logged via DiagnosticLog`() = runTest {
        every { repository.isPhoneDiscoveryEnabled } returns flow { throw RuntimeException("boom") }

        TvSettingsViewModel(repository)
        advanceUntilIdle()

        val errors = DiagnosticLog.snapshot().filter { it.level == DiagnosticLog.Level.ERROR }
        assertTrue(errors.any { it.message.contains("tv settings observation failed") })
    }

    @Test
    fun `write failure is logged via DiagnosticLog`() = runTest {
        coEvery { repository.setPhoneDiscoveryEnabled(any()) } throws RuntimeException("write boom")

        val vm = TvSettingsViewModel(repository)
        advanceUntilIdle()
        vm.setPhoneDiscoveryEnabled(false)
        advanceUntilIdle()

        val errors = DiagnosticLog.snapshot().filter { it.level == DiagnosticLog.Level.ERROR }
        assertTrue(errors.any { it.message.contains("tv settings write failed") })
    }
}
