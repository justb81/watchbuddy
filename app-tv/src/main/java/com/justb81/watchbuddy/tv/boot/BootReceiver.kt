package com.justb81.watchbuddy.tv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.discovery.TvDiscoveryService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var preferencesRepository: StreamingPreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val autostartEnabled = runBlocking { preferencesRepository.isAutostartEnabled.first() }
        if (!autostartEnabled) {
            Log.i(TAG, "autostart disabled; skipping boot-triggered discovery")
            return
        }

        Log.i(TAG, "boot completed; starting TvDiscoveryService")
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TvDiscoveryService::class.java)
            )
        } catch (e: Exception) {
            DiagnosticLog.event(
                "tv.boot.autostart.failed",
                "foreground service start failed: ${e.message}"
            )
            Log.e(TAG, "failed to start TvDiscoveryService on boot", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
