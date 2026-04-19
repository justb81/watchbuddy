package com.justb81.watchbuddy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.R
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
        private const val NSD_SERVICE_TYPE = "_watchbuddy._tcp."
        private const val NSD_SERVICE_NAME = "watchbuddy-companion"

        /** How often to check whether the TV is still polling us. */
        private const val PRESENCE_CHECK_INTERVAL_MS = 60_000L
        /** Auto-deactivate if no TV has polled /capability for this long. */
        private const val PRESENCE_TIMEOUT_MS = 5 * 60_000L

        /**
         * Debounce interval for `NetworkCallback.onAvailable`. The callback can
         * fire repeatedly during Wi-Fi transitions (captive-portal exit, SSID
         * change); without this guard each fire would trigger an unregister +
         * register cycle and leak a ghost NSD entry (#264).
         */
        private const val WIFI_AVAILABLE_DEBOUNCE_MS = 2_000L

        /**
         * Delay between `unregisterService` and the follow-up `registerService`
         * on Wi-Fi reconnect. `NsdManager.unregisterService` is async; calling
         * `registerService` before it completes leaves a duplicate advertisement
         * on the network (#264).
         */
        private const val NSD_REREGISTER_DELAY_MS = 300L

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
    @Inject lateinit var bleAdvertiser: CompanionBleAdvertiser

    private enum class NsdState { IDLE, REGISTERING, REGISTERED, UNREGISTERING }

    private val nsdLock = Any()
    @Volatile private var nsdState: NsdState = NsdState.IDLE
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var presenceJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Wi-Fi gate: without TRANSPORT_WIFI, wifiIpv4Address() returns null,
        // NSD advertises on no useful interface, and the TV can never discover
        // us — refuse to start and clear the persisted toggle (#278).
        if (wifiIpv4Address() == null) {
            Log.w(TAG, "onStartCommand refused; phone is not on Wi-Fi")
            serviceScope.launch { settingsRepository.setCompanionEnabled(false) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Idempotent: onStartCommand can fire multiple times (ViewModel re-starts,
        // system re-delivery of START_STICKY) and each repeat would otherwise
        // race the NSD registration.
        if (stateManager.isServiceRunning.value) {
            Log.d(TAG, "onStartCommand skipped; service already running")
            return START_STICKY
        }
        acquireMulticastLock()
        companionHttpServer.start()
        stateManager.setHttpServerBinding("0.0.0.0:${CompanionHttpServer.PORT}")
        stateManager.setWifiIpv4(wifiIpv4Address()?.hostAddress)
        registerNsd()
        startBleAdvertising()
        stateManager.setServiceRunning(true)
        registerNetworkCallback()
        startPresenceMonitor()
        return START_STICKY
    }

    override fun onDestroy() {
        presenceJob?.cancel()
        unregisterNetworkCallback()
        unregisterNsd()
        bleAdvertiser.stop()
        releaseMulticastLock()
        companionHttpServer.stop()
        stateManager.setHttpServerBinding(null)
        stateManager.setWifiIpv4(null)
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
            private var lastAvailable = 0L

            override fun onLost(network: Network) {
                Log.i(TAG, "Wi-Fi lost — unregistering NSD, awaiting grace period")
                unregisterNsd()
                bleAdvertiser.stop()
                // Grace period for SSID handoffs: if a fresh Wi-Fi network
                // arrives before the timer expires, `onAvailable` restarts
                // NSD and we stay alive. If not, the phone is truly off Wi-Fi
                // and we self-stop so the FG notification doesn't linger on
                // a dead state (#278).
                serviceScope.launch {
                    delay(WIFI_LOSS_GRACE_MS)
                    if (wifiIpv4Address() == null) {
                        Log.i(TAG, "Still no Wi-Fi after grace — self-stopping")
                        settingsRepository.setCompanionEnabled(false)
                        stopSelf()
                    }
                }
            }

            override fun onAvailable(network: Network) {
                val now = System.currentTimeMillis()
                if (now - lastAvailable < WIFI_AVAILABLE_DEBOUNCE_MS) {
                    Log.d(TAG, "Wi-Fi onAvailable debounced; last fire ${now - lastAvailable}ms ago")
                    return
                }
                lastAvailable = now
                Log.i(TAG, "Wi-Fi available — restarting NSD")
                // Force a clean slate: unregister first, let NsdManager complete
                // its teardown, then register the new advertisement. Calling
                // registerService before unregisterService finishes is what
                // leaves ghost entries on the network (#264).
                unregisterNsd()
                bleAdvertiser.stop()
                serviceScope.launch {
                    delay(NSD_REREGISTER_DELAY_MS)
                    registerNsd()
                    startBleAdvertising()
                }
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

    // ── Multicast lock ───────────────────────────────────────────────────────

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        // Many phone OEMs (OxygenOS, OneUI, MIUI) filter outgoing multicast
        // packets for battery reasons when no app is holding a MulticastLock.
        // Without this lock the phone's NSD registration succeeds locally but
        // no mDNS traffic leaves the radio, so peers can't discover us (#265).
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        multicastLock = wifi.createMulticastLock("watchbuddy-nsd").apply {
            setReferenceCounted(false)
            acquire()
        }
        stateManager.setMulticastLockHeld(true)
        Log.i(TAG, "Multicast lock acquired")
    }

    private fun releaseMulticastLock() {
        multicastLock?.takeIf { it.isHeld }?.let {
            runCatching { it.release() }
        }
        multicastLock = null
        stateManager.setMulticastLockHeld(false)
    }

    // ── NSD ──────────────────────────────────────────────────────────────────

    private fun registerNsd() {
        // Atomic guard: transition IDLE → REGISTERING before any I/O so
        // concurrent callers (onStartCommand, Wi-Fi onAvailable) can't both
        // pass the check and double-register (#264).
        synchronized(nsdLock) {
            if (nsdState != NsdState.IDLE) {
                Log.d(TAG, "registerNsd skipped; state=$nsdState")
                return
            }
            nsdState = NsdState.REGISTERING
        }
        stateManager.setNsdRegistrationState(CompanionStateManager.NsdRegistrationState.REGISTERING)

        val llmConfig = llmOrchestrator.selectConfig()
        val txtAttributes = buildTxtAttributes(BuildConfig.VERSION_NAME, llmConfig)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = NSD_SERVICE_NAME
            serviceType = NSD_SERVICE_TYPE
            port = CompanionHttpServer.PORT
            // Pin the advertised host to the Wi-Fi IPv4 address to avoid
            // NsdManager picking a wrong interface on devices with multiple
            // active networks (Wi-Fi + cellular, Wi-Fi + Ethernet) — a common
            // cause of "visible on the phone, invisible to peers" (#265).
            wifiIpv4Address()?.let { host = it }
            txtAttributes.forEach { (key, value) -> setAttribute(key, value) }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service registered: ${info.serviceName} TXT=$txtAttributes")
                synchronized(nsdLock) { nsdState = NsdState.REGISTERED }
                stateManager.setNsdRegistrationState(CompanionStateManager.NsdRegistrationState.REGISTERED)
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: error=$errorCode, service=${info.serviceName}")
                synchronized(nsdLock) {
                    nsdRegistrationListener = null
                    nsdManager = null
                    nsdState = NsdState.IDLE
                }
                stateManager.setNsdRegistrationState(
                    CompanionStateManager.NsdRegistrationState.FAILED,
                    errorCode = errorCode,
                )
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service unregistered: ${info.serviceName}")
                synchronized(nsdLock) {
                    nsdRegistrationListener = null
                    nsdManager = null
                    nsdState = NsdState.IDLE
                }
                stateManager.setNsdRegistrationState(CompanionStateManager.NsdRegistrationState.IDLE)
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD unregistration failed: error=$errorCode, service=${info.serviceName}")
                synchronized(nsdLock) {
                    nsdRegistrationListener = null
                    nsdManager = null
                    nsdState = NsdState.IDLE
                }
                stateManager.setNsdRegistrationState(
                    CompanionStateManager.NsdRegistrationState.FAILED,
                    errorCode = errorCode,
                )
            }
        }

        synchronized(nsdLock) {
            nsdRegistrationListener = listener
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        }
        runCatching {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.e(TAG, "registerService threw", it)
            synchronized(nsdLock) {
                nsdRegistrationListener = null
                nsdManager = null
                nsdState = NsdState.IDLE
            }
            stateManager.setNsdRegistrationState(CompanionStateManager.NsdRegistrationState.FAILED)
        }
    }

    private fun unregisterNsd() {
        val (mgr, listener) = synchronized(nsdLock) {
            if (nsdState != NsdState.REGISTERED && nsdState != NsdState.REGISTERING) {
                return
            }
            nsdState = NsdState.UNREGISTERING
            nsdManager to nsdRegistrationListener
        }
        stateManager.setNsdRegistrationState(CompanionStateManager.NsdRegistrationState.UNREGISTERING)
        if (mgr != null && listener != null) {
            runCatching { mgr.unregisterService(listener) }
                .onFailure {
                    Log.e(TAG, "unregisterService threw", it)
                    synchronized(nsdLock) {
                        nsdRegistrationListener = null
                        nsdManager = null
                        nsdState = NsdState.IDLE
                    }
                    stateManager.setNsdRegistrationState(CompanionStateManager.NsdRegistrationState.IDLE)
                }
        } else {
            synchronized(nsdLock) {
                nsdRegistrationListener = null
                nsdManager = null
                nsdState = NsdState.IDLE
            }
            stateManager.setNsdRegistrationState(CompanionStateManager.NsdRegistrationState.IDLE)
        }
    }

    // ── BLE advertising (mDNS fallback channel) ──────────────────────────────

    /**
     * Starts BLE advertising of our LAN endpoint for TVs that can't receive
     * our NSD packets (AP/client isolation, VLAN-segmented mesh Wi-Fi,
     * aggressive multicast filtering). This is additive to NSD — if BLE is
     * off, permission-denied, or unsupported, the advertiser no-ops and we
     * continue with NSD only.
     */
    private fun startBleAdvertising() {
        val ipv4 = wifiIpv4Address() as? Inet4Address ?: run {
            Log.d(TAG, "startBleAdvertising skipped: no IPv4 address")
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
     * active network. Used to pin the NSD advertisement to the correct
     * interface on multi-homed devices.
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
