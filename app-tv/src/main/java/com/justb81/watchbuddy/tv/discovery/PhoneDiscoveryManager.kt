package com.justb81.watchbuddy.tv.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.justb81.watchbuddy.core.model.DeviceCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers WatchBuddy companion phones on the local network via NSD (mDNS).
 *
 * Service pattern: trakt-companion-{username}._tcp.local (port 8765)
 *
 * Also ranks phones by capability score for recap generation:
 *   Score = modelQuality (0–150) + speedBonus (0–20) + ramBonus (0–10)
 */
@Singleton
class PhoneDiscoveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
) {
    companion object {
        const val SERVICE_TYPE = "_watchbuddy._tcp."
        const val CAPABILITY_PATH = "/capability"
    }

    private val _discoveredPhones = MutableStateFlow<List<DiscoveredPhone>>(emptyList())
    val discoveredPhones: StateFlow<List<DiscoveredPhone>> = _discoveredPhones

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    data class DiscoveredPhone(
        val serviceInfo: NsdServiceInfo,
        val capability: DeviceCapability?,
        val score: Int,
        val baseUrl: String
    )

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(service: NsdServiceInfo) {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
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
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
    }

    /** Returns the best available phone for recap generation, or null if none available. */
    fun getBestPhone(): DiscoveredPhone? =
        _discoveredPhones.value
            .filter { it.capability?.isAvailable == true }
            .maxByOrNull { it.score }

    private fun fetchCapabilityAndAdd(serviceInfo: NsdServiceInfo) {
        val hostAddress = serviceInfo.host?.hostAddress ?: return
        val url = "http://${hostAddress}:${serviceInfo.port}${CAPABILITY_PATH}"
        try {
            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
            val capability = response.body?.string()?.let {
                Json.decodeFromString<DeviceCapability>(it)
            }
            val score = calculateScore(capability)
            val baseUrl = "http://${hostAddress}:${serviceInfo.port}/"
            val phone = DiscoveredPhone(serviceInfo, capability, score, baseUrl)
            _discoveredPhones.value = (_discoveredPhones.value
                .filter { it.serviceInfo.serviceName != serviceInfo.serviceName } + phone)
                .sortedByDescending { it.score }
        } catch (e: Exception) {
            // Phone unreachable — ignore
        }
    }

    /**
     * Device ranking formula:
     *   Score = modelQuality (0–150) + RAM bonus (0–10) + availability bonus
     */
    private fun calculateScore(cap: DeviceCapability?): Int {
        if (cap == null) return 0
        val ramBonus = when {
            cap.freeRamMb >= 6_000 -> 10
            cap.freeRamMb >= 4_000 -> 6
            cap.freeRamMb >= 3_000 -> 3
            else -> 0
        }
        return cap.modelQuality + ramBonus
    }
}
