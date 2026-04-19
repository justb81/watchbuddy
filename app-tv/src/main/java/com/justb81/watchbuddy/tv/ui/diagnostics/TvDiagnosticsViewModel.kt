package com.justb81.watchbuddy.tv.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvDiagnosticsUiState(
    val discoveryActive: Boolean = false,
    val multicastLockHeld: Boolean = false,
    val bleScanState: PhoneDiscoveryManager.BleScanState = PhoneDiscoveryManager.BleScanState.IDLE,
    val bleScanErrorCode: Int? = null,
    val lastHeartbeatMs: Long = 0L,
    val phones: List<PhoneDiscoveryManager.DiscoveredPhone> = emptyList(),
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TvDiagnosticsViewModel @Inject constructor(
    phoneDiscovery: PhoneDiscoveryManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvDiagnosticsUiState())
    val uiState: StateFlow<TvDiagnosticsUiState> = _uiState.asStateFlow()

    init {
        val partA = combine(
            phoneDiscovery.discoveryActive,
            phoneDiscovery.multicastLockHeld,
            phoneDiscovery.bleScanState,
        ) { active, lock, ble ->
            Triple(active, lock, ble)
        }
        val partB = combine(
            phoneDiscovery.bleScanErrorCode,
            phoneDiscovery.lastHeartbeatTick,
            phoneDiscovery.discoveredPhones,
        ) { bleErr, tick, phones ->
            Triple(bleErr, tick, phones)
        }
        viewModelScope.launch {
            combine(partA, partB) { a, b ->
                TvDiagnosticsUiState(
                    discoveryActive = a.first,
                    multicastLockHeld = a.second,
                    bleScanState = a.third,
                    bleScanErrorCode = b.first,
                    lastHeartbeatMs = b.second,
                    phones = b.third,
                )
            }.collect { _uiState.value = it }
        }
    }
}
