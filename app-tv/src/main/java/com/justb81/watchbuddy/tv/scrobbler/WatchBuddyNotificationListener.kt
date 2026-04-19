package com.justb81.watchbuddy.tv.scrobbler

import android.service.notification.NotificationListenerService
import com.justb81.watchbuddy.core.logging.DiagnosticLog

/**
 * Placeholder [NotificationListenerService] required to unlock
 * [android.media.session.MediaSessionManager.getActiveSessions]. Without a
 * registered notification listener the platform silently returns an empty
 * list and [com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler]
 * has nothing to work with.
 *
 * We do not process notifications here — the only reason this class exists
 * is so the user can grant "Notification access" to WatchBuddy in TV
 * Settings and so we have a valid [android.content.ComponentName] to hand
 * to [android.media.session.MediaSessionManager].
 */
class WatchBuddyNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        DiagnosticLog.event(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DiagnosticLog.event(TAG, "listener disconnected")
    }

    companion object {
        private const val TAG = "NotificationListener"
    }
}
