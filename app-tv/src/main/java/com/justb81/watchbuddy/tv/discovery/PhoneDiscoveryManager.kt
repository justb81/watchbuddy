package com.justb81.watchbuddy.tv.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
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
 * Heartbeat:
 *   A fast driver tick ([DiscoveryConstants.HEARTBEAT_TICK_MS]) consults a
 *   per-phone schedule. Each phone is polled independently with exponential
 *   backoff on failure (`POLL_BACKOFF_INITIAL_MS` doubling up to
 *   `POLL_BACKOFF_MAX_MS`). The driver is gated on Wi-Fi availability — when
 *   Wi-Fi is unavailable no polling happens and `failCount` does not advance,
 *   so a transient blip cannot evict a healthy phone. On Wi-Fi return every
 *   schedule is reset so the next tick re-probes immediately.
 *
 * Ranking formula:
 *   Score = modelQuality (0–150) + ramBonus (0–10, only when capability is available)
 */
@Singleton
class PhoneDiscoveryManager(
    @param:ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val bleScanner: PhoneBleScanner,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        bleScanner: PhoneBleScanner,
    ) : this(context, httpClient, bleScanner, System::currentTimeMillis)

    companion object {
        const val CAPABILITY_PATH = "/capability"
        private const val TAG = "PhoneDiscoveryManager"

        // Intentionally NOT WatchBuddyJson: capability traffic must be strict so
        // a truncated or malicious peer can't pass a partially-defaulted payload
        // through the lenient shared decoder. Missing optional fields with
        // `= default` still resolve normally; only unknown keys, lenient number
        // parsing, and null→default coercion are disabled.
        private val STRICT_CAPABILITY_JSON: Json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
        }
    }

    /** Outcome of a `/capability` fetch + parse + validate. */
    private sealed interface CapabilityResult {
        data class Ok(val capability: DeviceCapability) : CapabilityResult

        /** Network error, non-2xx, or missing body — recoverable, may retry. */
        data class TransportFailure(val reason: String) : CapabilityResult

        /** Decoded JSON but it failed schema or post-deserialize validation. */
        data class Invalid(val reason: String) : CapabilityResult
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

    // Defaults to true so that if Wi-Fi observation is unavailable (e.g.
    // ConnectivityManager service missing), polling still proceeds — degrading
    // to the previous always-poll behaviour rather than silently disabling
    // the heartbeat.
    private val _isWifiAvailable = MutableStateFlow(true)

    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var wifiResetJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var isDiscovering: Boolean = false

    /** Per-phone next-poll-due timestamp + current backoff, keyed by `baseUrl`. */
    private data class PollSchedule(val nextPollAt: Long, val backoffMs: Long)

    private val pollSchedules = ConcurrentHashMap<String, PollSchedule>()

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
        startWifiResetWatcher()
        registerNetworkCallback()
    }

    fun stopDiscovery() {
        Log.i(TAG, "stopDiscovery")
        isDiscovering = false
        _discoveryActive.value = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        wifiResetJob?.cancel()
        wifiResetJob = null
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
            pollSchedules.clear()
        }
    }

    // Mirrors the phone's CompanionService network callback: if the TV's Wi-Fi
    // flickers, the BLE stack can silently go dead. Restart the scanner when
    // Wi-Fi becomes available again, and gate the heartbeat loop on Wi-Fi
    // availability so a transient blip can't accumulate spurious failures.
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
                _isWifiAvailable.value = true
                startBleScan()
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Wi-Fi lost — suspending heartbeat polling")
                _isWifiAvailable.value = false
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess {
                networkCallback = callback
                // Seed initial state from the active network so we don't poll
                // until Wi-Fi is actually up.
                val activeWifi = runCatching {
                    val active = cm.activeNetwork ?: return@runCatching false
                    cm.getNetworkCapabilities(active)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }.getOrDefault(true)
                _isWifiAvailable.value = activeWifi
            }
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

    /**
     * Watches for Wi-Fi-return transitions (false → true) and resets every
     * phone's schedule so the next driver tick re-probes immediately.
     * StateFlow already conflates equal values, so the only emissions we
     * see are real transitions. We drop the initial replay so we don't
     * reset on subscription.
     */
    private fun startWifiResetWatcher() {
        wifiResetJob = heartbeatScope.launch {
            _isWifiAvailable
                .drop(1)
                .filter { it }
                .collect { resetAllSchedulesNow() }
        }
    }

    /**
     * Resets every phone's `nextPollAt` to now and `backoffMs` to the initial
     * backoff so the next driver tick re-probes immediately. `failCount` is
     * intentionally **not** zeroed — a phone that was genuinely unreachable
     * before this reset should still evict on schedule if it stays down.
     */
    @VisibleForTesting
    internal fun resetAllSchedulesNow() {
        val now = clock()
        pollSchedules.replaceAll { _, _ ->
            PollSchedule(
                nextPollAt = now,
                backoffMs = DiscoveryConstants.POLL_BACKOFF_INITIAL_MS,
            )
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = heartbeatScope.launch {
            flow {
                while (currentCoroutineContext().isActive) {
                    delay(DiscoveryConstants.HEARTBEAT_TICK_MS)
                    emit(Unit)
                }
            }.collect {
                _lastHeartbeatTick.value = clock()
                tick()
            }
        }
    }

    private suspend fun tick() {
        if (!_isWifiAvailable.value) return
        val now = clock()
        val phones = _discoveredPhones.value
        if (phones.isEmpty()) return

        val due = phones.filter { phone ->
            val schedule = pollSchedules[phone.baseUrl]
            schedule == null || schedule.nextPollAt <= now
        }
        if (due.isEmpty()) return

        val results: Map<String, DiscoveredPhone?> = coroutineScope {
            due.map { phone -> async { phone.baseUrl to checkOne(phone) } }
                .awaitAll()
                .toMap()
        }

        val updated = phones.mapNotNull { phone ->
            if (results.containsKey(phone.baseUrl)) results[phone.baseUrl] else phone
        }
        _discoveredPhones.value = updated.sortedByDescending { it.score }
        results.forEach { (baseUrl, replacement) ->
            if (replacement == null) pollSchedules.remove(baseUrl)
        }
    }

    /**
     * Polls a single phone's `/capability`, updates its schedule, and returns
     * either a copy of the phone (refreshed or with bumped `failCount`) or
     * null if the phone should be evicted.
     *
     * A peer that returns a structurally invalid `/capability` (malformed JSON
     * or values that fail [validateCapability]) is evicted immediately rather
     * than backed off — the phone is misbehaving, not flaky, so additional
     * polls won't help and ranking it is a security risk.
     */
    private fun checkOne(phone: DiscoveredPhone): DiscoveredPhone? {
        val now = clock()
        return when (val result = fetchCapability(phone.baseUrl)) {
            is CapabilityResult.Ok -> {
                val newScore = calculateScore(phone.txtRecord, result.capability)
                pollSchedules[phone.baseUrl] = PollSchedule(
                    nextPollAt = now + DiscoveryConstants.POLL_BASE_INTERVAL_MS,
                    backoffMs = DiscoveryConstants.POLL_BACKOFF_INITIAL_MS,
                )
                phone.copy(
                    capability = result.capability,
                    score = newScore,
                    failCount = 0,
                    lastSuccessfulCheck = now,
                )
            }
            is CapabilityResult.Invalid -> {
                DiagnosticLog.warn(
                    TAG,
                    "Invalid /capability from ${phone.baseUrl}: ${result.reason}"
                )
                Log.w(
                    TAG,
                    "Removing phone ${phone.baseUrl}: invalid capability (${result.reason})"
                )
                pollSchedules.remove(phone.baseUrl)
                null
            }
            is CapabilityResult.TransportFailure -> {
                val newFailCount = phone.failCount + 1
                DiagnosticLog.event(
                    TAG,
                    "/capability failed for ${phone.baseUrl} (${result.reason}); fail=$newFailCount"
                )
                if (newFailCount >= DiscoveryConstants.MAX_CONSECUTIVE_FAILURES) {
                    Log.i(
                        TAG,
                        "Removing phone ${phone.baseUrl} after $newFailCount failed heartbeats"
                    )
                    return null
                }
                val previousBackoff = pollSchedules[phone.baseUrl]?.backoffMs
                    ?: DiscoveryConstants.POLL_BACKOFF_INITIAL_MS
                val nextBackoff = nextBackoffMs(newFailCount, previousBackoff)
                pollSchedules[phone.baseUrl] = PollSchedule(
                    nextPollAt = now + nextBackoff,
                    backoffMs = nextBackoff,
                )
                phone.copy(failCount = newFailCount)
            }
        }
    }

    /**
     * Fetches `/capability` over HTTP, decodes it with [STRICT_CAPABILITY_JSON],
     * and post-validates the result. Distinguishes recoverable transport
     * failures from a peer returning a structurally invalid payload — see
     * [CapabilityResult] — so callers can apply the right policy.
     */
    private fun fetchCapability(baseUrl: String): CapabilityResult {
        val url = capabilityUrl(baseUrl)
        val response = try {
            httpClient.newCall(Request.Builder().url(url).build()).execute()
        } catch (e: Exception) {
            return CapabilityResult.TransportFailure("HTTP error: ${e.javaClass.simpleName}: ${e.message}")
        }
        return response.use { resp ->
            if (!resp.isSuccessful) {
                return@use CapabilityResult.TransportFailure("HTTP ${resp.code}")
            }
            val body = resp.body.string()
            if (body.isBlank()) {
                return@use CapabilityResult.TransportFailure("empty body")
            }
            val capability = try {
                STRICT_CAPABILITY_JSON.decodeFromString<DeviceCapability>(body)
            } catch (e: SerializationException) {
                return@use CapabilityResult.Invalid("JSON parse failed: ${e.message}")
            } catch (e: IllegalArgumentException) {
                return@use CapabilityResult.Invalid("JSON parse failed: ${e.message}")
            }
            validateCapability(capability)?.let { reason ->
                CapabilityResult.Invalid(reason)
            } ?: CapabilityResult.Ok(capability)
        }
    }

    /**
     * Returns null when the capability is valid; a human-readable reason
     * string otherwise. `llmBackend` ordinal validity is enforced by
     * `kotlinx.serialization` itself when [STRICT_CAPABILITY_JSON] decodes
     * — an unknown enum value throws and surfaces as
     * [CapabilityResult.Invalid] before this function ever runs.
     */
    private fun validateCapability(cap: DeviceCapability): String? = when {
        cap.deviceId.isBlank() -> "blank deviceId"
        cap.userName.isBlank() -> "blank userName"
        cap.deviceName.isBlank() -> "blank deviceName"
        cap.modelQuality < 0 -> "negative modelQuality (${cap.modelQuality})"
        cap.freeRamMb < 0 -> "negative freeRamMb (${cap.freeRamMb})"
        else -> null
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

    /**
     * Seeds the discovered list and schedule map with `nextPollAt = clock()`
     * so the very next [tick] polls every phone — which matches what tests
     * almost always want. Production seeding goes through [addOrUpdatePhone]
     * with the steady-state cadence.
     */
    @VisibleForTesting
    internal fun setDiscoveredPhonesForTest(phones: List<DiscoveredPhone>) {
        _discoveredPhones.value = phones
        pollSchedules.clear()
        val now = clock()
        phones.forEach {
            pollSchedules[it.baseUrl] = PollSchedule(
                nextPollAt = now,
                backoffMs = DiscoveryConstants.POLL_BACKOFF_INITIAL_MS,
            )
        }
    }

    @VisibleForTesting
    internal fun setWifiAvailableForTest(available: Boolean) {
        _isWifiAvailable.value = available
    }

    @VisibleForTesting
    internal suspend fun runTickForTest() {
        tick()
    }

    @VisibleForTesting
    internal fun nextPollAtForTest(baseUrl: String): Long? =
        pollSchedules[baseUrl]?.nextPollAt

    @VisibleForTesting
    internal fun backoffForTest(baseUrl: String): Long? =
        pollSchedules[baseUrl]?.backoffMs

    @VisibleForTesting
    internal fun setNextPollAtForTest(baseUrl: String, nextPollAt: Long) {
        val current = pollSchedules[baseUrl] ?: PollSchedule(
            nextPollAt = nextPollAt,
            backoffMs = DiscoveryConstants.POLL_BACKOFF_INITIAL_MS,
        )
        pollSchedules[baseUrl] = current.copy(nextPollAt = nextPollAt)
    }

    private fun addOrUpdatePhone(phone: DiscoveredPhone) {
        // Dedup by baseUrl. baseUrl embeds host:port, which is the
        // authoritative address of the HTTP server.
        _discoveredPhones.value = (_discoveredPhones.value
            .filter { it.baseUrl != phone.baseUrl } + phone)
            .sortedByDescending { it.score }
        pollSchedules.putIfAbsent(
            phone.baseUrl,
            PollSchedule(
                nextPollAt = clock() + DiscoveryConstants.POLL_BASE_INTERVAL_MS,
                backoffMs = DiscoveryConstants.POLL_BACKOFF_INITIAL_MS,
            )
        )
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
     * via [addOrUpdatePhone].
     *
     * Failure policy:
     * - [CapabilityResult.TransportFailure] → fall back to a TXT-only entry so
     *   a phone whose HTTP server is temporarily unreachable still appears in
     *   the ranked list (the heartbeat will retry).
     * - [CapabilityResult.Invalid] → reject outright; a peer returning a
     *   malformed or structurally invalid payload should not be ranked.
     */
    private fun fetchCapabilityAndAdd(
        serviceInfo: NsdServiceInfo,
        txtRecord: PhoneTxtRecord,
        baseUrl: String,
        rssi: Int,
    ) {
        when (val result = fetchCapability(baseUrl)) {
            is CapabilityResult.Ok -> {
                val score = calculateScore(txtRecord, result.capability)
                addOrUpdatePhone(
                    DiscoveredPhone(serviceInfo, txtRecord, result.capability, score, baseUrl, rssi = rssi)
                )
            }
            is CapabilityResult.Invalid -> {
                DiagnosticLog.warn(
                    TAG,
                    "Rejected BLE-discovered phone $baseUrl: invalid /capability (${result.reason})"
                )
                Log.w(TAG, "Rejected $baseUrl: invalid capability (${result.reason})")
            }
            is CapabilityResult.TransportFailure -> {
                DiagnosticLog.event(
                    TAG,
                    "BLE-discovered phone $baseUrl: /capability ${result.reason}; using BLE-only payload"
                )
                Log.w(TAG, "Phone discovered at $baseUrl but capability fetch failed: ${result.reason}")
                val score = calculateScore(txtRecord, null)
                addOrUpdatePhone(
                    DiscoveredPhone(serviceInfo, txtRecord, null, score, baseUrl, rssi = rssi)
                )
            }
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

    private fun nextBackoffMs(failCount: Int, previousBackoff: Long): Long {
        val candidate = if (failCount <= 1) {
            DiscoveryConstants.POLL_BACKOFF_INITIAL_MS
        } else {
            previousBackoff * 2
        }
        return candidate.coerceAtMost(DiscoveryConstants.POLL_BACKOFF_MAX_MS)
    }
}
