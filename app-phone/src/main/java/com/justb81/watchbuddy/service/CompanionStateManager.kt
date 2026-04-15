package com.justb81.watchbuddy.service

import com.justb81.watchbuddy.core.model.ScrobbleDisplayEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared state between [CompanionService], [CompanionHttpServer], and the
 * phone HomeViewModel.  Lives as a Hilt singleton to avoid circular dependencies
 * between the service, HTTP server, and UI layers.
 */
@Singleton
class CompanionStateManager @Inject constructor() {

    private val _lastCapabilityCheck = MutableStateFlow(0L)
    /** Epoch millis of the most recent `/capability` request from a TV. */
    val lastCapabilityCheck: StateFlow<Long> = _lastCapabilityCheck.asStateFlow()

    private val _lastScrobbleEvent = MutableStateFlow<ScrobbleDisplayEvent?>(null)
    /** Most recent scrobble event received via the companion HTTP server. */
    val lastScrobbleEvent: StateFlow<ScrobbleDisplayEvent?> = _lastScrobbleEvent.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    /** Whether the [CompanionService] foreground service is currently active. */
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    fun onCapabilityChecked() {
        _lastCapabilityCheck.value = System.currentTimeMillis()
    }

    fun onScrobbleEvent(event: ScrobbleDisplayEvent) {
        _lastScrobbleEvent.value = event
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }
}
