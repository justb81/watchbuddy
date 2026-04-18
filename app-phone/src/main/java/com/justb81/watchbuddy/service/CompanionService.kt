package com.justb81.watchbuddy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import com.justb81.watchbuddy.phone.server.CompanionHttpServer
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CompanionService : Service() {

    companion object {
        private const val TAG = "CompanionService"
        const val CHANNEL_ID = "companion_service"
        private const val NOTIFICATION_ID = 1
        private const val NSD_SERVICE_TYPE = "_watchbuddy._tcp."
        private const val NSD_SERVICE_NAME = "watchbuddy-companion"

        /** How often to check whether the TV is still polling us. */
        private const val PRESENCE_CHECK_INTERVAL_MS = 60_000L
        /** Auto-deactivate if no TV has polled /capability for this long. */
        private const val PRESENCE_TIMEOUT_MS = 5 * 60_000L

        fun start(context: Context) {
            val intent = Intent(context, CompanionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CompanionService::class.java))
        }

        /**
         * Builds the NSD TXT record attributes for the WatchBuddy companion service.
         *
         * `version` carries the phone app's `versionName` (e.g. `0.15.1`), not a
         * protocol version. The HTTP contract is versioned by endpoint.
         */
        fun buildTxtAttributes(
            versionName: String,
            llmConfig: LlmOrchestrator.LlmConfig
        ): Map<String, String> = mapOf(
            "version" to versionName,
            "modelQuality" to llmConfig.qualityScore.toString(),
            "llmBackend" to llmConfig.backend.name
        )
    }

    @Inject lateinit var companionHttpServer: CompanionHttpServer
    @Inject lateinit var llmOrchestrator: LlmOrchestrator
    @Inject lateinit var stateManager: CompanionStateManager
    @Inject lateinit var settingsRepository: SettingsRepository

    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var presenceJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        companionHttpServer.start()
        registerNsd()
        stateManager.setServiceRunning(true)
        registerNetworkCallback()
        startPresenceMonitor()
        return START_STICKY
    }

    override fun onDestroy() {
        presenceJob?.cancel()
        unregisterNetworkCallback()
        unregisterNsd()
        companionHttpServer.stop()
        stateManager.setServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        serviceScope.launch {
            settingsRepository.setCompanionEnabled(false)
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Presence timeout ─────────────────────────────────────────────────────

    private fun startPresenceMonitor() {
        // Reset the timestamp so the first check doesn't immediately time out
        stateManager.onCapabilityChecked()
        presenceJob = serviceScope.launch {
            while (true) {
                delay(PRESENCE_CHECK_INTERVAL_MS)
                val elapsed = System.currentTimeMillis() - stateManager.lastCapabilityCheck.value
                if (elapsed > PRESENCE_TIMEOUT_MS) {
                    Log.i(TAG, "No TV polled /capability for ${elapsed / 1000}s — auto-deactivating")
                    settingsRepository.setCompanionEnabled(false)
                    stopSelf()
                    break
                }
            }
        }
    }

    // ── Network reconnect ────────────────────────────────────────────────────

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                Log.i(TAG, "Wi-Fi lost — unregistering NSD")
                unregisterNsd()
            }

            override fun onAvailable(network: Network) {
                Log.i(TAG, "Wi-Fi available — re-registering NSD")
                registerNsd()
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            runCatching {
                val cm = getSystemService(ConnectivityManager::class.java)
                cm?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.companion_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.companion_channel_description)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.companion_notification_title))
            .setContentText(getString(R.string.companion_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

    // ── NSD ──────────────────────────────────────────────────────────────────

    private fun registerNsd() {
        // Avoid double-registration
        if (nsdRegistrationListener != null) return

        val llmConfig = llmOrchestrator.selectConfig()
        val txtAttributes = buildTxtAttributes(BuildConfig.VERSION_NAME, llmConfig)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = NSD_SERVICE_NAME
            serviceType = NSD_SERVICE_TYPE
            port = CompanionHttpServer.PORT
            txtAttributes.forEach { (key, value) -> setAttribute(key, value) }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service registered: ${info.serviceName} TXT=$txtAttributes")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: error=$errorCode, service=${info.serviceName}")
                nsdRegistrationListener = null
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service unregistered: ${info.serviceName}")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD unregistration failed: error=$errorCode, service=${info.serviceName}")
            }
        }

        nsdRegistrationListener = listener
        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).also {
            it.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun unregisterNsd() {
        nsdRegistrationListener?.let { listener ->
            nsdManager?.let { mgr ->
                runCatching { mgr.unregisterService(listener) }
            }
        }
        nsdRegistrationListener = null
        nsdManager = null
    }
}
