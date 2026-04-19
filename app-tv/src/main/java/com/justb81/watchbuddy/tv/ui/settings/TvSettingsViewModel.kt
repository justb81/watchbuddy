package com.justb81.watchbuddy.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvSettingsUiState(
    val isPhoneDiscoveryEnabled: Boolean = true,
    val isAutostartEnabled: Boolean = false,
)

@HiltViewModel
class TvSettingsViewModel @Inject constructor(
    private val repository: StreamingPreferencesRepository
) : ViewModel() {

    companion object {
        private const val TAG = "TvSettingsViewModel"
    }

    private val _uiState = MutableStateFlow(TvSettingsUiState())
    val uiState: StateFlow<TvSettingsUiState> = _uiState.asStateFlow()

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        DiagnosticLog.error(TAG, "settings prefs write failed", throwable)
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(coroutineExceptionHandler, block = block)

    init {
        launchSafe {
            combine(
                repository.isPhoneDiscoveryEnabled,
                repository.isAutostartEnabled,
            ) { discovery, autostart ->
                TvSettingsUiState(
                    isPhoneDiscoveryEnabled = discovery,
                    isAutostartEnabled = autostart,
                )
            }
            .catch { e -> DiagnosticLog.error(TAG, "settings prefs observation failed", e) }
            .collect { _uiState.value = it }
        }
    }

    fun setPhoneDiscoveryEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isPhoneDiscoveryEnabled = enabled) }
        launchSafe { repository.setPhoneDiscoveryEnabled(enabled) }
    }

    fun setAutostartEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isAutostartEnabled = enabled) }
        launchSafe { repository.setAutostartEnabled(enabled) }
    }
}
