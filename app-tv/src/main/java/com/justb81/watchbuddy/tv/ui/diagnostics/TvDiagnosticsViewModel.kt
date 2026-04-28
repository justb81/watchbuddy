package com.justb81.watchbuddy.tv.ui.diagnostics

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler
import com.justb81.watchbuddy.tv.data.JustWatchDeepLinkRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.scrobbler.WatchNextMetadataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TvDiagnosticsUiState(
    val discoveryActive: Boolean = false,
    val bleScanState: PhoneDiscoveryManager.BleScanState = PhoneDiscoveryManager.BleScanState.IDLE,
    val bleScanErrorCode: Int? = null,
    val lastHeartbeatMs: Long = 0L,
    val phones: List<PhoneDiscoveryManager.DiscoveredPhone> = emptyList(),
    val notificationAccessGranted: Boolean = false,
    val scrobblerListening: Boolean = false,
    val lastCandidate: MediaSessionScrobbler.LastCandidate? = null,
    val lastObservedSession: MediaSessionScrobbler.LastObservedSession? = null,
    val recentEvents: List<DiagnosticLog.Entry> = emptyList(),
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val cachedDeepLinkCount: Int = 0,
    val negativeDeepLinkCount: Int = 0,
    val lastDeepLinkFetchMs: Long = 0L,
    /** null = not yet checked, PermissionDenied or Success from the WatchNext provider query. */
    val watchNextCountResult: WatchNextMetadataSource.CountResult? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TvDiagnosticsViewModel @Inject constructor(
    private val application: Application,
    phoneDiscovery: PhoneDiscoveryManager,
    scrobbler: MediaSessionScrobbler,
    private val justWatchRepo: JustWatchDeepLinkRepository,
    private val watchNextSource: WatchNextMetadataSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvDiagnosticsUiState())
    val uiState: StateFlow<TvDiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refreshNotificationAccess()
        refreshJustWatchCacheStats()
        refreshWatchNextStats()

        val discoveryState = combine(
            phoneDiscovery.discoveryActive,
            phoneDiscovery.bleScanState,
            phoneDiscovery.bleScanErrorCode,
        ) { active, ble, bleErr -> Triple(active, ble, bleErr) }
        val discoveryTail = combine(
            phoneDiscovery.lastHeartbeatTick,
            phoneDiscovery.discoveredPhones,
        ) { tick, phones -> tick to phones }
        val scrobbleState = combine(
            scrobbler.isListening,
            scrobbler.lastCandidate,
            scrobbler.lastObservedSession,
        ) { listening, last, observed -> Triple(listening, last, observed) }

        viewModelScope.launch {
            combine(discoveryState, discoveryTail, scrobbleState) { a, b, s ->
                TvDiagnosticsUiState(
                    discoveryActive = a.first,
                    bleScanState = a.second,
                    bleScanErrorCode = a.third,
                    lastHeartbeatMs = b.first,
                    phones = b.second,
                    notificationAccessGranted = _uiState.value.notificationAccessGranted,
                    scrobblerListening = s.first,
                    lastCandidate = s.second,
                    lastObservedSession = s.third,
                    cachedDeepLinkCount = _uiState.value.cachedDeepLinkCount,
                    negativeDeepLinkCount = _uiState.value.negativeDeepLinkCount,
                    lastDeepLinkFetchMs = _uiState.value.lastDeepLinkFetchMs,
                    watchNextCountResult = _uiState.value.watchNextCountResult,
                )
            }.collect { snapshot ->
                _uiState.update {
                    snapshot.copy(
                        notificationAccessGranted = it.notificationAccessGranted,
                        recentEvents = it.recentEvents,
                        cachedDeepLinkCount = it.cachedDeepLinkCount,
                        negativeDeepLinkCount = it.negativeDeepLinkCount,
                        lastDeepLinkFetchMs = it.lastDeepLinkFetchMs,
                        watchNextCountResult = it.watchNextCountResult,
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
    }

    /**
     * Re-reads the system notification-access allowlist; call on ON_RESUME.
     * Wrapped in runCatching so unit tests running against a stubbed Android
     * JAR don't throw on the static [android.provider.Settings.Secure] lookup.
     */
    fun refreshNotificationAccess() {
        val granted = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(application)
                .contains(application.packageName)
        }.getOrDefault(false)
        _uiState.update { it.copy(notificationAccessGranted = granted) }
    }

    fun refreshJustWatchCacheStats() {
        viewModelScope.launch {
            val count = justWatchRepo.count()
            val negCount = justWatchRepo.negativeCount()
            val lastFetch = justWatchRepo.lastFetchedAt() ?: 0L
            _uiState.update {
                it.copy(
                    cachedDeepLinkCount = count,
                    negativeDeepLinkCount = negCount,
                    lastDeepLinkFetchMs = lastFetch,
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

    /**
     * Queries the WatchNext content provider once and updates [TvDiagnosticsUiState.watchNextCountResult].
     * Call on [Lifecycle.Event.ON_RESUME] so the Diagnostics screen always shows a fresh count.
     */
    fun refreshWatchNextStats() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { watchNextSource.countPublishingApps() }
            _uiState.update { it.copy(watchNextCountResult = result) }
        }
    }

    companion object {
        private const val MAX_RECENT_EVENTS = 100
    }
}
