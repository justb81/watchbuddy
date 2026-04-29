package com.justb81.watchbuddy.phone.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat

/**
 * Helpers for the runtime `POST_NOTIFICATIONS` prompt introduced in Android 13.
 * The companion foreground service silently drops its notification if the user
 * hasn't granted this permission (#261), which is indistinguishable from the
 * service not running at all — we always ask before the first start.
 */
object NotificationPermission {

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Launches `Settings.ACTION_APP_NOTIFICATION_SETTINGS` for the current app.
     * Used as the fallback when the user has permanently denied the prompt —
     * only the OS can undo that choice.
     */
    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * Remembers a launcher that requests `POST_NOTIFICATIONS` and invokes [onResult]
 * with the grant outcome. Safe to call on every Android version: if the
 * permission is not applicable (pre-Android 13 — not reachable with the current
 * `minSdk = 34`, but kept for symmetry) the launcher still returns `granted`.
 */
@Composable
fun rememberNotificationPermissionRequest(
    onResult: (granted: Boolean) -> Unit
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> onResult(granted) }
    )
    return remember(launcher) {
        { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
