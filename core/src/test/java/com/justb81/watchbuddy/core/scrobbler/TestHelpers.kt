package com.justb81.watchbuddy.core.scrobbler

import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
import com.justb81.watchbuddy.core.model.PlaybackTick

/**
 * Builds a [MediaMetadataSnapshot] for tests using the same field-priority order
 * that [MediaSnapshotBuilder] / [MediaSessionScrobbler.buildSnapshot] uses in
 * production: albumArtist > album > artist > displayTitle > displaySubtitle >
 * title > displayDescription. Pass only the fields relevant to each test case.
 */
internal fun snapshotOf(packageName: String, vararg fields: Pair<String, String>): MediaMetadataSnapshot {
    val map = fields.toMap()
    val builder = MediaSnapshotBuilder(packageName)
    listOf("albumArtist", "album", "artist", "displayTitle", "displaySubtitle", "title", "displayDescription")
        .forEach { key -> map[key]?.let { builder.add("mediaSession.$key", it) } }
    return builder.build()
}

/** Builds a [PlaybackTick] for tests with sensible defaults. */
internal fun tickOf(
    state: Int = PlaybackTick.STATE_PLAYING,
    positionMs: Long = 600_000L,
    durationMs: Long = 2_700_000L,
    capturedAtMs: Long = System.currentTimeMillis(),
) = PlaybackTick(state = state, positionMs = positionMs, durationMs = durationMs, capturedAtMs = capturedAtMs)
