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

    companion object {
        /** Consecutive in-cadence `/capability` polls needed to enter steady state. */
        internal const val STEADY_STATE_STREAK = 3

        /**
         * Maximum tolerated gap between two `/capability` polls before we
         * count a heartbeat miss. TV side polls every 60 s; 90 s gives one
         * late poll of slack before the streak resets.
         */
        internal const val MISS_THRESHOLD_MS = 90_000L

        /**
         * Minimum quiet window (no misses) required before the streak counter
         * can flip us back into `pairedSteadyState`. Prevents a TV that
         * briefly drops Wi-Fi every ~90 s from making the BLE advertiser
         * oscillate between BALANCED and LOW_POWER modes.
         */
        internal const val HYSTERESIS_MS = 2 * 60_000L
    }

    enum class NsdRegistrationState { IDLE, REGISTERING, REGISTERED, UNREGISTERING, FAILED }

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

    private val _nsdRegistrationState = MutableStateFlow(NsdRegistrationState.IDLE)
    /** Mirror of the NSD state machine inside [CompanionService]. */
    val nsdRegistrationState: StateFlow<NsdRegistrationState> = _nsdRegistrationState.asStateFlow()

    private val _nsdErrorCode = MutableStateFlow<Int?>(null)
    /** Last NSD registration failure code reported by the system, or null. */
    val nsdErrorCode: StateFlow<Int?> = _nsdErrorCode.asStateFlow()

    private val _httpServerBinding = MutableStateFlow<String?>(null)
    /** Bound listen address of [CompanionHttpServer] (e.g. `0.0.0.0:8765`), or null when stopped. */
    val httpServerBinding: StateFlow<String?> = _httpServerBinding.asStateFlow()

    private val _multicastLockHeld = MutableStateFlow(false)
    /** Whether [CompanionService] currently holds a Wi-Fi multicast lock. */
    val multicastLockHeld: StateFlow<Boolean> = _multicastLockHeld.asStateFlow()

    private val _bleAdvertiseState = MutableStateFlow(BleAdvertiseState.IDLE)
    /** Current state of the BLE fallback advertiser. */
    val bleAdvertiseState: StateFlow<BleAdvertiseState> = _bleAdvertiseState.asStateFlow()

    private val _bleAdvertiseErrorCode = MutableStateFlow<Int?>(null)
    /** Last BLE `onStartFailure` error code, or null. */
    val bleAdvertiseErrorCode: StateFlow<Int?> = _bleAdvertiseErrorCode.asStateFlow()

    private val _wifiIpv4 = MutableStateFlow<String?>(null)
    /** Latest resolved Wi-Fi IPv4 address seen by [CompanionService], or null off Wi-Fi. */
    val wifiIpv4: StateFlow<String?> = _wifiIpv4.asStateFlow()

    private val _pairedSteadyState = MutableStateFlow(false)
    /**
     * True once the phone has received [STEADY_STATE_STREAK] consecutive
     * `/capability` polls from TVs within the expected cadence and no miss
     * has invalidated the streak. Consumers (notably [CompanionBleAdvertiser])
     * can demote to a lower-power BLE advertise mode while this is true,
     * because a TV that's polling us over Wi-Fi doesn't need a fast-cadence
     * BLE beacon to rediscover us (#345 Opt B).
     *
     * Reverts to false on any heartbeat miss (poll gap > [MISS_THRESHOLD_MS]).
     * After a miss, the [HYSTERESIS_MS] window guards re-entry so a flaky TV
     * that drops Wi-Fi every 90s doesn't make us oscillate.
     */
    val pairedSteadyState: StateFlow<Boolean> = _pairedSteadyState.asStateFlow()

    @Volatile private var consecutiveChecks: Int = 0
    @Volatile private var lastMissAtMs: Long = 0L

    fun onCapabilityChecked() {
        onCapabilityCheckedAt(System.currentTimeMillis())
    }

    /** Test-only overload — production always goes through [onCapabilityChecked]. */
    internal fun onCapabilityCheckedAt(now: Long) {
        val previous = _lastCapabilityCheck.value
        _lastCapabilityCheck.value = now

        // First poll of the session — can't tell cadence yet, just start counting.
        if (previous == 0L) {
            consecutiveChecks = 1
            return
        }
        val gap = now - previous
        if (gap > MISS_THRESHOLD_MS) {
            // Heartbeat miss: reset streak, remember when it happened so
            // hysteresis can guard re-entry to steady-state.
            consecutiveChecks = 1
            lastMissAtMs = now
            if (_pairedSteadyState.value) _pairedSteadyState.value = false
            return
        }
        consecutiveChecks += 1
        val hysteresisOk = now - lastMissAtMs >= HYSTERESIS_MS
        if (consecutiveChecks >= STEADY_STATE_STREAK && hysteresisOk && !_pairedSteadyState.value) {
            _pairedSteadyState.value = true
        }
    }

    fun onScrobbleEvent(event: ScrobbleDisplayEvent) {
        _lastScrobbleEvent.value = event
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
        if (!running) {
            // Reset transient state so the Diagnostics view doesn't report
            // stale flags once the foreground service is gone.
            _nsdRegistrationState.value = NsdRegistrationState.IDLE
            _nsdErrorCode.value = null
            _httpServerBinding.value = null
            _multicastLockHeld.value = false
            _bleAdvertiseState.value = BleAdvertiseState.IDLE
            _bleAdvertiseErrorCode.value = null
            _wifiIpv4.value = null
            _pairedSteadyState.value = false
            consecutiveChecks = 0
            lastMissAtMs = 0L
            _lastCapabilityCheck.value = 0L
        }
    }

    fun setNsdRegistrationState(state: NsdRegistrationState, errorCode: Int? = null) {
        _nsdRegistrationState.value = state
        if (state == NsdRegistrationState.FAILED) {
            _nsdErrorCode.value = errorCode
        } else if (state == NsdRegistrationState.REGISTERED || state == NsdRegistrationState.IDLE) {
            _nsdErrorCode.value = null
        }
    }

    fun setHttpServerBinding(binding: String?) {
        _httpServerBinding.value = binding
    }

    fun setMulticastLockHeld(held: Boolean) {
        _multicastLockHeld.value = held
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
