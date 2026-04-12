package com.justb81.watchbuddy.phone.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.phone.server.CompanionHttpServer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CompanionService : Service() {

    @Inject lateinit var companionServer: CompanionHttpServer

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.companion_notification_title))
            .setContentText(getString(R.string.companion_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        companionServer.start()
        return START_STICKY
    }

    override fun onDestroy() {
        companionServer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.companion_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.companion_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "companion_service"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, CompanionService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CompanionService::class.java))
        }
    }
}
