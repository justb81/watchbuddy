package com.justb81.watchbuddy.tv.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers WatchBuddy companion phones via BLE — the sole discovery channel.
 *
 * Discovery flow:
 *   1. [PhoneBleScanner] decodes an advertisement → (ipv4, port, modelQuality,
 *      llmBackend, rssi).
 *   2. `/capability` is fetched over LAN for full data (tmdbApiKey, freeRamMb,
 *      userAvatarUrl).
 *   3. If `/capability` fails, the phone is still added using the BLE payload
 *      alone so it shows up in the ranked list.
 *
 * Ranking formula:
 *   Score = modelQuality (0–150) + ramBonus (0–10, only when capability is available)
 */
@Singleton
class PhoneDiscoveryManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val bleScanner: PhoneBleScanner,
) {
    companion object {
        const val CAPABILITY_PATH = "/capability"
        private const val TAG = "PhoneDiscoveryManager"
    }

    enum class BleScanState { IDLE, SCANNING, FAILED }

    private val _discoveredPhones = MutableStateFlow<List<DiscoveredPhone>>(emptyList())
    val discoveredPhones: StateFlow<List<DiscoveredPhone>> = _discoveredPhones

    private val _discoveryActive = MutableStateFlow(false)
    /** True between [startDiscovery] and [stopDiscovery]. */
    val discoveryActive: StateFlow<Boolean> = _discoveryActive

    private val _bleScanState = MutableStateFlow(BleScanState.IDLE)

    /** Current state of the BLE scanner. */
    val bleScanState: StateFlow<BleScanState> = _bleScanState

    private val _bleScanErrorCode = MutableStateFlow<Int?>(null)
    /** Last `onScanFailed` error code reported by the BLE scanner, or null. */
    val bleScanErrorCode: StateFlow<Int?> = _bleScanErrorCode

    private val _lastHeartbeatTick = MutableStateFlow(0L)
    /** Epoch millis of the last heartbeat loop pass, or 0 before the first tick. */
    val lastHeartbeatTick: StateFlow<Long> = _lastHeartbeatTick

    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var isDiscovering: Boolean = false

    /**
     * Lightweight device info reconstructed from the BLE payload (or the
     * TXT record of a legacy NSD resolve). Available immediately, before
     * any `/capability` round-trip.
     */
    data class PhoneTxtRecord(
        val version: String,
        val modelQuality: Int,
        val llmBackend: LlmBackend,
    )

    data class DiscoveredPhone(
        val serviceInfo: NsdServiceInfo,
        val txtRecord: PhoneTxtRecord?,
        val capability: DeviceCapability?,
        val score: Int,
        val baseUrl: String,
        val failCount: Int = 0,
        /**
         * RSSI of the most recent BLE advertisement for this phone, in dBm.
         * Null for phones that have not been seen via BLE this session
         * (heartbeats do not carry a fresh RSSI). Surfaced read-only in
         * TV Diagnostics — no automatic filtering yet.
         */
        val rssi: Int? = null,
        val lastSuccessfulCheck: Long = System.currentTimeMillis()
    )

    fun startDiscovery() {
        Log.i(TAG, "startDiscovery (BLE-only)")
        isDiscovering = true
        _discoveryActive.value = true
        startBleScan()
        startHeartbeat()
        registerNetworkCallback()
    }

    fun stopDiscovery() {
        Log.i(TAG, "stopDiscovery")
        isDiscovering = false
        _discoveryActive.value = false
        heartbeatJob?.cancel()
        unregisterNetworkCallback()
        bleScanner.stop()
        _bleScanState.value = BleScanState.IDLE
        _bleScanErrorCode.value = null
    }

    /**
     * Idempotent lifecycle switch driven by the user's "Phone discovery" setting
     * and by [TvDiscoveryService]. Re-entrant: safe to call repeatedly with the
     * same value. On disable the discovered-phone list is cleared immediately so
     * the UI reflects the change without waiting for heartbeat timeouts.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            if (!isDiscovering) startDiscovery()
        } else {
            if (isDiscovering) stopDiscovery()
            _discoveredPhones.value = emptyList()
        }
    }

    // Mirrors the phone's CompanionService network callback: if the TV's Wi-Fi
    // flickers, the BLE stack can silently go dead. Restart the scanner when
    // Wi-Fi becomes available again.
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
        }.getOrNull() ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isDiscovering) return
                Log.i(TAG, "Wi-Fi available — restarting BLE scanner")
                startBleScan()
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed", it) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        runCatching {
            context.applicationContext
                .getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(cb)
        }
        networkCallback = null
    }

    private fun startHeartbeat() {
        heartbeatJob = heartbeatScope.launch {
            while (true) {
                delay(DiscoveryConstants.HEARTBEAT_INTERVAL_MS)
                _lastHeartbeatTick.value = System.currentTimeMillis()
                checkAllPhones()
            }
        }
    }

    private suspend fun checkAllPhones() {
        val phones = _discoveredPhones.value
        if (phones.isEmpty()) return

        val updated = phones.mapNotNull { phone ->
            val url = capabilityUrl(phone.baseUrl)
            try {
                val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
                val capability = response.body?.string()?.let {
                    Json.decodeFromString<DeviceCapability>(it)
                }
                val newScore = calculateScore(phone.txtRecord, capability)
                phone.copy(
                    capability = capability ?: phone.capability,
                    score = newScore,
                    failCount = 0,
                    lastSuccessfulCheck = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                val newFailCount = phone.failCount + 1
                if (newFailCount >= DiscoveryConstants.MAX_CONSECUTIVE_FAILURES) {
                    Log.i(TAG, "Removing phone ${phone.baseUrl} after ${DiscoveryConstants.MAX_CONSECUTIVE_FAILURES} failed heartbeats")
                    null
                } else {
                    phone.copy(failCount = newFailCount)
                }
            }
        }
        _discoveredPhones.value = updated.sortedByDescending { it.score }
    }

    /**
     * Returns the best available phone for recap generation, or null if none available.
     *
     * Phones that have TXT records but no capability (e.g. /capability fetch failed) are
     * included in the ranking and treated as available unless capability explicitly marks
     * them unavailable.
     */
    fun getBestPhone(): DiscoveredPhone? =
        _discoveredPhones.value
            .filter { it.capability?.isAvailable != false }
            .maxByOrNull { it.score }

    @VisibleForTesting
    internal fun setDiscoveredPhonesForTest(phones: List<DiscoveredPhone>) {
        _discoveredPhones.value = phones
    }

    private fun addOrUpdatePhone(phone: DiscoveredPhone) {
        // Dedup by baseUrl. baseUrl embeds host:port, which is the
        // authoritative address of the HTTP server.
        _discoveredPhones.value = (_discoveredPhones.value
            .filter { it.baseUrl != phone.baseUrl } + phone)
            .sortedByDescending { it.score }
    }

    /**
     * Canonical phone base-URL construction. Guarantees a single trailing slash
     * so concatenating [CAPABILITY_PATH] can't yield `//capability`.
     */
    private fun phoneBaseUrl(host: String, port: Int): String = "http://$host:$port/"

    /**
     * Builds the `/capability` URL from a base URL emitted by [phoneBaseUrl].
     * Tolerates both `.../` and `...` shapes.
     */
    private fun capabilityUrl(baseUrl: String): String =
        "${baseUrl.trimEnd('/')}$CAPABILITY_PATH"

    /**
     * Entry point for advertisements surfaced by [PhoneBleScanner]. When the
     * baseUrl is already known we refresh the cached RSSI in place; otherwise
     * we kick off a `/capability` fetch and add the phone to the discovered
     * list on success (or as a TXT-only entry on failure).
     *
     * BLE adverts fire every ~250 ms, so the hot path (phone already known)
     * must avoid HTTP work.
     */
    internal fun onBleAdvertisement(
        ipv4: Inet4Address,
        port: Int,
        modelQuality: Int,
        llmBackendOrdinal: Int,
        rssi: Int,
    ) {
        val hostAddress = ipv4.hostAddress ?: return
        val baseUrl = phoneBaseUrl(hostAddress, port)
        val existing = _discoveredPhones.value.firstOrNull { it.baseUrl == baseUrl }
        if (existing != null) {
            // Refresh the cached RSSI without re-ranking — score is driven
            // by heartbeat results, not BLE signal strength.
            _discoveredPhones.value = _discoveredPhones.value.map {
                if (it.baseUrl == baseUrl) it.copy(rssi = rssi) else it
            }
            return
        }

        val synthInfo = NsdServiceInfo().apply {
            serviceName = "watchbuddy-ble-$hostAddress-$port"
            this.port = port
            host = ipv4
        }
        val txtRecord = PhoneTxtRecord(
            version = "", // unknown until /capability is fetched
            modelQuality = modelQuality,
            llmBackend = runCatching { LlmBackend.entries[llmBackendOrdinal] }
                .getOrDefault(LlmBackend.NONE),
        )
        Log.i(TAG, "BLE advertisement → resolving phone at $baseUrl (rssi=$rssi dBm)")
        heartbeatScope.launch {
            fetchCapabilityAndAdd(synthInfo, txtRecord, baseUrl, rssi)
        }
    }

    /**
     * Fetches `/capability`, ranks the phone, and adds it to the shared list
     * via [addOrUpdatePhone]. Falls back to a TXT-only entry on HTTP failure
     * so the phone still shows up for ranking.
     */
    private fun fetchCapabilityAndAdd(
        serviceInfo: NsdServiceInfo,
        txtRecord: PhoneTxtRecord,
        baseUrl: String,
        rssi: Int,
    ) {
        val url = capabilityUrl(baseUrl)
        try {
            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
            val capability = response.body?.string()?.let {
                Json.decodeFromString<DeviceCapability>(it)
            }
            val score = calculateScore(txtRecord, capability)
            addOrUpdatePhone(DiscoveredPhone(serviceInfo, txtRecord, capability, score, baseUrl, rssi = rssi))
        } catch (e: Exception) {
            Log.w(TAG, "Phone discovered at $url but capability fetch failed: ${e.message}")
            val score = calculateScore(txtRecord, null)
            addOrUpdatePhone(DiscoveredPhone(serviceInfo, txtRecord, null, score, baseUrl, rssi = rssi))
        }
    }

    private fun startBleScan() {
        val started = bleScanner.start(
            listener = { ipv4, port, quality, backend, rssi ->
                onBleAdvertisement(ipv4, port, quality, backend, rssi)
            },
            onFailure = { errorCode ->
                _bleScanState.value = BleScanState.FAILED
                _bleScanErrorCode.value = errorCode
            },
        )
        if (started) {
            _bleScanState.value = BleScanState.SCANNING
            _bleScanErrorCode.value = null
        } else if (_bleScanState.value != BleScanState.FAILED) {
            // Permission denied, BLE off, or unsupported hardware — all soft failures.
            _bleScanState.value = BleScanState.IDLE
        }
    }

    /**
     * Device ranking formula:
     *   Score = modelQuality (0–150) + RAM bonus (0–10, only when capability is available)
     *
     * When only the BLE payload is available (capability fetch failed), modelQuality
     * from the payload is used directly with no RAM bonus.
     */
    @VisibleForTesting
    internal fun calculateScore(txt: PhoneTxtRecord?, cap: DeviceCapability?): Int {
        if (cap != null) {
            val ramBonus = when {
                cap.freeRamMb >= 6_000 -> 10
                cap.freeRamMb >= 4_000 -> 6
                cap.freeRamMb >= 3_000 -> 3
                else -> 0
            }
            return cap.modelQuality + ramBonus
        }
        return txt?.modelQuality ?: 0
    }
}
