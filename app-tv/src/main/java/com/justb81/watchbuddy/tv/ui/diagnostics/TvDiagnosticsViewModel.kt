package com.justb81.watchbuddy.tv.ui.diagnostics

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TvDiagnosticsViewModel @Inject constructor(
    private val application: Application,
    phoneDiscovery: PhoneDiscoveryManager,
    scrobbler: MediaSessionScrobbler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvDiagnosticsUiState())
    val uiState: StateFlow<TvDiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refreshNotificationAccess()
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
        ) { listening, last -> listening to last }

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
                )
            }.collect { snapshot ->
                _uiState.update { snapshot.copy(notificationAccessGranted = it.notificationAccessGranted) }
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
}
