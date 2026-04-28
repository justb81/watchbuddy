package com.justb81.watchbuddy.tv.scrobbler

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.PlaybackTick
import com.justb81.watchbuddy.core.scrobbler.MediaSnapshotBuilder
import com.justb81.watchbuddy.core.scrobbler.MetadataEnricher
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MetadataEnricher] that harvests show/episode evidence from the ongoing
 * [android.app.Notification.MediaStyle] notifications posted by streaming apps
 * while playback is active.
 *
 * Many streaming apps leave [android.media.MediaMetadata] blank but still post a
 * rich MediaStyle notification whose [android.app.Notification.extras] bundle carries
 * the show title, episode title, and a free-form text field — exactly the evidence
 * Phase 1 needs. The user has already granted Notification Access to unlock
 * [android.media.session.MediaSessionManager.getActiveSessions], so no additional
 * permission prompt is required.
 *
 * ### Two-layer freshness gate
 * 1. [PlaybackTick.isPlaying] — skips the map lookup for paused/stopped sessions.
 * 2. [FRESHNESS_MS] (5 min) — rejects snippets that were posted too long ago.
 *    Apps sometimes leave the notification on screen after playback ends; this
 *    prevents a finished episode from contaminating a fresh session that hasn't
 *    posted its own notification yet.
 *
 * ### Thread safety
 * [byPackage] is a [ConcurrentHashMap]; reads and writes from [onPosted],
 * [onRemoved], and [enrich] are all lock-free and safe to call from any thread.
 *
 * ### Snippet tags written to [MediaSnapshotBuilder]
 * | Tag                    | Source extra                      |
 * |------------------------|-----------------------------------|
 * | `notification.title`   | EXTRA_TITLE / EXTRA_TITLE_BIG     |
 * | `notification.text`    | EXTRA_TEXT                        |
 * | `notification.subText` | EXTRA_SUB_TEXT                    |
 * | `notification.bigText` | EXTRA_BIG_TEXT                    |
 * | `notification.infoText`| EXTRA_INFO_TEXT                   |
 */
@Singleton
class NotificationMetadataSource @Inject constructor() : MetadataEnricher {

    internal val byPackage = ConcurrentHashMap<String, NotificationSnippet>()

    internal fun onPosted(snippet: NotificationSnippet) {
        byPackage[snippet.packageName] = snippet
    }

    internal fun onRemoved(packageName: String) {
        byPackage.remove(packageName)
    }

    override suspend fun enrich(
        packageName: String,
        tick: PlaybackTick,
        builder: MediaSnapshotBuilder,
    ) {
        if (!tick.isPlaying) return

        val snip = byPackage[packageName] ?: return
        if (System.currentTimeMillis() - snip.postedAtMs > FRESHNESS_MS) return

        builder.add("notification.title", snip.title)
        builder.add("notification.text", snip.text)
        builder.add("notification.subText", snip.subText)
        builder.add("notification.bigText", snip.bigText)
        builder.add("notification.infoText", snip.infoText)
        builder.addSource("notification")
        DiagnosticLog.event(
            TAG,
            "enrich pkg=$packageName title=${snip.title?.take(60)}",
        )
    }

    /**
     * Returns the count of distinct package names that have a stored snippet
     * posted within [DIAGNOSTICS_WINDOW_MS] (10 min). Used by TV Diagnostics
     * to show how many apps have recently posted media notifications.
     */
    fun recentlyObservedCount(): Int {
        val cutoff = System.currentTimeMillis() - DIAGNOSTICS_WINDOW_MS
        return byPackage.values.count { it.postedAtMs >= cutoff }
    }

    companion object {
        /** Snippets older than this are ignored by [enrich]. */
        const val FRESHNESS_MS = 5L * 60_000L

        /** Window used by [recentlyObservedCount] for the diagnostics green-dot threshold. */
        const val DIAGNOSTICS_WINDOW_MS = 10L * 60_000L

        private const val TAG = "NotifListener"
    }
}

internal data class NotificationSnippet(
    val packageName: String,
    val title: String?,
    val text: String?,
    val subText: String?,
    val bigText: String?,
    val infoText: String?,
    val postedAtMs: Long,
)
