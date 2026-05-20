package com.justb81.watchbuddy.tv.ui.diagnostics

import android.app.Application
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.data.CatalogSource
import com.justb81.watchbuddy.tv.data.CatalogStatus
import com.justb81.watchbuddy.tv.data.JustWatchDeepLinkRepository
import com.justb81.watchbuddy.tv.data.JustWatchOutcomeEvent
import com.justb81.watchbuddy.tv.data.TvProviderCatalogRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
    private val justWatchRepo: JustWatchDeepLinkRepository = mockk(relaxed = true)
    private val catalogRepository: TvProviderCatalogRepository = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        DiagnosticLog.clear()
        every { phoneDiscovery.discoveryActive } returns MutableStateFlow(false)
        every { phoneDiscovery.bleScanState } returns
            MutableStateFlow(PhoneDiscoveryManager.BleScanState.IDLE)
        every { phoneDiscovery.bleScanErrorCode } returns MutableStateFlow<Int?>(null)
        every { phoneDiscovery.lastHeartbeatTick } returns MutableStateFlow(0L)
        every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(emptyList())
        coEvery { justWatchRepo.count() } returns 0
        coEvery { justWatchRepo.negativeCount() } returns 0
        coEvery { justWatchRepo.lastFetchedAt() } returns null
        coEvery { justWatchRepo.clearAll() } returns Unit
        every { justWatchRepo.lastFetchError() } returns null
        every { justWatchRepo.searchMissCount() } returns 0
        every { justWatchRepo.outcomeEvents } returns MutableStateFlow(emptyList<JustWatchOutcomeEvent>())
        every { catalogRepository.status } returns MutableStateFlow(
            CatalogStatus(version = 0, fetchedAtMs = 0L, source = CatalogSource.BUNDLED),
        )
    }

    @AfterEach
    fun tearDown() {
        DiagnosticLog.clear()
    }

    @Test
    fun `recentEvents is empty on fresh VM`() = runTest {
        val vm = TvDiagnosticsViewModel(
            application, phoneDiscovery, justWatchRepo, catalogRepository,
        )
        advanceUntilIdle()

        assertEquals(true, vm.uiState.value.recentEvents.isEmpty())
    }

    @Test
    fun `single event is projected with correct level and newest-first order`() = runTest {
        val vm = TvDiagnosticsViewModel(
            application, phoneDiscovery, justWatchRepo, catalogRepository,
        )
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
        val vm = TvDiagnosticsViewModel(
            application, phoneDiscovery, justWatchRepo, catalogRepository,
        )
        advanceUntilIdle()

        repeat(150) { i -> DiagnosticLog.event("TAG", "msg $i") }
        advanceUntilIdle()

        val events = vm.uiState.value.recentEvents
        assertEquals(100, events.size)
        assertEquals("msg 149", events[0].message)
        assertEquals("msg 50", events[99].message)
    }

    @Test
    fun `clearJustWatchCache clears repo and resets cache stats to zero`() = runTest {
        coEvery { justWatchRepo.count() } returnsMany listOf(5, 0)
        coEvery { justWatchRepo.negativeCount() } returnsMany listOf(3, 0)
        val vm = TvDiagnosticsViewModel(
            application, phoneDiscovery, justWatchRepo, catalogRepository,
        )
        advanceUntilIdle()

        vm.clearJustWatchCache()
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.cachedDeepLinkCount)
        assertEquals(0, vm.uiState.value.negativeDeepLinkCount)
    }
}
