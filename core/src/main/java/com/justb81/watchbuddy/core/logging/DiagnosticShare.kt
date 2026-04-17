package com.justb81.watchbuddy.core.logging

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.ArrayList

/**
 * Builds a share intent for WatchBuddy diagnostic reports.
 *
 * The entry point is [launchShare], which bundles the current [DiagnosticLog]
 * snapshot together with every pending crash file and opens the system chooser.
 * Any caller exposing a "Share diagnostics" affordance should delegate here so
 * the logic stays in one place.
 */
object DiagnosticShare {

    /** Matches the <provider> authority registered in both app manifests. */
    private const val FILE_PROVIDER_SUFFIX = ".diagnostics.fileprovider"

    /**
     * Opens the system share sheet with all current diagnostic artefacts attached.
     *
     * The chooser is started with [Intent.FLAG_ACTIVITY_NEW_TASK] so callers can
     * invoke this from non-Activity contexts (e.g. Composable callbacks that only
     * hold the application Context).
     */
    fun launchShare(context: Context) {
        val appContext = context.applicationContext
        val authority = appContext.packageName + FILE_PROVIDER_SUFFIX

        val snapshotFile = writeSnapshotFile(appContext)
        val crashFiles = CrashReporter.listReports(appContext)
            // The manual snapshot we just wrote is also listed by the reporter, so
            // exclude it to avoid attaching it twice.
            .filter { it.absolutePath != snapshotFile.absolutePath }

        val uris = ArrayList<Uri>(1 + crashFiles.size)
        uris += FileProvider.getUriForFile(appContext, authority, snapshotFile)
        crashFiles.forEach { file ->
            runCatching { FileProvider.getUriForFile(appContext, authority, file) }
                .getOrNull()
                ?.let(uris::add)
        }

        val sendIntent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }.apply {
            putExtra(Intent.EXTRA_SUBJECT, "WatchBuddy diagnostic report")
            putExtra(Intent.EXTRA_TEXT, "Attached: ${uris.size} diagnostic file(s).")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(sendIntent, "Share diagnostic report").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        DiagnosticLog.event(
            TAG,
            "Launching share intent with ${uris.size} file(s) (crashFiles=${crashFiles.size})"
        )
        appContext.startActivity(chooser)
    }

    private fun writeSnapshotFile(context: Context): File =
        CrashReporter.writeManualSnapshot(context, reason = "share")

    private const val TAG = "DiagnosticShare"
}
