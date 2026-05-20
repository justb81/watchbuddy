package com.justb81.watchbuddy.tv.discovery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.ui.TvMainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps [PhoneDiscoveryManager] alive in the background so the TV can learn
 * about companion phones even when the launcher activity isn't foregrounded
 * (e.g. right after boot via [com.justb81.watchbuddy.tv.boot.BootReceiver]).
 *
 * Self-stops when the "Phone discovery" preference becomes false — at that
 * point there is no reason to hold a foreground notification slot.
 */
@AndroidEntryPoint
class TvDiscoveryService : Service() {

    @Inject lateinit var phoneDiscovery: PhoneDiscoveryManager
    @Inject lateinit var preferences: StreamingPreferencesRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null
    private var idleTimeoutJob: Job? = null
    private val idleTimeoutMonitor = IdleTimeoutMonitor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        DiagnosticLog.event(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagnosticLog.event(TAG, "onStartCommand")
        observePreferences()
        return START_STICKY
    }

    private fun observePreferences() {
        if (observerJob != null) return
        observerJob = scope.launch {
            preferences.isPhoneDiscoveryEnabled.collect { discovery ->
                if (discovery) {
                    phoneDiscovery.setEnabled(true)
                    startIdleTimeoutMonitor()
                } else {
                    idleTimeoutJob?.cancel()
                    DiagnosticLog.event(TAG, "phone discovery disabled — stopping self")
                    stopSelf()
                }
            }
        }
    }

    /**
     * Launches (or re-launches) the idle-timeout coroutine so the service self-stops
     * when no phones are discovered or all phones become unreachable for too long.
     * Idempotent — cancels any previous job before starting a new one so toggling
     * discovery off and back on resets the clock.
     */
    private fun startIdleTimeoutMonitor() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = scope.launch {
            val reason = idleTimeoutMonitor.awaitTimeout(
                phoneDiscovery.discoveredPhones.map { it.isNotEmpty() },
            )
            val label = when (reason) {
                IdleTimeoutMonitor.Reason.NO_DISCOVERY ->
                    "no phone discovered in ${DiscoveryConstants.NO_DISCOVERY_TIMEOUT_MINUTES} min"
                IdleTimeoutMonitor.Reason.ALL_UNREACHABLE ->
                    "all phones unreachable for ${DiscoveryConstants.ALL_UNREACHABLE_TIMEOUT_MINUTES} min"
            }
            DiagnosticLog.event(TAG, "idle timeout ($label) — stopping self")
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagnosticLog.event(TAG, "service destroyed")
        idleTimeoutJob?.cancel()
        observerJob?.cancel()
        scope.cancel()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tv_discovery_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TvMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tv_discovery_notification_channel_name))
            .setContentText(getString(R.string.tv_discovery_notification_text))
            .setSmallIcon(R.drawable.ic_tv_discovery_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "TvDiscoveryService"
        const val CHANNEL_ID = "watchbuddy_tv_discovery"
        private const val NOTIFICATION_ID = 2001
    }
}
