package com.justb81.watchbuddy.phone.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.core.model.ScrobbleDisplayEvent
import com.justb81.watchbuddy.phone.llm.LlmEventLog
import com.justb81.watchbuddy.phone.network.WifiStateProvider
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionStateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LlmEventSummary(
    val id: Long,
    val caller: String,
    val backend: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val status: LlmEventLog.Status,
)

data class DiagnosticsUiState(
    val isOnWifi: Boolean = false,
    val wifiIpv4: String? = null,
    val serviceRunning: Boolean = false,
    val httpServerBinding: String? = null,
    val lastCapabilityCheckMs: Long = 0L,
    val bleState: CompanionStateManager.BleAdvertiseState = CompanionStateManager.BleAdvertiseState.IDLE,
    val bleErrorCode: Int? = null,
    val lastScrobble: ScrobbleDisplayEvent? = null,
    val llmActivityLoggingEnabled: Boolean = true,
    val llmEvents: List<LlmEventSummary> = emptyList(),
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    wifiStateProvider: WifiStateProvider,
    stateManager: CompanionStateManager,
    private val llmEventLog: LlmEventLog,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        // Fan-in: independent flows from the shared state manager + Wi-Fi provider.
        // `combine` only fires after every flow has emitted once, which is fine because
        // each source is a StateFlow with a defined initial value.
        val partA = combine(
            wifiStateProvider.isOnWifi,
            stateManager.wifiIpv4,
            stateManager.isServiceRunning,
            stateManager.httpServerBinding,
        ) { onWifi, ipv4, running, http ->
            DiagnosticsPartA(onWifi, ipv4, running, http)
        }
        val partB = combine(
            stateManager.lastCapabilityCheck,
            stateManager.bleAdvertiseState,
            stateManager.bleAdvertiseErrorCode,
            stateManager.lastScrobbleEvent,
        ) { lastCapCheck, ble, bleErr, scrobble ->
            DiagnosticsPartB(lastCapCheck, ble, bleErr, scrobble)
        }

        val llmEvents = llmEventLog.updates
            .onStart { emit(Unit) }
            .map { snapshotEvents() }
        val loggingEnabled = settingsRepository.settings
            .map { it.llmActivityLoggingEnabled }

        viewModelScope.launch {
            combine(partA, partB, llmEvents, loggingEnabled) { a, b, events, enabled ->
                DiagnosticsUiState(
                    isOnWifi = a.onWifi,
                    wifiIpv4 = a.ipv4,
                    serviceRunning = a.running,
                    httpServerBinding = a.httpBinding,
                    lastCapabilityCheckMs = b.lastCapCheck,
                    bleState = b.ble,
                    bleErrorCode = b.bleErrorCode,
                    lastScrobble = b.scrobble,
                    llmActivityLoggingEnabled = enabled,
                    llmEvents = events,
                )
            }.collect { _uiState.value = it }
        }
    }

    private fun snapshotEvents(): List<LlmEventSummary> =
        llmEventLog.snapshot().take(MAX_EVENTS).map {
            LlmEventSummary(
                id = it.id,
                caller = it.caller,
                backend = it.backend,
                startedAtMs = it.startedAtMs,
                durationMs = it.durationMs,
                status = it.status,
            )
        }

    private data class DiagnosticsPartA(
        val onWifi: Boolean,
        val ipv4: String?,
        val running: Boolean,
        val httpBinding: String?,
    )

    private data class DiagnosticsPartB(
        val lastCapCheck: Long,
        val ble: CompanionStateManager.BleAdvertiseState,
        val bleErrorCode: Int?,
        val scrobble: ScrobbleDisplayEvent?,
    )

    private companion object {
        const val MAX_EVENTS = 20
    }
}
