package com.justb81.watchbuddy.phone.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive source of truth for "is this phone currently on a Wi-Fi network?".
 *
 * Gates the companion service: without Wi-Fi, NSD registers against no useful
 * interface (`CompanionService.wifiIpv4Address()` returns null) and the TV
 * can never discover the phone, so the toggle must be hard-disabled (#278).
 *
 * The instance is provided as a singleton by [com.justb81.watchbuddy.phone.di.AppModule]
 * which also registers it as a [DefaultLifecycleObserver] on `ProcessLifecycleOwner`
 * so [shutdown] is called automatically when the process ends (#529).
 */
class WifiStateProvider(context: Context) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "WifiStateProvider"
    }

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    private val _isOnWifi = MutableStateFlow(probeCurrent())
    val isOnWifi: StateFlow<Boolean> = _isOnWifi.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
        networkCallback = callback
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { DiagnosticLog.warn(TAG, "registerNetworkCallback failed", it) }
    }

    /**
     * Unregisters the [ConnectivityManager.NetworkCallback] registered in [registerCallback].
     *
     * Idempotent: safe to call more than once. Called automatically from [onDestroy] when
     * the process lifecycle ends, and may also be called explicitly in tests or on Hilt
     * component teardown.
     */
    fun shutdown() {
        networkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
                .onFailure { DiagnosticLog.warn(TAG, "unregisterNetworkCallback failed", it) }
        }
        networkCallback = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        shutdown()
    }
}
