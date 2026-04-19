package com.justb81.watchbuddy.phone.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive source of truth for "is this phone currently on a Wi-Fi network?".
 *
 * Gates the companion service: without Wi-Fi, NSD registers against no useful
 * interface (`CompanionService.wifiIpv4Address()` returns null) and the TV
 * can never discover the phone, so the toggle must be hard-disabled (#278).
 */
@Singleton
class WifiStateProvider @Inject constructor(
    @ApplicationContext context: Context
) {

    companion object {
        private const val TAG = "WifiStateProvider"
    }

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    private val _isOnWifi = MutableStateFlow(probeCurrent())
    val isOnWifi: StateFlow<Boolean> = _isOnWifi.asStateFlow()

    init {
        registerCallback()
    }

    private fun probeCurrent(): Boolean {
        val cm = connectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun registerCallback() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                DiagnosticLog.event(TAG, "Wi-Fi onAvailable")
                _isOnWifi.value = true
            }

            override fun onLost(network: Network) {
                DiagnosticLog.event(TAG, "Wi-Fi onLost")
                _isOnWifi.value = probeCurrent()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _isOnWifi.value = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    probeCurrent()
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { DiagnosticLog.warn(TAG, "registerNetworkCallback failed", it) }
    }
}
