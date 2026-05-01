package com.justb81.watchbuddy.tv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.di.ApplicationScope
import com.justb81.watchbuddy.tv.discovery.TvDiscoveryService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts [TvDiscoveryService] on device boot when the user has opted in via
 * the "Autostart at TV boot" setting. Without this receiver discovery only
 * runs while [com.justb81.watchbuddy.tv.ui.TvMainActivity] is alive.
 *
 * Implemented as a plain [BroadcastReceiver] (not `@AndroidEntryPoint`) —
 * boot broadcasts arrive once per reboot, so the marginal Hilt wiring cost is
 * avoided in favour of an on-demand [EntryPointAccessors] lookup.
 *
 * The DataStore read is performed asynchronously: [goAsync] extends the
 * broadcast lifetime beyond `onReceive`'s return, and [PendingResult.finish]
 * is called once the coroutine completes so the system can reclaim resources.
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun streamingPreferencesRepository(): StreamingPreferencesRepository

        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val entryPoint = EntryPointAccessors
            .fromApplication(context.applicationContext, BootReceiverEntryPoint::class.java)

        handleBootCompleted(
            context = context,
            repo = entryPoint.streamingPreferencesRepository(),
            scope = entryPoint.applicationScope(),
            pendingResult = goAsync(),
        )
    }

    internal fun handleBootCompleted(
        context: Context,
        repo: StreamingPreferencesRepository,
        scope: CoroutineScope,
        pendingResult: PendingResult,
    ) {
        scope.launch {
            try {
                val autostartEnabled = repo.isAutostartEnabled.first()

                if (!autostartEnabled) {
                    DiagnosticLog.event(TAG, "autostart disabled — not starting service")
                    return@launch
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
            } catch (e: Exception) {
                DiagnosticLog.error(TAG, "read autostart preference failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
