package com.justb81.watchbuddy.tv.scrobbler

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.TvContractCompat
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.PlaybackTick
import com.justb81.watchbuddy.core.scrobbler.MediaSnapshotBuilder
import com.justb81.watchbuddy.core.scrobbler.MetadataEnricher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MetadataEnricher] that harvests currently-playing program metadata from the
 * Android TV system WatchNext provider.
 *
 * Most major streaming apps (Netflix, Disney+, Prime Video, Apple TV+, YouTube TV, etc.)
 * populate the WatchNext content provider so the Android TV launcher can render its
 * "Continue Watching" row. That data contains the show title, season/episode numbers,
 * episode title, and an optional content ID — exactly the evidence the scrobble cascade
 * needs but that `MediaSession` rarely carries.
 *
 * ### Two-layer freshness gate
 * 1. [PlaybackTick.isPlaying] — skips the content-provider round-trip entirely for
 *    paused/stopped sessions. Stale launcher rows are meaningless when nothing is playing.
 * 2. [ROW_FRESHNESS_MS] (5 min) — rejects rows whose
 *    `COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS` is too old. Launcher rows linger after
 *    playback ends, so a purely state-based gate is not sufficient when the user pauses
 *    mid-episode and the playback state remains `STATE_PAUSED` for a while.
 *
 * ### Content-ID prefix table (trusted on day one)
 * | Prefix  | Source          | Short-circuit? |
 * |---------|-----------------|----------------|
 * | `tmdb:` | Disney+, Prime  | Yes — confidence 1.0 in Phase 0.5 of [MediaSessionScrobbler] |
 * | other   | Netflix, etc.   | No — row still contributes title/season/episode evidence lines |
 *
 * ### Permission
 * `READ_TV_LISTINGS` has protection level `signature|privileged|appop` on current Android.
 * Most Android TV firmwares auto-grant it to apps with the leanback launcher feature, but a
 * growing number of Google TV / Chromecast-with-Google-TV / 3rd-party Android TV builds do
 * not — they require either an explicit `requestPermissions(...)` flow or a manual toggle in
 * App Info → "Additional Permissions". [TvMainActivity] requests it at runtime; the system
 * suppresses the dialog when it is auto-granted or the user has already answered.
 *
 * ### Why we never pass a `selection` clause to the WatchNext provider
 * AOSP `TvProvider.createSqlParams(...)` throws
 * `SecurityException("Selection not allowed for content://android.media.tv/watch_next_program…")`
 * for any non-empty `selection`/`selectionArgs` unless the caller holds
 * `com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA`. That permission is
 * `signature|privileged` and unattainable for normal apps — `READ_TV_LISTINGS` does **not**
 * exempt the caller. With `READ_TV_LISTINGS` granted the provider returns every "searchable"
 * row from every package; without it, only the caller's own rows. Either way the cursor is
 * filtered by package-name and freshness in app code below — see [lookup] / [countPublishingApps].
 *
 * The canonical signal for the diagnostics red/yellow/green dot is therefore
 * [ContextCompat.checkSelfPermission] for `READ_TV_LISTINGS`, not a cached [SecurityException].
 * The `permissionDenied` latch is kept as a defensive backstop so a surprise SecurityException
 * (e.g. from a downstream OEM fork) doesn't spam the log on every scrobble tick.
 */
// TvContractCompat.WatchNextPrograms inherits column-name constants from internal
// ProgramColumns / PreviewProgramColumns interfaces annotated @RestrictTo(LIBRARY_GROUP).
// There is no public alternative — these are the only column names for WatchNext queries.
@SuppressLint("RestrictedApi")
@Singleton
class WatchNextMetadataSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MetadataEnricher {

    /** Result of a diagnostic count of apps currently publishing to the WatchNext provider. */
    sealed class CountResult {
        /** [count] distinct package names found within [ROW_FRESHNESS_MS]. */
        data class Success(val count: Int) : CountResult()

        /** `READ_TV_LISTINGS` was denied — user or device policy blocked the query. */
        object PermissionDenied : CountResult()
    }

    /**
     * Set to `true` once [SecurityException] has been thrown by the WatchNext provider so
     * subsequent calls short-circuit without re-issuing the query. Cleared by
     * [resetPermissionState] (explicit) or automatically by [isPermissionCurrentlyDenied] when
     * a live [ContextCompat.checkSelfPermission] call detects an out-of-band grant — e.g. when
     * the user grants `READ_TV_LISTINGS` via system Settings without returning through the in-app
     * permission dialog or the diagnostics screen.
     */
    @Volatile
    private var permissionDenied: Boolean = false

    /** Visible for testing — exposes the cached denial flag. */
    internal fun isPermissionDenied(): Boolean = permissionDenied

    /**
     * Clears the cached `READ_TV_LISTINGS` denial so the next [enrich]/[countPublishingApps]
     * call retries the query. Called from the diagnostics screen after the user returns from
     * the system permission settings.
     */
    fun resetPermissionState() {
        permissionDenied = false
    }

    /**
     * Returns `true` when the WatchNext query should be skipped due to a permission denial.
     *
     * If the cached [permissionDenied] flag is set, performs a live
     * [ContextCompat.checkSelfPermission] check to detect out-of-band grants (e.g. the user
     * toggled the permission in system Settings without coming back through the in-app dialog).
     * When the live check confirms the permission is now granted the flag is cleared
     * automatically so the next query proceeds without an app restart.
     */
    private fun isPermissionCurrentlyDenied(): Boolean {
        if (!permissionDenied) return false
        return if (ContextCompat.checkSelfPermission(context, READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED) {
            permissionDenied = false
            DiagnosticLog.event(TAG, "WatchNext: READ_TV_LISTINGS granted out-of-band — re-enabling source")
            false
        } else {
            true
        }
    }

    override suspend fun enrich(
        packageName: String,
        tick: PlaybackTick,
        builder: MediaSnapshotBuilder,
    ) = withContext(Dispatchers.IO) {
        if (!tick.isPlaying) {
            DiagnosticLog.debug(TAG, "WatchNext: skipped (not playing) for $packageName")
            return@withContext
        }
        val snippet = lookup(packageName) ?: return@withContext
        builder.add("watchNext.title", snippet.title)
        builder.add("watchNext.season", snippet.seasonDisplayNumber)
        builder.add("watchNext.episode", snippet.episodeDisplayNumber)
        builder.add("watchNext.episodeTitle", snippet.episodeTitle)
        builder.add("watchNext.contentId", snippet.contentId)
        synthesiseMarker(snippet)?.let { builder.add("watchNext.marker", it) }
        builder.addSource("watchNext")
        DiagnosticLog.event(
            TAG,
            "WatchNext: matched $packageName title=${snippet.title} " +
                "S${snippet.seasonDisplayNumber}E${snippet.episodeDisplayNumber} " +
                "contentId=${snippet.contentId}",
        )
    }

    private fun lookup(packageName: String): WatchNextSnippet? {
        if (isPermissionCurrentlyDenied()) return null
        return try {
            // No selection / sortOrder: TvProvider rejects either with
            // SecurityException("Selection not allowed for ...") unless the caller holds
            // ACCESS_ALL_EPG_DATA (signature|privileged). Filter and rank in app code.
            val now = System.currentTimeMillis()
            context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                var freshest: WatchNextSnippet? = null
                while (cursor.moveToNext()) {
                    val rowPackage = cursor.getStringOrNull(
                        TvContractCompat.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                    )
                    if (rowPackage != packageName) continue
                    val snippet = cursor.toSnippet()
                    val engagement = snippet.lastEngagementMs ?: continue
                    if (now - engagement > ROW_FRESHNESS_MS) continue
                    val incumbent = freshest?.lastEngagementMs ?: Long.MIN_VALUE
                    if (engagement > incumbent) freshest = snippet
                }
                freshest
            }
        } catch (e: SecurityException) {
            permissionDenied = true
            DiagnosticLog.warn(
                TAG,
                "WatchNext: provider rejected query unexpectedly — disabling source",
                e,
            )
            null
        }
    }

    /**
     * Counts distinct package names that have published a fresh WatchNext row within
     * [ROW_FRESHNESS_MS]. Used by TV Diagnostics to show how many apps are reachable.
     * Returns [CountResult.PermissionDenied] when `READ_TV_LISTINGS` is not granted (the
     * canonical red-dot signal — checked via [ContextCompat.checkSelfPermission] before any
     * provider round-trip).
     */
    fun countPublishingApps(): CountResult {
        if (isPermissionCurrentlyDenied()) return CountResult.PermissionDenied
        if (ContextCompat.checkSelfPermission(context, READ_TV_LISTINGS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionDenied = true
            return CountResult.PermissionDenied
        }
        return try {
            // No selection / sortOrder — see lookup() for the AOSP rationale.
            val now = System.currentTimeMillis()
            context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                COUNT_PROJECTION,
                null,
                null,
                null,
            )?.use { cursor -> readFreshDistinctPackageCount(cursor, now) }
                ?: CountResult.Success(0)
        } catch (e: SecurityException) {
            permissionDenied = true
            DiagnosticLog.warn(
                TAG,
                "WatchNext: provider rejected query unexpectedly — disabling source",
                e,
            )
            CountResult.PermissionDenied
        }
    }

    private fun readFreshDistinctPackageCount(cursor: Cursor, nowMs: Long): CountResult.Success {
        val packages = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            val pkg = cursor.getStringOrNull(
                TvContractCompat.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            ) ?: continue
            val engagement = cursor.getLongOrNull(
                TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            ) ?: continue
            if (nowMs - engagement <= ROW_FRESHNESS_MS) packages += pkg
        }
        return CountResult.Success(packages.size)
    }

    private fun Cursor.getStringOrNull(columnName: String): String? {
        val idx = getColumnIndex(columnName)
        if (idx < 0 || isNull(idx)) return null
        return getString(idx)?.takeIf { it.isNotBlank() }
    }

    private fun Cursor.getLongOrNull(columnName: String): Long? {
        val idx = getColumnIndex(columnName)
        if (idx < 0 || isNull(idx)) return null
        return getLong(idx)
    }

    private fun Cursor.toSnippet(): WatchNextSnippet {
        fun col(name: String): String? = getColumnIndex(name).takeIf { it >= 0 }?.let {
            getString(it)?.takeIf { v -> v.isNotBlank() }
        }
        fun colLong(name: String): Long? = getColumnIndex(name).takeIf { it >= 0 }?.let {
            if (isNull(it)) null else getLong(it)
        }
        return WatchNextSnippet(
            title = col(TvContractCompat.WatchNextPrograms.COLUMN_TITLE),
            seasonDisplayNumber = col(TvContractCompat.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER),
            episodeDisplayNumber = col(TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER),
            episodeTitle = col(TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_TITLE),
            shortDescription = col(TvContractCompat.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION),
            contentId = col(TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID),
            lastEngagementMs = colLong(TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS),
        )
    }

    /**
     * Synthesises a `S##E##` marker when both season and episode numbers are present and
     * parse as integers. The synthesised line is appended as `watchNext.marker` so the
     * existing [MediaSessionScrobbler] marker regex picks it up without modification.
     */
    private fun synthesiseMarker(snippet: WatchNextSnippet): String? {
        val season = snippet.seasonDisplayNumber?.trim()?.toIntOrNull() ?: return null
        val episode = snippet.episodeDisplayNumber?.trim()?.toIntOrNull() ?: return null
        return "S%02dE%02d".format(season, episode)
    }

    companion object {
        /** Rows older than this are considered stale and ignored. */
        const val ROW_FRESHNESS_MS = 5L * 60_000L

        private const val TAG = "WatchNextMetadataSource"

        /**
         * String literal for `android.permission.READ_TV_LISTINGS` — the constant is
         * `@SystemApi` / `@hide` in the public SDK stubs and not resolvable at compile time.
         */
        private const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

        private val PROJECTION = arrayOf(
            TvContractCompat.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContractCompat.WatchNextPrograms.COLUMN_TITLE,
            TvContractCompat.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContractCompat.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID,
            TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        )

        private val COUNT_PROJECTION = arrayOf(
            TvContractCompat.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        )
    }
}

internal data class WatchNextSnippet(
    val title: String?,
    val seasonDisplayNumber: String?,
    val episodeDisplayNumber: String?,
    val episodeTitle: String?,
    val shortDescription: String?,
    val contentId: String?,
    val lastEngagementMs: Long?,
)
