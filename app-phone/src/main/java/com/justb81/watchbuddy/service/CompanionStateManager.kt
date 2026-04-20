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

    enum class BleAdvertiseState { IDLE, ADVERTISING, FAILED }

    private val _lastCapabilityCheck = MutableStateFlow(0L)
    /** Epoch millis of the most recent `/capability` request from a TV. */
    val lastCapabilityCheck: StateFlow<Long> = _lastCapabilityCheck.asStateFlow()

    private val _lastScrobbleEvent = MutableStateFlow<ScrobbleDisplayEvent?>(null)
    /** Most recent scrobble event received via the companion HTTP server. */
    val lastScrobbleEvent: StateFlow<ScrobbleDisplayEvent?> = _lastScrobbleEvent.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    /** Whether the [CompanionService] foreground service is currently active. */
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _httpServerBinding = MutableStateFlow<String?>(null)
    /** Bound listen address of [CompanionHttpServer] (e.g. `0.0.0.0:8765`), or null when stopped. */
    val httpServerBinding: StateFlow<String?> = _httpServerBinding.asStateFlow()

    private val _bleAdvertiseState = MutableStateFlow(BleAdvertiseState.IDLE)

    /** Current state of the BLE advertiser (the sole discovery channel). */
    val bleAdvertiseState: StateFlow<BleAdvertiseState> = _bleAdvertiseState.asStateFlow()

    private val _bleAdvertiseErrorCode = MutableStateFlow<Int?>(null)
    /** Last BLE `onStartFailure` error code, or null. */
    val bleAdvertiseErrorCode: StateFlow<Int?> = _bleAdvertiseErrorCode.asStateFlow()

    private val _wifiIpv4 = MutableStateFlow<String?>(null)
    /** Latest resolved Wi-Fi IPv4 address seen by [CompanionService], or null off Wi-Fi. */
    val wifiIpv4: StateFlow<String?> = _wifiIpv4.asStateFlow()

    fun onCapabilityChecked() {
        _lastCapabilityCheck.value = System.currentTimeMillis()
    }

    /** Test-only overload — production always goes through [onCapabilityChecked]. */
    internal fun onCapabilityCheckedAt(now: Long) {
        _lastCapabilityCheck.value = now
    }

    fun onScrobbleEvent(event: ScrobbleDisplayEvent) {
        _lastScrobbleEvent.value = event
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
        if (!running) {
            // Reset transient state so the Diagnostics view doesn't report
            // stale flags once the foreground service is gone.
            _httpServerBinding.value = null
            _bleAdvertiseState.value = BleAdvertiseState.IDLE
            _bleAdvertiseErrorCode.value = null
            _wifiIpv4.value = null
            _lastCapabilityCheck.value = 0L
        }
    }

    fun setHttpServerBinding(binding: String?) {
        _httpServerBinding.value = binding
    }

    fun setBleAdvertiseState(state: BleAdvertiseState, errorCode: Int? = null) {
        _bleAdvertiseState.value = state
        if (state == BleAdvertiseState.FAILED) {
            _bleAdvertiseErrorCode.value = errorCode
        } else if (state == BleAdvertiseState.ADVERTISING || state == BleAdvertiseState.IDLE) {
            _bleAdvertiseErrorCode.value = null
        }
    }

    fun setWifiIpv4(address: String?) {
        _wifiIpv4.value = address
    }
}
