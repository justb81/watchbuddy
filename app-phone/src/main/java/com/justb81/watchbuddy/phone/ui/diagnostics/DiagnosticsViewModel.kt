package com.justb81.watchbuddy.phone.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.core.model.ScrobbleDisplayEvent
import com.justb81.watchbuddy.phone.network.WifiStateProvider
import com.justb81.watchbuddy.service.CompanionStateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsUiState(
    val isOnWifi: Boolean = false,
    val wifiIpv4: String? = null,
    val multicastLockHeld: Boolean = false,
    val serviceRunning: Boolean = false,
    val nsdState: CompanionStateManager.NsdRegistrationState = CompanionStateManager.NsdRegistrationState.IDLE,
    val nsdErrorCode: Int? = null,
    val httpServerBinding: String? = null,
    val lastCapabilityCheckMs: Long = 0L,
    val bleState: CompanionStateManager.BleAdvertiseState = CompanionStateManager.BleAdvertiseState.IDLE,
    val bleErrorCode: Int? = null,
    val lastScrobble: ScrobbleDisplayEvent? = null,
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    wifiStateProvider: WifiStateProvider,
    stateManager: CompanionStateManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        // Fan-in: eight independent flows from the shared state manager + Wi-Fi provider.
        // `combine` only fires after every flow has emitted once, which is fine because
        // each source is a StateFlow with a defined initial value.
        val partA = combine(
            wifiStateProvider.isOnWifi,
            stateManager.wifiIpv4,
            stateManager.multicastLockHeld,
            stateManager.isServiceRunning,
        ) { onWifi, ipv4, lockHeld, running ->
            DiagnosticsPartA(onWifi, ipv4, lockHeld, running)
        }
        val partB = combine(
            stateManager.nsdRegistrationState,
            stateManager.nsdErrorCode,
            stateManager.httpServerBinding,
            stateManager.lastCapabilityCheck,
        ) { nsd, nsdErr, http, lastCapCheck ->
            DiagnosticsPartB(nsd, nsdErr, http, lastCapCheck)
        }
        val partC = combine(
            stateManager.bleAdvertiseState,
            stateManager.bleAdvertiseErrorCode,
            stateManager.lastScrobbleEvent,
        ) { ble, bleErr, scrobble ->
            DiagnosticsPartC(ble, bleErr, scrobble)
        }

        viewModelScope.launch {
            combine(partA, partB, partC) { a, b, c ->
                DiagnosticsUiState(
                    isOnWifi = a.onWifi,
                    wifiIpv4 = a.ipv4,
                    multicastLockHeld = a.lockHeld,
                    serviceRunning = a.running,
                    nsdState = b.nsd,
                    nsdErrorCode = b.nsdErrorCode,
                    httpServerBinding = b.httpBinding,
                    lastCapabilityCheckMs = b.lastCapCheck,
                    bleState = c.ble,
                    bleErrorCode = c.bleErrorCode,
                    lastScrobble = c.scrobble,
                )
            }.map { it }.collect { _uiState.value = it }
        }
    }

    private data class DiagnosticsPartA(
        val onWifi: Boolean,
        val ipv4: String?,
        val lockHeld: Boolean,
        val running: Boolean,
    )

    private data class DiagnosticsPartB(
        val nsd: CompanionStateManager.NsdRegistrationState,
        val nsdErrorCode: Int?,
        val httpBinding: String?,
        val lastCapCheck: Long,
    )

    private data class DiagnosticsPartC(
        val ble: CompanionStateManager.BleAdvertiseState,
        val bleErrorCode: Int?,
        val scrobble: ScrobbleDisplayEvent?,
    )
}
