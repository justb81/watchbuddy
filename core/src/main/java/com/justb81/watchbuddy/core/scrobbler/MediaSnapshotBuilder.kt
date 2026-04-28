package com.justb81.watchbuddy.core.scrobbler

import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot

/**
 * Builds a [MediaMetadataSnapshot] as an ordered list of `"<tag>: <value>"` lines.
 *
 * Lines are appended in priority order — the first line carries the highest-signal
 * field. Blank or null values are silently skipped; newline characters inside
 * values are stripped to keep each line a single logical unit.
 *
 * [packageName] is the streaming-app package name (e.g. `com.plexapp.android`).
 */
class MediaSnapshotBuilder(val packageName: String) {
    private val lines = mutableListOf<String>()
    private val sources = mutableSetOf<String>()

    fun add(tag: String, value: String?) {
        val v = value?.replace('\n', ' ')?.replace('\r', ' ')?.trim() ?: return
        if (v.isBlank()) return
        lines += "$tag: $v"
    }

    fun addSource(source: String) {
        sources += source
    }

    fun build(): MediaMetadataSnapshot =
        MediaMetadataSnapshot(packageName, lines.joinToString("\n"), sources)
}
