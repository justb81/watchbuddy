package com.justb81.watchbuddy.tv.discovery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps phone discovery running after TV boot when autostart is enabled.
 *
 * Lifecycle:
 * - Started by [com.justb81.watchbuddy.tv.boot.BootReceiver] when BOOT_COMPLETED fires and
 *   the autostart preference is true.
 * - Starts [PhoneDiscoveryManager] on [onStartCommand] (idempotent via [PhoneDiscoveryManager.setEnabled]).
 * - Self-stops when both [isPhoneDiscoveryEnabled] and [isAutostartEnabled] become false so the
 *   user can suppress background discovery entirely from the Settings screen.
 * - [TvHomeViewModel] skips [PhoneDiscoveryManager.stopDiscovery] on clear when this service is
 *   running so the discovery lifetime outlives the UI.
 */
@AndroidEntryPoint
class TvDiscoveryService : Service() {

    @Inject
    lateinit var phoneDiscoveryManager: PhoneDiscoveryManager

    @Inject
    lateinit var preferencesRepository: StreamingPreferencesRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "TvDiscoveryService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "watchbuddy_tv_discovery"

        @Volatile
        var isRunning: Boolean = false
            private set

        @androidx.annotation.VisibleForTesting
        fun setRunningForTest(value: Boolean) {
            isRunning = value
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "service created; starting phone discovery")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        phoneDiscoveryManager.setEnabled(true)
        observePreferencesToSelfStop()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "service destroyed")
        // Discovery continues if enabled; TvHomeViewModel takes over when the activity starts.
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observePreferencesToSelfStop() {
        serviceScope.launch {
            combine(
                preferencesRepository.isPhoneDiscoveryEnabled,
                preferencesRepository.isAutostartEnabled,
            ) { discoveryEnabled, autostartEnabled ->
                discoveryEnabled to autostartEnabled
            }.collect { (discoveryEnabled, autostartEnabled) ->
                phoneDiscoveryManager.setEnabled(discoveryEnabled)
                if (!discoveryEnabled && !autostartEnabled) {
                    Log.i(TAG, "both discovery and autostart disabled; stopping service")
                    stopSelf()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tv_discovery_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.tv_discovery_notification_text))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()
}
