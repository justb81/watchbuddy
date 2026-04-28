package com.justb81.watchbuddy.core.scrobbler

import com.justb81.watchbuddy.core.model.PlaybackTick

/**
 * Extension point for plugging additional evidence sources into the scrobble
 * cascade without reopening [MediaSessionScrobbler] for each addition.
 *
 * Implementations append `"<tag>: <value>"` lines to [builder] for [packageName].
 * Short-circuit when [tick] is not actively playing so slow sources (content
 * provider queries, notification reads) are skipped for stale sessions.
 *
 * Registered via the [MediaSessionScrobbler] constructor. The TV app will
 * populate this list in #471 (WatchNextMetadataSource) and #472
 * (NotificationMetadataSource). The phone app and tests use an empty list.
 */
fun interface MetadataEnricher {
    suspend fun enrich(packageName: String, tick: PlaybackTick, builder: MediaSnapshotBuilder)
}
