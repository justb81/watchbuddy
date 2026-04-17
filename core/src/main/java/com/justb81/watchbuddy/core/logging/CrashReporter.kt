package com.justb81.watchbuddy.core.logging

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes uncaught exceptions to disk so the user can share them later.
 *
 * The Settings crash has survived four targeted fixes without an actionable stack
 * trace reaching the maintainer. This class installs a
 * [Thread.UncaughtExceptionHandler] that captures the failure, appends the current
 * [DiagnosticLog] snapshot, writes everything to `filesDir/diagnostics/`, and then
 * delegates to the previous handler so Android's own process kill still runs.
 */
object CrashReporter {

    private const val REPORTS_DIR = "diagnostics"
    private const val REPORT_PREFIX = "crash_"
    private const val REPORT_SUFFIX = ".txt"
    /** Keep at most this many crash files on disk to avoid unbounded growth. */
    private const val MAX_RETAINED_REPORTS = 20

    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashReport(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
        DiagnosticLog.event(TAG, "CrashReporter installed")
    }

    fun reportsDir(context: Context): File =
        File(context.filesDir, REPORTS_DIR).also { it.mkdirs() }

    fun hasPendingReports(context: Context): Boolean =
        listReports(context).isNotEmpty()

    fun listReports(context: Context): List<File> =
        reportsDir(context)
            .listFiles { f -> f.isFile && f.name.startsWith(REPORT_PREFIX) && f.name.endsWith(REPORT_SUFFIX) }
            ?.toList()
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun clearReports(context: Context): Int {
        val files = listReports(context)
        var removed = 0
        files.forEach { if (it.delete()) removed++ }
        DiagnosticLog.event(TAG, "Cleared $removed crash report(s)")
        return removed
    }

    /**
     * Writes a fresh report containing only the current [DiagnosticLog] snapshot
     * (no crash). Useful when the user wants to export diagnostics even though
     * nothing has crashed — e.g. to help us understand a freeze or a silent bug.
     */
    fun writeManualSnapshot(context: Context, reason: String = "manual"): File {
        val dir = reportsDir(context)
        val file = File(dir, "snapshot_${System.currentTimeMillis()}$REPORT_SUFFIX")
        val content = buildReport(
            context = context,
            title = "WatchBuddy Diagnostic Snapshot",
            thread = Thread.currentThread(),
            throwable = null,
            note = "Reason: $reason"
        )
        file.writeText(content)
        enforceRetention(dir)
        return file
    }

    private fun writeCrashReport(context: Context, thread: Thread, throwable: Throwable) {
        val dir = reportsDir(context)
        val file = File(dir, "$REPORT_PREFIX${System.currentTimeMillis()}$REPORT_SUFFIX")
        val content = buildReport(
            context = context,
            title = "WatchBuddy Crash Report",
            thread = thread,
            throwable = throwable,
            note = null
        )
        file.writeText(content)
        enforceRetention(dir)
    }

    private fun buildReport(
        context: Context,
        title: String,
        thread: Thread,
        throwable: Throwable?,
        note: String?
    ): String = buildString {
        append(title).append('\n')
        append("=".repeat(title.length)).append('\n')
        append("Timestamp:   ").append(DiagnosticLog.formatTimestamp(System.currentTimeMillis())).append('\n')
        append("App:         ").append(appVersionString(context)).append('\n')
        append("Device:      ").append(Build.MANUFACTURER).append(' ')
            .append(Build.MODEL).append(" (").append(Build.BRAND).append(" / Android ")
            .append(Build.VERSION.RELEASE).append(", SDK ").append(Build.VERSION.SDK_INT)
            .append(')').append('\n')
        append("Process:     ").append(context.packageName).append('\n')
        append("Thread:      ").append(thread.name).append('\n')
        note?.let { append("Note:        ").append(it).append('\n') }
        append('\n')

        if (throwable != null) {
            append("--- Uncaught Exception ---\n")
            append(stackTraceToString(throwable))
            append('\n')
        }

        append("--- Diagnostic Breadcrumbs (").append(DiagnosticLog.snapshot().size).append(" entries) ---\n")
        append(DiagnosticLog.formatForShare())
        append('\n')
        append("--- End of Report ---\n")
    }

    private fun stackTraceToString(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString()
    }

    private fun appVersionString(context: Context): String =
        try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION") info.versionCode.toString()
            }
            "${context.packageName} v${info.versionName} (vc=$versionCode)"
        } catch (_: PackageManager.NameNotFoundException) {
            context.packageName
        }

    private fun enforceRetention(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(REPORT_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size <= MAX_RETAINED_REPORTS) return
        files.drop(MAX_RETAINED_REPORTS).forEach { runCatching { it.delete() } }
    }

    @Suppress("unused")
    internal fun formatFilesModifiedAt(file: File): String =
        DiagnosticLog.formatTimestamp(Date(file.lastModified()).time)

    private const val TAG = "CrashReporter"
}
