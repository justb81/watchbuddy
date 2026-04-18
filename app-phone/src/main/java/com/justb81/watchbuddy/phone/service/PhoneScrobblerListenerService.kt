package com.justb81.watchbuddy.phone.service

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.util.Log
import com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Notification-listener service that enables the shared [MediaSessionScrobbler] to access
 * active MediaSessions on the phone. The system binds this service when the user grants
 * notification access in Settings → Apps → Special app access → Notification access.
 *
 * No runtime permission dialog exists; the app deep-links the user into the system
 * notification-listener settings screen from the Auto-Scrobble settings section.
 */
@AndroidEntryPoint
class PhoneScrobblerListenerService : NotificationListenerService() {

    @Inject
    lateinit var scrobbler: MediaSessionScrobbler

    override fun onListenerConnected() {
        Log.i(TAG, "Notification listener connected — starting media session scrobbler")
        scrobbler.startListening(
            ComponentName(this, PhoneScrobblerListenerService::class.java)
        )
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "Notification listener disconnected — stopping scrobbler")
        scrobbler.stopListening()
    }

    private companion object {
        const val TAG = "PhoneScrobblerListener"
    }
}
