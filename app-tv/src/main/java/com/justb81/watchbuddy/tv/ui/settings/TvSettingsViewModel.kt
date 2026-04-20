package com.justb81.watchbuddy.tv.ui.settings

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvSettingsUiState(
    val isPhoneDiscoveryEnabled: Boolean = true,
    val isAutostartEnabled: Boolean = false,
    val isNotificationAccessGranted: Boolean = false,
    val showNonInstalledProviders: Boolean = false,
)

@HiltViewModel
class TvSettingsViewModel @Inject constructor(
    private val application: Application,
    private val repository: StreamingPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvSettingsUiState())
    val uiState: StateFlow<TvSettingsUiState> = _uiState.asStateFlow()

    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        DiagnosticLog.error(TAG, "tv settings write failed", t)
    }

    private fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(exceptionHandler, block = block)

    init {
        refreshNotificationAccess()
        launchSafe {
            combine(
                repository.isPhoneDiscoveryEnabled,
                repository.isAutostartEnabled,
                repository.showNonInstalledProviders,
            ) { discovery, autostart, nonInstalled -> Triple(discovery, autostart, nonInstalled) }
                .catch { e -> DiagnosticLog.error(TAG, "tv settings observation failed", e) }
                .collect { (discovery, autostart, nonInstalled) ->
                    _uiState.update {
                        it.copy(
                            isPhoneDiscoveryEnabled = discovery,
                            isAutostartEnabled = autostart,
                            showNonInstalledProviders = nonInstalled,
                        )
                    }
                }
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

    fun setShowNonInstalledProviders(show: Boolean) {
        _uiState.update { it.copy(showNonInstalledProviders = show) }
        launchSafe { repository.setShowNonInstalledProviders(show) }
    }

    /**
     * Re-read the system notification-access allowlist. Called from the UI
     * on every ON_RESUME because there is no broadcast to observe — the user
     * flips the toggle in system Settings and we only learn about it on
     * return to our own screen. Wrapped in runCatching so unit tests (which
     * run against a stubbed Android JAR) don't throw on the static
     * android.provider.Settings.Secure lookup inside NotificationManagerCompat.
     */
    fun refreshNotificationAccess() {
        val granted = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(application)
                .contains(application.packageName)
        }.getOrDefault(false)
        _uiState.update { it.copy(isNotificationAccessGranted = granted) }
    }

    companion object {
        private const val TAG = "TvSettingsViewModel"
    }
}
