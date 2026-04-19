package com.justb81.watchbuddy.tv.discovery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps [PhoneDiscoveryManager] alive in the background so the TV can learn
 * about companion phones even when the launcher activity isn't foregrounded
 * (e.g. right after boot via [com.justb81.watchbuddy.tv.boot.BootReceiver]).
 *
 * Self-stops when both the "Phone discovery" and "Autostart at TV boot"
 * preferences become false — at that point there is no reason to hold a
 * foreground notification slot.
 */
@AndroidEntryPoint
class TvDiscoveryService : Service() {

    @Inject lateinit var phoneDiscovery: PhoneDiscoveryManager
    @Inject lateinit var preferences: StreamingPreferencesRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        DiagnosticLog.event(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagnosticLog.event(TAG, "onStartCommand")
        // Discovery lifecycle is driven from the preference observer below —
        // don't blindly start here because the user may have toggled discovery
        // off between boot and the service starting up.
        observePreferences()
        return START_STICKY
    }

    private fun observePreferences() {
        if (observerJob != null) return
        observerJob = scope.launch {
            preferences.isPhoneDiscoveryEnabled.collect { discovery ->
                if (discovery) {
                    phoneDiscovery.setEnabled(true)
                } else {
                    DiagnosticLog.event(TAG, "phone discovery disabled — stopping self")
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagnosticLog.event(TAG, "service destroyed")
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
