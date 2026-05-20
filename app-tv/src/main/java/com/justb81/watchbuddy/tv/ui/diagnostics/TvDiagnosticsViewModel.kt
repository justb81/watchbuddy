package com.justb81.watchbuddy.tv.ui.diagnostics

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.data.CatalogSource
import com.justb81.watchbuddy.tv.data.JustWatchDeepLinkRepository
import com.justb81.watchbuddy.tv.data.JustWatchOutcomeEvent
import com.justb81.watchbuddy.tv.data.TvProviderCatalogRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvDiagnosticsUiState(
    val discoveryActive: Boolean = false,
    val bleScanState: PhoneDiscoveryManager.BleScanState = PhoneDiscoveryManager.BleScanState.IDLE,
    val bleScanErrorCode: Int? = null,
    val lastHeartbeatMs: Long = 0L,
    val phones: List<PhoneDiscoveryManager.DiscoveredPhone> = emptyList(),
    val recentEvents: List<DiagnosticLog.Entry> = emptyList(),
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val cachedDeepLinkCount: Int = 0,
    val negativeDeepLinkCount: Int = 0,
    val lastDeepLinkFetchMs: Long = 0L,
    val lastJustWatchError: String? = null,
    val justWatchSearchMisses24h: Int = 0,
    /**
     * The last 5 JustWatch resolution outcomes for display in the Streaming Links diagnostics section.
     * Most recent event is last. Empty until the user navigates to a show-detail screen.
     */
    val recentJustWatchOutcomes: List<JustWatchOutcomeEvent> = emptyList(),
    /** Current catalog version number (0 = bundled fallback not yet loaded). */
    val catalogVersion: Int = 0,
    /** Epoch-ms of last successful catalog fetch from a phone; 0 = never fetched. */
    val catalogFetchedAtMs: Long = 0L,
    /** Whether the active catalog was fetched live from a phone or loaded from bundled assets. */
    val catalogSource: CatalogSource = CatalogSource.BUNDLED,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TvDiagnosticsViewModel @Inject constructor(
    private val application: Application,
    phoneDiscovery: PhoneDiscoveryManager,
    private val justWatchRepo: JustWatchDeepLinkRepository,
    private val catalogRepository: TvProviderCatalogRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvDiagnosticsUiState())
    val uiState: StateFlow<TvDiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refreshJustWatchCacheStats()

        val discoveryState = combine(
            phoneDiscovery.discoveryActive,
            phoneDiscovery.bleScanState,
            phoneDiscovery.bleScanErrorCode,
        ) { active, ble, bleErr -> Triple(active, ble, bleErr) }
        val discoveryTail = combine(
            phoneDiscovery.lastHeartbeatTick,
            phoneDiscovery.discoveredPhones,
        ) { tick, phones -> tick to phones }

        viewModelScope.launch {
            combine(discoveryState, discoveryTail) { a, b ->
                TvDiagnosticsUiState(
                    discoveryActive = a.first,
                    bleScanState = a.second,
                    bleScanErrorCode = a.third,
                    lastHeartbeatMs = b.first,
                    phones = b.second,
                    cachedDeepLinkCount = _uiState.value.cachedDeepLinkCount,
                    negativeDeepLinkCount = _uiState.value.negativeDeepLinkCount,
                    lastDeepLinkFetchMs = _uiState.value.lastDeepLinkFetchMs,
                )
            }.collect { snapshot ->
                _uiState.update {
                    snapshot.copy(
                        recentEvents = it.recentEvents,
                        cachedDeepLinkCount = it.cachedDeepLinkCount,
                        negativeDeepLinkCount = it.negativeDeepLinkCount,
                        lastDeepLinkFetchMs = it.lastDeepLinkFetchMs,
                        lastJustWatchError = it.lastJustWatchError,
                        justWatchSearchMisses24h = it.justWatchSearchMisses24h,
                    )
                }
            }
        }

        viewModelScope.launch {
            DiagnosticLog.updates.onStart { emit(Unit) }.collect {
                val latest = DiagnosticLog.snapshot().asReversed().take(MAX_RECENT_EVENTS)
                _uiState.update { it.copy(recentEvents = latest) }
            }
        }

        viewModelScope.launch {
            justWatchRepo.outcomeEvents.collect { events ->
                _uiState.update { it.copy(recentJustWatchOutcomes = events.takeLast(MAX_DISPLAYED_OUTCOMES)) }
            }
        }

        viewModelScope.launch {
            catalogRepository.status.collect { status ->
                _uiState.update {
                    it.copy(
                        catalogVersion = status.version,
                        catalogFetchedAtMs = status.fetchedAtMs,
                        catalogSource = status.source,
                    )
                }
            }
        }
    }

    fun refreshJustWatchCacheStats() {
        viewModelScope.launch {
            val count = justWatchRepo.count()
            val negCount = justWatchRepo.negativeCount()
            val lastFetch = justWatchRepo.lastFetchedAt() ?: 0L
            val lastError = justWatchRepo.lastFetchError()
            val misses24h = justWatchRepo.searchMissCount()
            _uiState.update {
                it.copy(
                    cachedDeepLinkCount = count,
                    negativeDeepLinkCount = negCount,
                    lastDeepLinkFetchMs = lastFetch,
                    lastJustWatchError = lastError,
                    justWatchSearchMisses24h = misses24h,
                )
            }
        }
    }

    fun clearJustWatchCache() {
        viewModelScope.launch {
            justWatchRepo.clearAll()
            refreshJustWatchCacheStats()
        }
    }

    companion object {
        private const val MAX_RECENT_EVENTS = 100
        private const val MAX_DISPLAYED_OUTCOMES = 5
    }
}
