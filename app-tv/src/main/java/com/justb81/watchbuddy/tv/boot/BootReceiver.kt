package com.justb81.watchbuddy.tv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.discovery.TvDiscoveryService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Starts [TvDiscoveryService] on device boot when the user has opted in via
 * the "Autostart at TV boot" setting. Without this receiver discovery only
 * runs while [com.justb81.watchbuddy.tv.ui.TvMainActivity] is alive.
 *
 * Implemented as a plain [BroadcastReceiver] (not `@AndroidEntryPoint`) —
 * boot broadcasts arrive once per reboot, so the marginal Hilt wiring cost is
 * avoided in favour of an on-demand [EntryPointAccessors] lookup. The DataStore
 * read is a synchronous blocking call, which is fine inside `onReceive` (the
 * system allows up to 10 s and the read is a single disk fetch).
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun streamingPreferencesRepository(): StreamingPreferencesRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val repo = EntryPointAccessors
            .fromApplication(context.applicationContext, BootReceiverEntryPoint::class.java)
            .streamingPreferencesRepository()

        val autostartEnabled = runCatching {
            runBlocking { repo.isAutostartEnabled.first() }
        }.getOrElse { e ->
            DiagnosticLog.error(TAG, "read autostart preference failed", e)
            return
        }

        if (!autostartEnabled) {
            DiagnosticLog.event(TAG, "autostart disabled — not starting service")
            return
        }

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TvDiscoveryService::class.java),
            )
        }.onSuccess {
            DiagnosticLog.event(TAG, "TvDiscoveryService start requested")
        }.onFailure { e ->
            DiagnosticLog.error(TAG, "tv.boot.autostart.failed: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
