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
 * App Info → Permissions. [TvMainActivity] requests the permission at runtime; the system
 * suppresses the dialog when it is auto-granted or the user has already answered.
 *
 * On non-TV form-factor (e.g. test runners) the query returns an empty cursor, which is
 * handled gracefully. The first [SecurityException] flips a process-wide [permissionDenied]
 * flag so subsequent scrobble cycles short-circuit before issuing the content-provider query
 * — without that, every tick throws and logs once per package. The flag is cleared via
 * [resetPermissionState] once the user grants the permission and the diagnostics screen
 * resumes.
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
            val now = System.currentTimeMillis()
            context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                PROJECTION,
                "${TvContractCompat.WatchNextPrograms.COLUMN_PACKAGE_NAME} = ?",
                arrayOf(packageName),
                "${TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val snippet = cursor.toSnippet()
                val age = snippet.lastEngagementMs?.let { now - it } ?: Long.MAX_VALUE
                if (age > ROW_FRESHNESS_MS) null else snippet
            }
        } catch (e: SecurityException) {
            permissionDenied = true
            DiagnosticLog.warn(TAG, "WatchNext: READ_TV_LISTINGS denied — disabling source", e)
            null
        }
    }

    /**
     * Counts distinct package names that have published a fresh WatchNext row within
     * [ROW_FRESHNESS_MS]. Used by TV Diagnostics to show how many apps are reachable.
     * Returns [CountResult.PermissionDenied] when the system denies `READ_TV_LISTINGS`.
     */
    fun countPublishingApps(): CountResult {
        if (isPermissionCurrentlyDenied()) return CountResult.PermissionDenied
        return try {
            val cutoffMs = System.currentTimeMillis() - ROW_FRESHNESS_MS
            context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(TvContractCompat.WatchNextPrograms.COLUMN_PACKAGE_NAME),
                "${TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS} > ?",
                arrayOf(cutoffMs.toString()),
                null,
            )?.use(::readDistinctPackageCount) ?: CountResult.Success(0)
        } catch (e: SecurityException) {
            permissionDenied = true
            DiagnosticLog.warn(TAG, "WatchNext: READ_TV_LISTINGS denied — disabling source", e)
            CountResult.PermissionDenied
        }
    }

    private fun readDistinctPackageCount(cursor: Cursor): CountResult.Success {
        val packages = mutableSetOf<String>()
        while (cursor.moveToNext()) {
            cursor.getString(0)?.let { packages += it }
        }
        return CountResult.Success(packages.size)
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
            TvContractCompat.WatchNextPrograms.COLUMN_TITLE,
            TvContractCompat.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContractCompat.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContractCompat.WatchNextPrograms.COLUMN_CONTENT_ID,
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
