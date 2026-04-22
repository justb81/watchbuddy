package com.justb81.watchbuddy.tv.ui.diagnostics

import android.app.Application
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TvDiagnosticsViewModel")
class TvDiagnosticsViewModelTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private val application: Application = mockk(relaxed = true)
    private val phoneDiscovery: PhoneDiscoveryManager = mockk(relaxed = true)
    private val scrobbler: MediaSessionScrobbler = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        DiagnosticLog.clear()
        every { phoneDiscovery.discoveryActive } returns MutableStateFlow(false)
        every { phoneDiscovery.bleScanState } returns
            MutableStateFlow(PhoneDiscoveryManager.BleScanState.IDLE)
        every { phoneDiscovery.bleScanErrorCode } returns MutableStateFlow<Int?>(null)
        every { phoneDiscovery.lastHeartbeatTick } returns MutableStateFlow(0L)
        every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(emptyList())
        every { scrobbler.isListening } returns MutableStateFlow(false)
        every { scrobbler.lastCandidate } returns MutableStateFlow(null)
    }

    @AfterEach
    fun tearDown() {
        DiagnosticLog.clear()
    }

    @Test
    fun `recentEvents is empty on fresh VM`() = runTest {
        val vm = TvDiagnosticsViewModel(application, phoneDiscovery, scrobbler)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.recentEvents.isEmpty())
    }

    @Test
    fun `single event is projected with correct level and newest-first order`() = runTest {
        val vm = TvDiagnosticsViewModel(application, phoneDiscovery, scrobbler)
        advanceUntilIdle()

        DiagnosticLog.event("TAG", "hello")
        advanceUntilIdle()

        val events = vm.uiState.value.recentEvents
        assertEquals(1, events.size)
        assertEquals("TAG", events[0].tag)
        assertEquals("hello", events[0].message)
        assertEquals(DiagnosticLog.Level.INFO, events[0].level)
    }

    @Test
    fun `recentEvents is truncated to 100 entries newest first`() = runTest {
        val vm = TvDiagnosticsViewModel(application, phoneDiscovery, scrobbler)
        advanceUntilIdle()

        repeat(150) { i -> DiagnosticLog.event("TAG", "msg $i") }
        advanceUntilIdle()

        val events = vm.uiState.value.recentEvents
        assertEquals(100, events.size)
        assertEquals("msg 149", events[0].message)
        assertEquals("msg 50", events[99].message)
    }
}
