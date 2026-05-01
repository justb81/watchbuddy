package com.justb81.watchbuddy.tv.ui.diagnostics

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntentProvider
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntentStats
import com.justb81.watchbuddy.tv.data.JustWatchDeepLinkRepository
import com.justb81.watchbuddy.tv.data.JustWatchOutcomeEvent
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.scrobbler.NotificationMetadataSource
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
    val lastJustWatchError: String? = null,
    val justWatchSearchMisses24h: Int = 0,
    /** null = not yet checked, PermissionDenied or Success from the WatchNext provider query. */
    val watchNextCountResult: WatchNextMetadataSource.CountResult? = null,
    /**
     * Count of distinct packages with a media notification observed in the last
     * [NotificationMetadataSource.DIAGNOSTICS_WINDOW_MS] (10 min), or null before first refresh.
     */
    val notificationTrackedCount: Int? = null,
    /**
     * Observed-package stats since [MediaSessionScrobbler.startListening]: how many
     * distinct packages have been seen in `getActiveSessions` and how many of those
     * have an entry in [AppProfiles]. Null before the first refresh.
     */
    val appProfileStats: MediaSessionScrobbler.ObservedPackageStats? = null,
    /** Counters for ambiguous-prompt lifecycle (emitted / resolved / dismissed). Null before first refresh. */
    val ambiguousPromptStats: MediaSessionScrobbler.AmbiguousPromptStats? = null,
    /** Counters for Watch-Now intent lifecycle (hits / fallthroughs / manual-mark overrides). Null before first refresh. */
    val intentStats: PlaybackIntentStats? = null,
    /**
     * The last 5 JustWatch resolution outcomes for display in the Streaming Links diagnostics section.
     * Most recent event is last. Empty until the user navigates to a show-detail screen.
     */
    val recentJustWatchOutcomes: List<JustWatchOutcomeEvent> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TvDiagnosticsViewModel @Inject constructor(
    private val application: Application,
    phoneDiscovery: PhoneDiscoveryManager,
    private val scrobbler: MediaSessionScrobbler,
    private val justWatchRepo: JustWatchDeepLinkRepository,
    private val watchNextSource: WatchNextMetadataSource,
    private val notificationSource: NotificationMetadataSource,
    private val intentProvider: PlaybackIntentProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvDiagnosticsUiState())
    val uiState: StateFlow<TvDiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refreshNotificationAccess()
        refreshJustWatchCacheStats()
        refreshWatchNextStats()
        refreshNotificationStats()
        refreshAppProfileStats()
        refreshAmbiguousPromptStats()
        refreshIntentStats()

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
                    notificationTrackedCount = _uiState.value.notificationTrackedCount,
                )
            }.collect { snapshot ->
                _uiState.update {
                    snapshot.copy(
                        notificationAccessGranted = it.notificationAccessGranted,
                        recentEvents = it.recentEvents,
                        cachedDeepLinkCount = it.cachedDeepLinkCount,
                        negativeDeepLinkCount = it.negativeDeepLinkCount,
                        lastDeepLinkFetchMs = it.lastDeepLinkFetchMs,
                        lastJustWatchError = it.lastJustWatchError,
                        justWatchSearchMisses24h = it.justWatchSearchMisses24h,
                        watchNextCountResult = it.watchNextCountResult,
                        notificationTrackedCount = it.notificationTrackedCount,
                        appProfileStats = it.appProfileStats,
                        ambiguousPromptStats = it.ambiguousPromptStats,
                        intentStats = it.intentStats,
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

    /**
     * Queries the WatchNext content provider once and updates [TvDiagnosticsUiState.watchNextCountResult].
     * Call on [Lifecycle.Event.ON_RESUME] so the Diagnostics screen always shows a fresh count.
     *
     * Clears the cached permission-denied flag first so that a permission grant the user just
     * made in the system settings is picked up immediately when they return to this screen.
     */
    fun refreshWatchNextStats() {
        viewModelScope.launch {
            watchNextSource.resetPermissionState()
            val result = withContext(Dispatchers.IO) { watchNextSource.countPublishingApps() }
            _uiState.update { it.copy(watchNextCountResult = result) }
        }
    }

    /**
     * Reads the in-memory notification snippet map and updates
     * [TvDiagnosticsUiState.notificationTrackedCount]. This is a fast in-memory
     * read — no IO dispatcher required. Call on [Lifecycle.Event.ON_RESUME].
     */
    fun refreshNotificationStats() {
        _uiState.update { it.copy(notificationTrackedCount = notificationSource.recentlyObservedCount()) }
    }

    /**
     * Reads the in-memory observed-package map and updates
     * [TvDiagnosticsUiState.appProfileStats]. This is a fast in-memory read —
     * no IO dispatcher required. Call on [Lifecycle.Event.ON_RESUME].
     */
    fun refreshAppProfileStats() {
        _uiState.update { it.copy(appProfileStats = scrobbler.observedPackageStats()) }
    }

    /**
     * Reads the in-memory ambiguous-prompt counters and updates
     * [TvDiagnosticsUiState.ambiguousPromptStats]. Fast in-memory read.
     * Call on [Lifecycle.Event.ON_RESUME].
     */
    fun refreshAmbiguousPromptStats() {
        _uiState.update { it.copy(ambiguousPromptStats = scrobbler.ambiguousPromptStats()) }
    }

    /**
     * Reads the in-memory Watch-Now intent counters and updates
     * [TvDiagnosticsUiState.intentStats]. Fast in-memory read.
     * Call on [Lifecycle.Event.ON_RESUME].
     */
    fun refreshIntentStats() {
        _uiState.update { it.copy(intentStats = intentProvider.intentStats()) }
    }

    companion object {
        private const val MAX_RECENT_EVENTS = 100
        private const val MAX_DISPLAYED_OUTCOMES = 5
    }
}
