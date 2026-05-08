package com.justb81.watchbuddy.tv.ui.diagnostics

import android.app.Application
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler
import com.justb81.watchbuddy.core.scrobbler.NoOpPlaybackIntentProvider
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.data.CatalogSource
import com.justb81.watchbuddy.tv.data.CatalogStatus
import com.justb81.watchbuddy.tv.data.JustWatchDeepLinkRepository
import com.justb81.watchbuddy.tv.data.JustWatchOutcomeEvent
import com.justb81.watchbuddy.tv.data.TvProviderCatalogRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.scrobbler.NotificationMetadataSource
import com.justb81.watchbuddy.tv.scrobbler.WatchNextMetadataSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    private val justWatchRepo: JustWatchDeepLinkRepository = mockk(relaxed = true)
    private val watchNextSource: WatchNextMetadataSource = mockk(relaxed = true)
    private val notificationSource: NotificationMetadataSource = NotificationMetadataSource()
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
        every { scrobbler.isListening } returns MutableStateFlow(false)
        every { scrobbler.lastCandidate } returns MutableStateFlow(null)
        every { scrobbler.lastObservedSession } returns MutableStateFlow(null)
        coEvery { justWatchRepo.count() } returns 0
        coEvery { justWatchRepo.negativeCount() } returns 0
        coEvery { justWatchRepo.lastFetchedAt() } returns null
        coEvery { justWatchRepo.clearAll() } returns Unit
        every { justWatchRepo.lastFetchError() } returns null
        every { justWatchRepo.searchMissCount() } returns 0
        every { justWatchRepo.outcomeEvents } returns MutableStateFlow(emptyList<JustWatchOutcomeEvent>())
        every { watchNextSource.countPublishingApps() } returns WatchNextMetadataSource.CountResult.Success(0)
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
            application, phoneDiscovery, scrobbler, justWatchRepo,
            watchNextSource, notificationSource, NoOpPlaybackIntentProvider(), catalogRepository,
        )
        advanceUntilIdle()

        assertTrue(vm.uiState.value.recentEvents.isEmpty())
    }

    @Test
    fun `single event is projected with correct level and newest-first order`() = runTest {
        val vm = TvDiagnosticsViewModel(
            application, phoneDiscovery, scrobbler, justWatchRepo,
            watchNextSource, notificationSource, NoOpPlaybackIntentProvider(), catalogRepository,
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
            application, phoneDiscovery, scrobbler, justWatchRepo,
            watchNextSource, notificationSource, NoOpPlaybackIntentProvider(), catalogRepository,
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
    fun `scrobblerListening reflects true when scrobbler isListening emits true`() = runTest {
        val isListeningFlow = MutableStateFlow(false)
        every { scrobbler.isListening } returns isListeningFlow
        val vm = TvDiagnosticsViewModel(
            application, phoneDiscovery, scrobbler, justWatchRepo,
            watchNextSource, notificationSource, NoOpPlaybackIntentProvider(), catalogRepository,
        )
        advanceUntilIdle()

        isListeningFlow.value = true
        advanceUntilIdle()

        assertTrue(vm.uiState.value.scrobblerListening)
    }

    @Test
    fun `scrobblerListening reflects false when scrobbler isListening emits false`() = runTest {
        val isListeningFlow = MutableStateFlow(true)
        every { scrobbler.isListening } returns isListeningFlow
        val vm = TvDiagnosticsViewModel(
            application, phoneDiscovery, scrobbler, justWatchRepo,
            watchNextSource, notificationSource, NoOpPlaybackIntentProvider(), catalogRepository,
        )
        advanceUntilIdle()

        isListeningFlow.value = false
        advanceUntilIdle()

        assertFalse(vm.uiState.value.scrobblerListening)
    }

    @Test
    fun `scrobblerListening starts as false when scrobbler is not listening`() = runTest {
        every { scrobbler.isListening } returns MutableStateFlow(false)
        val vm = TvDiagnosticsViewModel(
            application, phoneDiscovery, scrobbler, justWatchRepo,
            watchNextSource, notificationSource, NoOpPlaybackIntentProvider(), catalogRepository,
        )
        advanceUntilIdle()

        assertFalse(vm.uiState.value.scrobblerListening)
    }

    @Test
    fun `notificationTrackedCount reflects snippets in NotificationMetadataSource`() = runTest {
        val vm = TvDiagnosticsViewModel(
            application, phoneDiscovery, scrobbler, justWatchRepo,
            watchNextSource, notificationSource, NoOpPlaybackIntentProvider(), catalogRepository,
        )
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.notificationTrackedCount)

        notificationSource.onPosted(
            com.justb81.watchbuddy.tv.scrobbler.NotificationSnippet(
                packageName = "com.netflix.ninja",
                title = "Stranger Things",
                text = null,
                subText = null,
                bigText = null,
                infoText = null,
                postedAtMs = System.currentTimeMillis(),
            ),
        )
        vm.refreshNotificationStats()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.notificationTrackedCount)
    }

    @Test
    fun `clearJustWatchCache clears repo and resets cache stats to zero`() = runTest {
        coEvery { justWatchRepo.count() } returnsMany listOf(5, 0)
        coEvery { justWatchRepo.negativeCount() } returnsMany listOf(3, 0)
        val vm = TvDiagnosticsViewModel(
            application, phoneDiscovery, scrobbler, justWatchRepo,
            watchNextSource, notificationSource, NoOpPlaybackIntentProvider(), catalogRepository,
        )
        advanceUntilIdle()

        vm.clearJustWatchCache()
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.cachedDeepLinkCount)
        assertEquals(0, vm.uiState.value.negativeDeepLinkCount)
    }
}
