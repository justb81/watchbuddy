package com.justb81.watchbuddy.tv.discovery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.data.TvShowCache
import com.justb81.watchbuddy.tv.scrobbler.TvScrobbleDispatcher
import com.justb81.watchbuddy.tv.scrobbler.WatchBuddyNotificationListener
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
 * Self-stops when both the "Phone discovery" and "Autostart at TV boot"
 * preferences become false — at that point there is no reason to hold a
 * foreground notification slot.
 */
@AndroidEntryPoint
class TvDiscoveryService : Service() {

    @Inject lateinit var phoneDiscovery: PhoneDiscoveryManager
    @Inject lateinit var preferences: StreamingPreferencesRepository
    @Inject lateinit var scrobbler: MediaSessionScrobbler

    @Inject lateinit var scrobbleDispatcher: TvScrobbleDispatcher

    @Inject lateinit var tvShowCache: TvShowCache

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null
    private var idleTimeoutJob: Job? = null
    private var notificationAccessObserver: ContentObserver? = null
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
        // Discovery lifecycle is driven from the preference observer below —
        // don't blindly start here because the user may have toggled discovery
        // off between boot and the service starting up.
        observePreferences()
        return START_STICKY
    }

    private fun observePreferences() {
        if (observerJob != null) return
        observerJob = scope.launch {
            launch {
                preferences.isPhoneDiscoveryEnabled.collect { discovery ->
                    if (discovery) {
                        phoneDiscovery.setEnabled(true)
                        startScrobblerIfPermitted()
                        registerNotificationAccessObserver()
                        startIdleTimeoutMonitor()
                    } else {
                        idleTimeoutJob?.cancel()
                        DiagnosticLog.event(TAG, "phone discovery disabled — stopping self")
                        scrobbler.stopListening()
                        stopSelf()
                    }
                }
            }
            launch {
                preferences.debugLogMediaSession.collect { enabled ->
                    scrobbler.debugLogMediaSession = enabled
                }
            }
            launch { collectAmbiguousEvents() }
            launch { observeResolvedPrompts() }
        }
    }

    /** Fans ambiguous scrobble events to every connected phone. */
    private suspend fun collectAmbiguousEvents() {
        scrobbler.pendingAmbiguousEvent.collect { event ->
            scrobbleDispatcher.dispatchAmbiguous(event)
        }
    }

    /**
     * Watches capability responses for [lastResolvedSessionKey]; when a phone reports
     * a resolved prompt, clears the dispatcher dedup state and records an evidence hint
     * in [TvShowCache] so the next identical metadata shape short-circuits Phase 1.
     */
    private suspend fun observeResolvedPrompts() {
        var lastSeenResolved: String? = null
        phoneDiscovery.discoveredPhones.collect { phones ->
            phones.forEach { phone ->
                val capability = phone.capability ?: return@forEach
                val resolved = capability.lastResolvedSessionKey ?: return@forEach
                val traktId = capability.lastResolvedTraktId ?: return@forEach
                if (resolved == lastSeenResolved) return@forEach
                lastSeenResolved = resolved
                scrobbleDispatcher.clearResolvedPrompt(resolved)
                tvShowCache.recordEvidenceHint(resolved, traktId)
                DiagnosticLog.event(TAG, "resolved prompt sessionKey='$resolved' traktId=$traktId")
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

    /**
     * Start [MediaSessionScrobbler] if the user has granted Notification Access
     * to [WatchBuddyNotificationListener]. Without that grant,
     * [android.media.session.MediaSessionManager.getActiveSessions] silently
     * returns an empty list — log a breadcrumb so diagnostics can explain why
     * scrobbling is inert, and leave phone discovery running regardless.
     */
    private fun startScrobblerIfPermitted() {
        val component = ComponentName(this, WatchBuddyNotificationListener::class.java)
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        if (granted) {
            scrobbler.startListening(component)
            DiagnosticLog.event(TAG, "scrobbler listening")
        } else {
            DiagnosticLog.event(
                TAG,
                "scrobbler idle — notification access not granted",
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagnosticLog.event(TAG, "service destroyed")
        idleTimeoutJob?.cancel()
        scrobbler.stopListening()
        notificationAccessObserver?.let { contentResolver.unregisterContentObserver(it) }
        notificationAccessObserver = null
        observerJob?.cancel()
        scope.cancel()
    }

    /**
     * Registers a [ContentObserver] on `enabled_notification_listeners` in
     * [Settings.Secure] so the service can react to permission changes without
     * requiring the user to toggle Phone discovery. Idempotent — subsequent calls
     * while already registered are no-ops.
     */
    private fun registerNotificationAccessObserver() {
        if (notificationAccessObserver != null) return
        val uri = Settings.Secure.getUriFor("enabled_notification_listeners")
        notificationAccessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onNotificationAccessChanged()
            }
        }
        contentResolver.registerContentObserver(uri, false, notificationAccessObserver!!)
    }

    /**
     * Called whenever the system notification-listener allowlist changes.
     * Starts the scrobbler if access was just granted and it isn't running yet;
     * stops it immediately when access is revoked so [isListening] reflects reality
     * before the next per-tick poll in [MediaSessionScrobbler].
     */
    private fun onNotificationAccessChanged() {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        if (granted && !scrobbler.isListening.value) {
            DiagnosticLog.warn(TAG, "notification access granted — starting scrobbler")
            startScrobblerIfPermitted()
        } else if (!granted && scrobbler.isListening.value) {
            DiagnosticLog.warn(TAG, "notification access revoked — stopping scrobbler")
            scrobbler.stopListening()
        }
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
