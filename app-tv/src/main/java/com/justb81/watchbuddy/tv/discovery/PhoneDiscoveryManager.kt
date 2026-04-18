package com.justb81.watchbuddy.tv.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
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
 * Discovers WatchBuddy companion phones on the local network via NSD (mDNS).
 *
 * Service pattern: watchbuddy-companion._watchbuddy._tcp.local (port 8765)
 *
 * Discovery flow:
 *   1. NSD resolves a service → TXT records are parsed immediately for fast scoring.
 *   2. /capability is fetched for full data (tmdbApiKey, freeRamMb, userAvatarUrl).
 *   3. If /capability fails, the phone is still added using TXT record data alone.
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
        const val SERVICE_TYPE = "_watchbuddy._tcp."
        const val CAPABILITY_PATH = "/capability"
        private const val TAG = "PhoneDiscoveryManager"
        /** Back-off before retrying a stop+start cycle after FAILURE_ALREADY_ACTIVE. */
        private const val RESTART_BACKOFF_MS = 500L
    }

    private val _discoveredPhones = MutableStateFlow<List<DiscoveredPhone>>(emptyList())
    val discoveredPhones: StateFlow<List<DiscoveredPhone>> = _discoveredPhones

    // Nullable + runCatching so that a missing NSD system service on unusual
    // TV ROMs cannot throw during Hilt singleton construction, which would
    // otherwise blow up the first hiltViewModel() call and prevent the app
    // from ever drawing a frame.
    private val nsdManager: NsdManager? = runCatching {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }.getOrNull()
    private val wifiManager: WifiManager? = runCatching {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }.getOrNull()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var isDiscovering: Boolean = false

    /**
     * Lightweight device info extracted from NSD TXT records.
     * Available immediately after service resolution without any HTTP round-trip.
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
        val lastSuccessfulCheck: Long = System.currentTimeMillis()
    )

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.i(TAG, "discovery started: $serviceType")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "start discovery failed: $serviceType, error=${nsdErrorName(errorCode)}")
            // A prior listener (possibly leaked across a process restart) is
            // still registered in the system NSD service. Schedule a single
            // stop+start cycle so we self-heal without an app relaunch.
            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && isDiscovering) {
                heartbeatScope.launch {
                    delay(RESTART_BACKOFF_MS)
                    Log.i(
                        TAG,
                        "self-heal: retrying discovery after FAILURE_ALREADY_ACTIVE " +
                            "(stale listener registration in system NSD service)"
                    )
                    restartDiscoveryInternal()
                }
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "stop discovery failed: $serviceType, error=${nsdErrorName(errorCode)}")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.i(TAG, "service found: ${service.serviceName} type=${service.serviceType}")
            val mgr = nsdManager ?: return
            @Suppress("DEPRECATION")
            mgr.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(
                        TAG,
                        "resolve failed: ${serviceInfo.serviceName}, error=${nsdErrorName(errorCode)}"
                    )
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = serviceInfo.host?.hostAddress
                    val attrs = serviceInfo.attributes
                        ?.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "<null>" }
                        ?: emptyMap()
                    Log.i(
                        TAG,
                        "service resolved: name=${serviceInfo.serviceName} " +
                            "host=$host port=${serviceInfo.port} TXT=$attrs"
                    )
                    fetchCapabilityAndAdd(serviceInfo)
                }
            })
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.i(TAG, "service lost: ${service.serviceName}")
            _discoveredPhones.value = _discoveredPhones.value
                .filter { it.serviceInfo.serviceName != service.serviceName }
        }
    }

    fun startDiscovery() {
        Log.i(TAG, "startDiscovery: type=$SERVICE_TYPE")
        isDiscovering = true
        acquireMulticastLock()
        val mgr = nsdManager
        if (mgr != null) {
            runCatching {
                mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            }.onFailure { Log.e(TAG, "discoverServices failed", it) }
        } else {
            Log.w(TAG, "NSD unavailable on this device; relying on BLE channel only")
        }
        startBleScan()
        startHeartbeat()
        registerNetworkCallback()
    }

    fun stopDiscovery() {
        Log.i(TAG, "stopDiscovery")
        isDiscovering = false
        heartbeatJob?.cancel()
        unregisterNetworkCallback()
        val mgr = nsdManager
        if (mgr != null) {
            runCatching { mgr.stopServiceDiscovery(discoveryListener) }
        }
        bleScanner.stop()
        releaseMulticastLock()
    }

    /**
     * Tear down and restart NSD discovery. Safe to call while discovery is
     * already running: the underlying stop/start is wrapped in runCatching so
     * a stale listener registration cannot abort the cycle. Intended for
     * external callers (UI retry, post-reconnect recovery).
     */
    fun restartDiscovery() {
        if (!isDiscovering) {
            Log.i(TAG, "restartDiscovery skipped: discovery not active")
            return
        }
        heartbeatScope.launch { restartDiscoveryInternal() }
    }

    private suspend fun restartDiscoveryInternal() {
        val mgr = nsdManager ?: return
        runCatching { mgr.stopServiceDiscovery(discoveryListener) }
        delay(RESTART_BACKOFF_MS)
        runCatching {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure { Log.e(TAG, "re-discoverServices failed", it) }
    }

    // Mirrors the phone's CompanionService network callback: if the TV's Wi-Fi
    // flickers, our NSD listener can silently go dead on the new network.
    // Cycle discovery when Wi-Fi becomes available again.
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
                Log.i(TAG, "Wi-Fi available — cycling discovery (NSD + BLE)")
                if (isDiscovering) {
                    heartbeatScope.launch { restartDiscoveryInternal() }
                    // Wi-Fi reconnects often coincide with the phone's IP
                    // flipping; restart the BLE scanner so a fresh advert
                    // (with the new IP) is captured promptly rather than
                    // waiting for the next LOW_POWER scan window.
                    startBleScan()
                }
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

    // Many Android TV ROMs (Google TV, Chromecast with Google TV, Shield, several
    // Sony/TCL images) drop inbound multicast packets at the Wi-Fi driver unless
    // an app holds a multicast lock, so NsdManager runs but never receives the
    // phone's mDNS announcements. Hold the lock only while discovery is active.
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) {
            Log.i(TAG, "multicast lock already held; skipping acquire")
            return
        }
        val wifi = wifiManager ?: run {
            Log.w(TAG, "WifiManager unavailable; skipping multicast lock")
            return
        }
        runCatching {
            val lock = wifi.createMulticastLock("watchbuddy-nsd").apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
            Log.i(TAG, "multicast lock acquired (held=${lock.isHeld})")
        }.onFailure { Log.e(TAG, "multicast lock acquire failed", it) }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: run {
            Log.i(TAG, "multicast lock release skipped: no lock held")
            return
        }
        runCatching {
            if (lock.isHeld) {
                lock.release()
                Log.i(TAG, "multicast lock released")
            } else {
                Log.i(TAG, "multicast lock already released")
            }
        }.onFailure { Log.w(TAG, "multicast lock release failed", it) }
        multicastLock = null
    }

    private fun nsdErrorName(errorCode: Int): String = when (errorCode) {
        NsdManager.FAILURE_INTERNAL_ERROR -> "FAILURE_INTERNAL_ERROR($errorCode)"
        NsdManager.FAILURE_ALREADY_ACTIVE -> "FAILURE_ALREADY_ACTIVE($errorCode)"
        NsdManager.FAILURE_MAX_LIMIT -> "FAILURE_MAX_LIMIT($errorCode)"
        else -> "UNKNOWN($errorCode)"
    }

    private fun startHeartbeat() {
        heartbeatJob = heartbeatScope.launch {
            while (true) {
                delay(DiscoveryConstants.HEARTBEAT_INTERVAL_MS)
                checkAllPhones()
            }
        }
    }

    private suspend fun checkAllPhones() {
        val phones = _discoveredPhones.value
        if (phones.isEmpty()) {
            // If we have no phones after a full heartbeat interval, the system
            // NSD service may have silently dropped our listener (seen on some
            // Google TV ROMs). Cycle discovery so we recover without relaunch.
            if (isDiscovering) {
                Log.i(
                    TAG,
                    "heartbeat self-heal: no phones discovered → restarting NSD discovery " +
                        "(multicastLockHeld=${multicastLock?.isHeld == true})"
                )
                restartDiscoveryInternal()
            } else {
                Log.d(TAG, "heartbeat tick: no phones and discovery inactive; nothing to do")
            }
            return
        }

        val updated = phones.mapNotNull { phone ->
            val url = "${phone.baseUrl}${CAPABILITY_PATH}".replace("//capability", "/capability")
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

    private fun fetchCapabilityAndAdd(serviceInfo: NsdServiceInfo) {
        @Suppress("DEPRECATION")
        val hostAddress = serviceInfo.host?.hostAddress ?: return
        val baseUrl = "http://${hostAddress}:${serviceInfo.port}/"
        val txtRecord = parseTxtRecord(serviceInfo)
        fetchCapabilityAndAdd(serviceInfo, txtRecord, baseUrl)
    }

    /**
     * Shared implementation for both NSD and BLE discovery channels. Callers
     * are responsible for constructing a [NsdServiceInfo] (real or synthetic
     * for BLE) and a [PhoneTxtRecord] (parsed from NSD TXT attributes or
     * reconstructed from BLE payload); this method fetches `/capability`,
     * ranks the phone, and adds it to the shared list via [addOrUpdatePhone].
     */
    private fun fetchCapabilityAndAdd(
        serviceInfo: NsdServiceInfo,
        txtRecord: PhoneTxtRecord?,
        baseUrl: String,
    ) {
        val url = "${baseUrl.trimEnd('/')}${CAPABILITY_PATH}"
        try {
            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
            val capability = response.body?.string()?.let {
                Json.decodeFromString<DeviceCapability>(it)
            }
            val score = calculateScore(txtRecord, capability)
            addOrUpdatePhone(DiscoveredPhone(serviceInfo, txtRecord, capability, score, baseUrl))
        } catch (e: Exception) {
            Log.w(TAG, "Phone discovered at $url but capability fetch failed: ${e.message}")
            if (txtRecord != null) {
                // Still add the phone using TXT record data so it appears in the ranked list
                val score = calculateScore(txtRecord, null)
                addOrUpdatePhone(DiscoveredPhone(serviceInfo, txtRecord, null, score, baseUrl))
            }
        }
    }

    /**
     * Parses WatchBuddy TXT records from a resolved NsdServiceInfo.
     *
     * Returns null if `version` or `modelQuality` is missing / unparseable.
     * `llmBackend` parsing is lenient: unknown values fall back to
     * [LlmBackend.NONE] so a new phone-side enum value does not make the
     * phone silently invisible to older TV builds.
     *
     * Every failure path logs a structured reason — this code path is the
     * primary suspect when a phone is emitting mDNS but the TV does not list
     * it, and silent returns were observed to mask the root cause in the
     * field (see #259).
     */
    private fun parseTxtRecord(serviceInfo: NsdServiceInfo): PhoneTxtRecord? {
        return try {
            val attrs = serviceInfo.attributes ?: emptyMap()
            val decoded = attrs.mapValues { (_, v) ->
                v?.toString(Charsets.UTF_8) ?: "<null>"
            }
            Log.d(TAG, "parseTxtRecord: attrs=$decoded")

            val version = attrs["version"]?.toString(Charsets.UTF_8)
            if (version == null) {
                Log.w(TAG, "parseTxtRecord: missing 'version' attribute; attrs=$decoded")
                return null
            }
            val modelQualityRaw = attrs["modelQuality"]?.toString(Charsets.UTF_8)
            if (modelQualityRaw == null) {
                Log.w(TAG, "parseTxtRecord: missing 'modelQuality' attribute; attrs=$decoded")
                return null
            }
            val modelQuality = modelQualityRaw.toIntOrNull()
            if (modelQuality == null) {
                Log.w(
                    TAG,
                    "parseTxtRecord: unparseable 'modelQuality'='$modelQualityRaw'; attrs=$decoded"
                )
                return null
            }
            val llmBackendStr = attrs["llmBackend"]?.toString(Charsets.UTF_8)
            if (llmBackendStr == null) {
                Log.w(TAG, "parseTxtRecord: missing 'llmBackend' attribute; attrs=$decoded")
                return null
            }
            val llmBackend = runCatching { LlmBackend.valueOf(llmBackendStr) }
                .getOrElse {
                    Log.w(
                        TAG,
                        "parseTxtRecord: unknown llmBackend='$llmBackendStr' → falling back to NONE"
                    )
                    LlmBackend.NONE
                }
            PhoneTxtRecord(version = version, modelQuality = modelQuality, llmBackend = llmBackend)
        } catch (e: Exception) {
            Log.w(TAG, "parseTxtRecord: unexpected error", e)
            null
        }
    }

    private fun addOrUpdatePhone(phone: DiscoveredPhone) {
        // Dedup by baseUrl so NSD and BLE — the two independent discovery
        // channels — converge into a single entry when they surface the same
        // phone. baseUrl embeds host:port, which is the authoritative address
        // of the HTTP server; the NSD serviceName is per-channel and would
        // allow duplicates.
        _discoveredPhones.value = (_discoveredPhones.value
            .filter { it.baseUrl != phone.baseUrl } + phone)
            .sortedByDescending { it.score }
    }

    /**
     * Entry point for advertisements surfaced by [PhoneBleScanner]. Dedups
     * against phones already discovered via NSD (same host:port) and,
     * otherwise, feeds the endpoint into the existing capability-fetch
     * pipeline so heartbeating and ranking are identical across channels.
     *
     * BLE adverts fire every ~250 ms, so this method must not kick off an
     * HTTP request on every tick — we short-circuit when the same baseUrl is
     * already in the list.
     */
    internal fun onBleAdvertisement(
        ipv4: Inet4Address,
        port: Int,
        modelQuality: Int,
        llmBackendOrdinal: Int,
    ) {
        val hostAddress = ipv4.hostAddress ?: return
        val baseUrl = "http://$hostAddress:$port/"
        if (_discoveredPhones.value.any { it.baseUrl == baseUrl }) {
            // Already known via NSD or a prior BLE tick — heartbeat handles
            // aliveness from here.
            return
        }

        val synthInfo = NsdServiceInfo().apply {
            serviceName = "watchbuddy-ble-$hostAddress-$port"
            serviceType = SERVICE_TYPE
            this.port = port
            host = ipv4
        }
        val txtRecord = PhoneTxtRecord(
            version = "", // unknown until /capability is fetched
            modelQuality = modelQuality,
            llmBackend = runCatching { LlmBackend.entries[llmBackendOrdinal] }
                .getOrDefault(LlmBackend.NONE),
        )
        Log.i(TAG, "BLE advertisement → resolving phone at $baseUrl")
        heartbeatScope.launch {
            fetchCapabilityAndAdd(synthInfo, txtRecord, baseUrl)
        }
    }

    private fun startBleScan() {
        bleScanner.start { ipv4, port, quality, backend ->
            onBleAdvertisement(ipv4, port, quality, backend)
        }
    }

    /**
     * Device ranking formula:
     *   Score = modelQuality (0–150) + RAM bonus (0–10, only when capability is available)
     *
     * When only TXT records are available (capability fetch failed), modelQuality from
     * TXT records is used directly with no RAM bonus.
     */
    private fun calculateScore(txt: PhoneTxtRecord?, cap: DeviceCapability?): Int {
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
