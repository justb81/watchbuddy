package com.justb81.watchbuddy.core.logging

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * In-process diagnostic breadcrumb buffer.
 *
 * Exists because the Settings screen has now survived four targeted fix attempts
 * (#170, #177, #196, #224) without ever producing an actionable stack trace — the
 * project previously had no persistent logging. This log is a thread-safe ring
 * buffer of recent events that is (a) dumped into crash reports by [CrashReporter]
 * and (b) attachable to a share intent via [DiagnosticShare] so the user can send
 * us what the app was doing right up until the crash.
 *
 * Each entry is lightweight (timestamp + level + tag + message + optional throwable
 * summary) and all calls mirror to [android.util.Log] so logcat continues to work
 * unchanged during development.
 */
object DiagnosticLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestampMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwableSummary: String?
    )

    private const val MAX_ENTRIES = 500

    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)
    private val lock = Any()

    private val timestampFormatter: ThreadLocal<SimpleDateFormat> =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }

    fun event(tag: String, message: String) = append(Level.INFO, tag, message, null)

    fun debug(tag: String, message: String) = append(Level.DEBUG, tag, message, null)

    fun warn(tag: String, message: String, t: Throwable? = null) =
        append(Level.WARN, tag, message, t)

    fun error(tag: String, message: String, t: Throwable? = null) =
        append(Level.ERROR, tag, message, t)

    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList() }

    fun clear() = synchronized(lock) { buffer.clear() }

    /** Renders the current buffer as a human-readable block for sharing. */
    fun formatForShare(): String {
        val entries = snapshot()
        if (entries.isEmpty()) return "(no breadcrumbs captured)"
        return buildString {
            entries.forEach { entry ->
                append(formatTimestamp(entry.timestampMs))
                append(' ')
                append(entry.level.name.padEnd(5))
                append(" [")
                append(entry.tag)
                append("] ")
                append(entry.message)
                append('\n')
                entry.throwableSummary?.let {
                    append("    -> ")
                    append(it.replace("\n", "\n    "))
                    append('\n')
                }
            }
        }
    }

    private fun append(level: Level, tag: String, message: String, throwable: Throwable?) {
        val entry = Entry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwableSummary = throwable?.let(::summarizeThrowable)
        )
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.pollFirst()
            buffer.addLast(entry)
        }
        mirrorToLogcat(entry, throwable)
    }

    private fun summarizeThrowable(t: Throwable): String {
        val first = "${t.javaClass.name}: ${t.message ?: ""}".trim()
        val topFrame = t.stackTrace.firstOrNull()?.let { "at $it" }
        val cause = t.cause?.let { "caused by ${it.javaClass.simpleName}: ${it.message ?: ""}".trim() }
        return listOfNotNull(first, topFrame, cause).joinToString("\n")
    }

    private fun mirrorToLogcat(entry: Entry, throwable: Throwable?) {
        val androidTag = "WB/${entry.tag}"
        when (entry.level) {
            Level.DEBUG -> Log.d(androidTag, entry.message)
            Level.INFO -> Log.i(androidTag, entry.message)
            Level.WARN -> if (throwable != null) Log.w(androidTag, entry.message, throwable)
                          else Log.w(androidTag, entry.message)
            Level.ERROR -> if (throwable != null) Log.e(androidTag, entry.message, throwable)
                           else Log.e(androidTag, entry.message)
        }
    }

    internal fun formatTimestamp(ms: Long): String =
        timestampFormatter.get()!!.format(Date(ms))
}
