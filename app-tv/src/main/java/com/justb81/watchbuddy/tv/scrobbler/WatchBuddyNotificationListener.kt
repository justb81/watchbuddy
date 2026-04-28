package com.justb81.watchbuddy.tv.scrobbler

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * [NotificationListenerService] that serves two purposes:
 *
 * 1. Unlocks [android.media.session.MediaSessionManager.getActiveSessions] — the
 *    platform requires a registered notification listener before returning active
 *    media sessions. Without it [com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler]
 *    always gets an empty list.
 *
 * 2. Harvests MediaStyle notification extras into [NotificationMetadataSource] so
 *    the scrobble cascade can match shows even when [android.media.MediaMetadata]
 *    fields are blank. Many streaming apps post a rich ongoing notification with
 *    show/episode info that they do not expose via MediaSession.
 *
 * Filtering rules applied before ingesting a notification:
 * - Must originate from a different package (we never ingest our own notifications).
 * - Must be ongoing (`isOngoing == true`).
 * - Must carry a MediaStyle template in [Notification.EXTRA_TEMPLATE].
 *
 * The [Notification.extras] bundle is read for the five evidence fields listed in
 * [NotificationMetadataSource]. Log entries truncate title to 60 chars to avoid
 * privacy bleed in shared diagnostics.
 */
@AndroidEntryPoint
class WatchBuddyNotificationListener : NotificationListenerService() {

    @Inject
    lateinit var notificationSource: NotificationMetadataSource

    override fun onListenerConnected() {
        super.onListenerConnected()
        DiagnosticLog.event(TAG, "listener connected")
        activeNotifications?.forEach { ingest(it) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DiagnosticLog.event(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        ingest(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isMediaStyle(sbn)) {
            notificationSource.onRemoved(sbn.packageName)
        }
    }

    private fun ingest(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (!sbn.isOngoing) return
        if (!isMediaStyle(sbn)) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: extras.getString(Notification.EXTRA_TITLE_BIG)?.takeIf { it.isNotBlank() }
        val snippet = NotificationSnippet(
            packageName = sbn.packageName,
            title = title,
            text = extras.getString(Notification.EXTRA_TEXT)?.takeIf { it.isNotBlank() },
            subText = extras.getString(Notification.EXTRA_SUB_TEXT)?.takeIf { it.isNotBlank() },
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?.takeIf { it.isNotBlank() },
            infoText = extras.getString(Notification.EXTRA_INFO_TEXT)?.takeIf { it.isNotBlank() },
            postedAtMs = System.currentTimeMillis(),
        )
        notificationSource.onPosted(snippet)
        DiagnosticLog.event(
            TAG,
            "ingest pkg=${sbn.packageName} title=${title?.take(NotificationMetadataSource.LOG_TITLE_MAX_CHARS)}",
        )
    }

    private fun isMediaStyle(sbn: StatusBarNotification): Boolean {
        val template = sbn.notification.extras.getString(Notification.EXTRA_TEMPLATE)
        return template == "android.app.Notification\$MediaStyle" ||
            template == "androidx.media.app.NotificationCompat\$MediaStyle"
    }

    companion object {
        private const val TAG = "NotificationListener"
    }
}
