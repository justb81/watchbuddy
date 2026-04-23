package com.justb81.watchbuddy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import com.justb81.watchbuddy.phone.server.CompanionHttpServer
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.phone.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import javax.inject.Inject

@AndroidEntryPoint
class CompanionService : Service() {

    companion object {
        private const val TAG = "CompanionService"
        const val CHANNEL_ID = "companion_service"
        private const val NOTIFICATION_ID = 1

        /** How often to check whether the TV is still polling us. */
        private const val PRESENCE_CHECK_INTERVAL_MS = 60_000L
        /** Auto-deactivate if no TV has polled /capability for this long. */
        private const val PRESENCE_TIMEOUT_MS = 5 * 60_000L

        /**
         * Grace period after a Wi-Fi network is lost before the service
         * self-stops. Covers SSID handoffs where `onLost(oldNet)` fires a beat
         * before `onAvailable(newNet)`. If Wi-Fi is genuinely gone after this
         * delay we stop; otherwise the service keeps running (#278).
         */
        private const val WIFI_LOSS_GRACE_MS = 3_000L

        fun start(context: Context) {
            val intent = Intent(context, CompanionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CompanionService::class.java))
        }
    }

    @Inject lateinit var companionHttpServer: CompanionHttpServer
    @Inject lateinit var llmOrchestrator: LlmOrchestrator
    @Inject lateinit var stateManager: CompanionStateManager
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var bleAdvertiser: CompanionBleAdvertiser

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var presenceJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.event(TAG, "onCreate")
        ensureNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ipv4 = wifiIpv4Address()
        DiagnosticLog.event(TAG, "onStartCommand ipv4=${ipv4?.hostAddress ?: "null"} running=${stateManager.isServiceRunning.value}")
        // Wi-Fi gate: the BLE advert carries the phone's LAN endpoint, so
        // without Wi-Fi the TV has nothing to connect to even if it receives
        // our advertisement (#278).
        if (ipv4 == null) {
            DiagnosticLog.warn(TAG, "onStartCommand refused; phone is not on Wi-Fi")
            serviceScope.launch { settingsRepository.setCompanionEnabled(false) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Idempotent: onStartCommand can fire multiple times (ViewModel re-starts,
        // system re-delivery of START_STICKY).
        if (stateManager.isServiceRunning.value) {
            DiagnosticLog.debug(TAG, "onStartCommand skipped; service already running")
            return START_STICKY
        }
        companionHttpServer.start()
        stateManager.setHttpServerBinding("0.0.0.0:${CompanionHttpServer.PORT}")
        stateManager.setWifiIpv4(ipv4.hostAddress)
        DiagnosticLog.event(TAG, "HTTP server bound 0.0.0.0:${CompanionHttpServer.PORT}")
        startBleAdvertising()
        stateManager.setServiceRunning(true)
        registerNetworkCallback()
        startPresenceMonitor()
        return START_STICKY
    }

    override fun onDestroy() {
        DiagnosticLog.event(TAG, "onDestroy")
        presenceJob?.cancel()
        unregisterNetworkCallback()
        bleAdvertiser.stop()
        companionHttpServer.stop()
        stateManager.setHttpServerBinding(null)
        stateManager.setWifiIpv4(null)
        stateManager.setServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticLog.event(TAG, "onTaskRemoved; user swiped app from recents")
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
                    DiagnosticLog.event(TAG, "presence timeout=${elapsed / 1000}s — auto-deactivating")
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
                DiagnosticLog.event(TAG, "Wi-Fi onLost — stopping BLE, awaiting grace period")
                bleAdvertiser.stop()
                // Grace period for SSID handoffs: if a fresh Wi-Fi network
                // arrives before the timer expires, `onAvailable` restarts
                // BLE and we stay alive. If not, the phone is truly off Wi-Fi
                // and we self-stop so the FG notification doesn't linger on
                // a dead state (#278).
                serviceScope.launch {
                    delay(WIFI_LOSS_GRACE_MS)
                    if (wifiIpv4Address() == null) {
                        DiagnosticLog.event(TAG, "Wi-Fi still lost after grace — self-stopping")
                        settingsRepository.setCompanionEnabled(false)
                        stopSelf()
                    }
                }
            }

            override fun onAvailable(network: Network) {
                DiagnosticLog.event(TAG, "Wi-Fi onAvailable — restarting BLE advertiser")
                startBleAdvertising()
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

    private fun buildNotification(): Notification {
        // Tapping the notification brings MainActivity back to the front so the
        // user can toggle the "I am watching TV" switch off without fishing the
        // app out of recents.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.companion_notification_title))
            .setContentText(getString(R.string.companion_notification_text))
            // Must be a white-on-transparent vector — adaptive mipmaps are
            // rejected or rendered blank by some OEM skins (#261).
            .setSmallIcon(R.drawable.ic_companion_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ── BLE advertising (sole discovery channel) ─────────────────────────────

    private fun startBleAdvertising() {
        val ipv4 = wifiIpv4Address() as? Inet4Address ?: run {
            DiagnosticLog.debug(TAG, "startBleAdvertising skipped: no IPv4 address")
            return
        }
        val llmConfig = llmOrchestrator.selectConfig()
        bleAdvertiser.start(
            ipv4 = ipv4,
            port = CompanionHttpServer.PORT,
            modelQuality = llmConfig.qualityScore,
            llmBackendOrdinal = llmConfig.backend.ordinal,
        )
    }

    /**
     * Returns the phone's Wi-Fi IPv4 address, or null if Wi-Fi is not the
     * active network. The BLE advert carries this address so the TV can reach
     * the HTTP server directly.
     */
    private fun wifiIpv4Address(): InetAddress? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val lp = cm.getLinkProperties(net) ?: return null
        return lp.linkAddresses
            .asSequence()
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress }
    }
}
