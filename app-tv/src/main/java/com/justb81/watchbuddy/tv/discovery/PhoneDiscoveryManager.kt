package com.justb81.watchbuddy.tv.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
    private val httpClient: OkHttpClient
) {
    companion object {
        const val SERVICE_TYPE = "_watchbuddy._tcp."
        const val CAPABILITY_PATH = "/capability"
        private const val TAG = "PhoneDiscoveryManager"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val MAX_FAIL_COUNT = 3
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
    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

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
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(service: NsdServiceInfo) {
            val mgr = nsdManager ?: return
            @Suppress("DEPRECATION")
            mgr.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    fetchCapabilityAndAdd(serviceInfo)
                }
            })
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            _discoveredPhones.value = _discoveredPhones.value
                .filter { it.serviceInfo.serviceName != service.serviceName }
        }
    }

    fun startDiscovery() {
        val mgr = nsdManager ?: return
        runCatching {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }
        startHeartbeat()
    }

    fun stopDiscovery() {
        heartbeatJob?.cancel()
        val mgr = nsdManager ?: return
        runCatching { mgr.stopServiceDiscovery(discoveryListener) }
    }

    private fun startHeartbeat() {
        heartbeatJob = heartbeatScope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                checkAllPhones()
            }
        }
    }

    private fun checkAllPhones() {
        val phones = _discoveredPhones.value
        if (phones.isEmpty()) return

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
                if (newFailCount >= MAX_FAIL_COUNT) {
                    Log.i(TAG, "Removing phone ${phone.baseUrl} after $MAX_FAIL_COUNT failed heartbeats")
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

        val url = "http://${hostAddress}:${serviceInfo.port}${CAPABILITY_PATH}"
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
     * Returns null if any required field is missing or unparseable.
     */
    private fun parseTxtRecord(serviceInfo: NsdServiceInfo): PhoneTxtRecord? {
        return try {
            val attrs = serviceInfo.attributes
            val version = attrs["version"]?.toString(Charsets.UTF_8) ?: return null
            val modelQuality = attrs["modelQuality"]?.toString(Charsets.UTF_8)?.toIntOrNull()
                ?: return null
            val llmBackendStr = attrs["llmBackend"]?.toString(Charsets.UTF_8) ?: return null
            val llmBackend = try {
                LlmBackend.valueOf(llmBackendStr)
            } catch (_: IllegalArgumentException) {
                return null
            }
            PhoneTxtRecord(version = version, modelQuality = modelQuality, llmBackend = llmBackend)
        } catch (_: Exception) {
            null
        }
    }

    private fun addOrUpdatePhone(phone: DiscoveredPhone) {
        _discoveredPhones.value = (_discoveredPhones.value
            .filter { it.serviceInfo.serviceName != phone.serviceInfo.serviceName } + phone)
            .sortedByDescending { it.score }
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
