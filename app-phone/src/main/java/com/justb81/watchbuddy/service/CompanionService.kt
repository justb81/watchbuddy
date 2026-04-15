package com.justb81.watchbuddy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import com.justb81.watchbuddy.phone.server.CompanionHttpServer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CompanionService : Service() {

    companion object {
        private const val TAG = "CompanionService"
        const val CHANNEL_ID = "companion_service"
        private const val NOTIFICATION_ID = 1
        private const val NSD_SERVICE_TYPE = "_watchbuddy._tcp."
        private const val NSD_SERVICE_NAME = "watchbuddy-companion"
        private const val NSD_TXT_VERSION = "1"

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

    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        companionHttpServer.start()
        registerNsd()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNsd()
        companionHttpServer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    private fun registerNsd() {
        val llmConfig = llmOrchestrator.selectConfig()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = NSD_SERVICE_NAME
            serviceType = NSD_SERVICE_TYPE
            port = CompanionHttpServer.PORT
            setAttribute("version", NSD_TXT_VERSION)
            setAttribute("modelQuality", llmConfig.qualityScore.toString())
            setAttribute("llmBackend", llmConfig.backend.name)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: error=$errorCode, service=${info.serviceName}")
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
            nsdManager?.unregisterService(listener)
        }
        nsdRegistrationListener = null
        nsdManager = null
    }
}
