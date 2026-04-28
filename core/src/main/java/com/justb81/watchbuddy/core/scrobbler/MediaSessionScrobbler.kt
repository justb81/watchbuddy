package com.justb81.watchbuddy.core.scrobbler

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
import com.justb81.watchbuddy.core.model.PlaybackTick
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TitleExtractionResponse
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.progress.ShowProgressCalculator
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens to active MediaSessions and automatically scrobbles to Trakt.
 *
 * Shared between the phone and TV apps:
 *   - TV: [WatchedShowSource] reads from in-memory TvShowCache populated by connected phones;
 *     [ScrobbleDispatcher] fans scrobbles to each connected phone's HTTP API.
 *   - Phone: [WatchedShowSource] reads from ShowRepository (Trakt cache);
 *     [ScrobbleDispatcher] calls the Trakt scrobble API directly with the phone's own token.
 *
 * Confidence thresholds: ≥ 0.95 auto-scrobble; 0.70–0.95 emit for UI confirmation; < 0.70 ignore.
 */
@Singleton
class MediaSessionScrobbler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tmdbApiService: TmdbApiService,
    private val watchedShowSource: WatchedShowSource,
    private val scrobbleDispatcher: ScrobbleDispatcher,
    private val titleExtractor: TitleExtractor,
    /** Enrichers append additional evidence lines; populated per-app via Hilt. */
    private val metadataEnrichers: List<@JvmSuppressWildcards MetadataEnricher> = emptyList(),
) {
    companion object {
        private const val TAG = "MediaSessionScrobbler"
        internal const val AUTO_SCROBBLE_THRESHOLD = 0.95f
        internal const val OVERLAY_THRESHOLD = 0.70f
    }

    /** Diagnostic snapshot of a candidate the scrobbler recently saw. */
    data class LastCandidate(
        val candidate: ScrobbleCandidate,
        val observedAtMs: Long,
        val autoScrobbled: Boolean,
    )

    /**
     * Full dump of the most recent `MediaSession` the scrobbler polled, regardless
     * of whether it produced a scrobble candidate. Intended for the TV diagnostics
     * view so the user can see exactly which evidence lines the streaming app
     * published — `METADATA_KEY_TITLE` is frequently null on Plex, Jellyfin,
     * and some Netflix skins, so surfacing only the title row hides everything.
     *
     * [observedAtMs] is kept separately from [tick.capturedAtMs] because it drives
     * the staleness check for implicit-stop reconciliation (5-minute presence timeout).
     */
    data class LastObservedSession(
        val snapshot: MediaMetadataSnapshot,
        val tick: PlaybackTick,
        val observedAtMs: Long,
    ) {
        @Deprecated("Use tick.state", ReplaceWith("tick.state"))
        val playbackState: Int get() = tick.state

        @Deprecated("Use tick.positionMs", ReplaceWith("tick.positionMs"))
        val positionMs: Long get() = tick.positionMs

        @Deprecated("Use tick.durationMs", ReplaceWith("tick.durationMs"))
        val durationMs: Long get() = tick.durationMs
    }

    /** Strips the `"<tag>: "` prefix from a text-blob line and returns the bare value. */
    private fun String.stripTag(): String = substringAfter(": ", missingDelimiterValue = this).trim()

    /**
     * Per-session progress snapshot kept around so `reconcileVanished` can dispatch
     * an implicit stop using the full [MediaMetadataSnapshot] (not just the title)
     * when the session disappears without transitioning through `STATE_STOPPED`.
     */
    internal data class LastKnownProgress(
        val snapshot: MediaMetadataSnapshot?,
        val progress: Float,
    )

    private val _pendingConfirmation = MutableSharedFlow<ScrobbleCandidate>()
    val pendingConfirmation: SharedFlow<ScrobbleCandidate> = _pendingConfirmation

    private val _isListening = MutableStateFlow(false)
    /** True while [startListening] is active; flips back to false on [stopListening]. */
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastCandidate = MutableStateFlow<LastCandidate?>(null)
    /**
     * Most recent candidate observed — regardless of whether it was auto-scrobbled
     * or emitted to [pendingConfirmation]. Populated only for candidates that
     * clear [OVERLAY_THRESHOLD]. Intended for diagnostics/UI surfacing so the
     * user can see "the scrobbler *is* seeing things" even when no match auto-fires.
     */
    val lastCandidate: StateFlow<LastCandidate?> = _lastCandidate.asStateFlow()

    private val _lastObservedSession = MutableStateFlow<LastObservedSession?>(null)

    /**
     * Most recent `MediaSession` polled, regardless of match outcome. Populated
     * on every poll tick that encounters at least one session, so the diagnostics
     * view can show every MediaMetadata field even when the scrobble cascade
     * can't produce a match (e.g. TITLE is null).
     */
    val lastObservedSession: StateFlow<LastObservedSession?> = _lastObservedSession.asStateFlow()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private var currentlySessionKey: String? = null

    /**
     * Returns true when this app currently holds notification-listener access.
     * Overridable in tests to avoid a real Android framework call.
     */
    internal var notificationAccessChecker: () -> Boolean = {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    /**
     * Debug firehose toggle. When true, every poll tick writes a per-session breadcrumb
     * to [DiagnosticLog] (package, title, state, position, duration) regardless of
     * confidence, plus a near-miss line when a title fails to clear [OVERLAY_THRESHOLD].
     *
     * Set from the TV layer (see `TvDiscoveryService.observePreferences`) based on the
     * "Debug: log every media session" setting. The phone leaves this `false`.
     */
    @Volatile
    var debugLogMediaSession: Boolean = false

    /**
     * Last observed playback progress per session key, captured on every poll that yields
     * a usable `position/duration`. Feeds the implicit-stop dispatched by [reconcileVanished]
     * when a session disappears without ever emitting STATE_STOPPED (issue #402). Stores the
     * last-known [MediaMetadataSnapshot] alongside the progress so vanished-stop can re-run
     * the full multi-field match cascade, not just title-based matching.
     */
    private val lastProgressBySessionKey = ConcurrentHashMap<String, LastKnownProgress>()

    /**
     * Checks notification-listener access on every poll tick, updates [_isListening],
     * and emits a breadcrumb when the state flips. Returns true when access is granted
     * and session polling should proceed.
     */
    private fun updateListeningState(): Boolean {
        val granted = notificationAccessChecker()
        val wasListening = _isListening.value
        _isListening.value = granted
        if (wasListening != granted) {
            if (granted) {
                DiagnosticLog.event(TAG, "notification access granted — scrobbler resumed")
            } else {
                DiagnosticLog.warn(TAG, "notification access revoked — scrobbler paused")
            }
        }
        return granted
    }

    fun startListening(notificationListenerComponent: ComponentName) {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as MediaSessionManager

        pollingJob = scope.launch {
            while (isActive) {
                if (!updateListeningState()) {
                    delay(30_000)
                    continue
                }
                try {
                    val sessions = sessionManager.getActiveSessions(notificationListenerComponent)
                    val liveKeys = mutableSetOf<String>()
                    var publishedDiagnostics = false
                    sessions.forEach { controller ->
                        val packageName = controller.packageName
                        val metadata = controller.metadata
                        val playbackState = controller.playbackState
                        val tick = buildTick(playbackState, metadata)
                        val snapshot = if (metadata != null) {
                            buildSnapshotWithEnrichers(packageName, metadata, tick)
                        } else {
                            MediaMetadataSnapshot(packageName = packageName)
                        }
                        if (!publishedDiagnostics) {
                            publishObservedSession(snapshot, tick)
                            publishedDiagnostics = true
                        }
                        logSessionIfDebug(snapshot, tick.state, tick.positionMs, tick.durationMs)
                        val key = snapshot.sessionKey() ?: return@forEach
                        liveKeys.add(key)
                        if (metadata == null || playbackState == null) return@forEach
                        val progress = computeProgress(playbackState, metadata)
                        if (progress != null) recordProgress(key, snapshot, progress)
                        when (playbackState.state) {
                            PlaybackState.STATE_PLAYING -> processPlayingMedia(snapshot, key, progress, tick)
                            PlaybackState.STATE_PAUSED -> handleScrobblePause(snapshot, key, progress)
                            PlaybackState.STATE_STOPPED,
                            PlaybackState.STATE_NONE -> handleScrobbleStop(snapshot, key, progress)
                            else -> {}
                        }
                    }
                    reconcileVanished(liveKeys)
                } catch (e: Exception) {
                    Log.w(TAG, "Session polling error", e)
                }
                delay(30_000)
            }
        }
    }

    /**
     * Stable identity for a media session across polls. Reads the first non-blank
     * line of [MediaMetadataSnapshot.text] (stripping the `tag: ` prefix) so
     * sessions where `METADATA_KEY_TITLE` is null (Plex ships the show in
     * `ALBUM_ARTIST`, Jellyfin in `ALBUM`) still get a durable key. The builder
     * writes lines in the same priority order as the old `candidateStrings()` so
     * behaviour is identical for the MediaSession-only case.
     * Returns null when [text] is blank — caller skips scrobble but still
     * publishes the snapshot to [lastObservedSession] for diagnostics.
     */
    internal fun MediaMetadataSnapshot.sessionKey(): String? =
        text.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.stripTag()
            ?.takeIf { it.isNotBlank() }
            ?.let { "$packageName:$it" }

    internal fun sessionKey(candidate: ScrobbleCandidate): String =
        "${candidate.packageName}:${candidate.mediaTitle}"

    internal fun publishObservedSession(
        snapshot: MediaMetadataSnapshot,
        tick: PlaybackTick,
    ) {
        _lastObservedSession.value = LastObservedSession(
            snapshot = snapshot,
            tick = tick,
            observedAtMs = System.currentTimeMillis(),
        )
    }

    /** Compat overload used by tests that still pass raw state/position/duration ints. */
    internal fun publishObservedSession(
        snapshot: MediaMetadataSnapshot,
        playbackState: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        publishObservedSession(
            snapshot,
            PlaybackTick(
                state = playbackState,
                positionMs = positionMs,
                durationMs = durationMs,
                capturedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Reads every MediaMetadata field a streaming app might use to ship the show
     * name or `S##E##` marker and emits them as prioritised `"tag: value"` lines.
     * Netflix/Prime/Disney+ ship only the episode title in `METADATA_KEY_TITLE`;
     * Plex publishes the show in `ALBUM_ARTIST`; Jellyfin uses `ALBUM`; some
     * Netflix skins use `DISPLAY_SUBTITLE`. The builder insertion order is the
     * field-priority ranking (albumArtist first), preserved through to the
     * cascade that strips tag prefixes and tries each line as a candidate title.
     */
    internal fun buildSnapshot(
        packageName: String,
        metadata: android.media.MediaMetadata,
    ): MediaMetadataSnapshot {
        val builder = MediaSnapshotBuilder(packageName)
        builder.add("mediaSession.albumArtist", metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
        builder.add("mediaSession.album", metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM))
        builder.add("mediaSession.artist", metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST))
        builder.add("mediaSession.displayTitle", metadata.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        builder.add("mediaSession.displaySubtitle", metadata.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        builder.add("mediaSession.title", metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
        builder.add("mediaSession.displayDescription", metadata.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
        return builder.build()
    }

    /** Samples the Android [PlaybackState] and metadata duration into a [PlaybackTick]. */
    private fun buildTick(
        playbackState: android.media.session.PlaybackState?,
        metadata: android.media.MediaMetadata?,
    ) = PlaybackTick(
        state = playbackState?.state ?: -1,
        positionMs = playbackState?.position ?: -1L,
        durationMs = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: -1L,
        capturedAtMs = System.currentTimeMillis(),
    )

    /**
     * Calls [buildSnapshot] then lets every registered [MetadataEnricher] append
     * additional evidence lines. Enrichers should short-circuit when [tick] is
     * not actively playing to avoid querying providers for stale sessions.
     */
    private suspend fun buildSnapshotWithEnrichers(
        packageName: String,
        metadata: android.media.MediaMetadata,
        tick: PlaybackTick,
    ): MediaMetadataSnapshot {
        if (metadataEnrichers.isEmpty()) return buildSnapshot(packageName, metadata)
        val builder = MediaSnapshotBuilder(packageName)
        builder.add("mediaSession.albumArtist", metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
        builder.add("mediaSession.album", metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM))
        builder.add("mediaSession.artist", metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST))
        builder.add("mediaSession.displayTitle", metadata.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        builder.add("mediaSession.displaySubtitle", metadata.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        builder.add("mediaSession.title", metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
        builder.add("mediaSession.displayDescription", metadata.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
        metadataEnrichers.forEach { it.enrich(packageName, tick, builder) }
        return builder.build()
    }

    internal fun recordProgress(sessionKey: String, snapshot: MediaMetadataSnapshot?, progress: Float) {
        lastProgressBySessionKey[sessionKey] = LastKnownProgress(snapshot, progress)
    }

    internal fun recordProgress(sessionKey: String, progress: Float) {
        recordProgress(sessionKey, null, progress)
    }

    /**
     * Writes a breadcrumb listing the full evidence text blob the streaming app
     * published, plus playback state / position / duration. The text lines show
     * which field actually carries signal (Plex/Jellyfin/Netflix-skin metadata
     * shapes differ widely), making the "Debug: log every media session" toggle
     * useful for diagnosing missed scrobbles.
     */
    internal fun logSessionIfDebug(
        snapshot: MediaMetadataSnapshot,
        state: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (!debugLogMediaSession) return
        val evidence = if (snapshot.text.isBlank()) "(no evidence)" else snapshot.text.replace("\n", " | ")
        DiagnosticLog.event(
            TAG,
            "session pkg=${snapshot.packageName} state=$state pos=${positionMs}ms dur=${durationMs}ms $evidence",
        )
    }

    /**
     * Detect the case where a streaming app destroys its MediaSession instead of
     * transitioning through STATE_STOPPED (common on YouTube and some Fire TV skins —
     * issue #402). Without this reconciliation `currentlySessionKey` would stay
     * pinned to the last episode forever, silently blocking every future scrobble
     * of the same session inside [processPlayingMedia]'s dedup guard.
     *
     * When the previously-tracked session key is no longer in the live set, dispatch
     * an implicit stop with the last observed progress (best-effort — we don't know the
     * real endpoint) and clear local state so the next playback can scrobble again.
     * Uses the last-known [MediaMetadataSnapshot] so the match goes through the full
     * multi-field cascade — falling back to title-only matching on the key suffix
     * (`packageName:title`) only when no snapshot was stashed (direct test calls).
     */
    internal suspend fun reconcileVanished(liveKeys: Set<String>) {
        val stale = currentlySessionKey ?: return
        if (stale in liveKeys) return
        val last = lastProgressBySessionKey.remove(stale)
        currentlySessionKey = null
        if (last == null) {
            DiagnosticLog.warn(TAG, "Session vanished without captured progress — cleared '$stale'")
            return
        }
        try {
            val candidate = last.snapshot?.let { matchSnapshot(it) }
                ?: matchTitle("", stale.substringAfter(':', missingDelimiterValue = stale))
            val show = candidate?.matchedShow
            val episode = candidate?.matchedEpisode
            if (show != null && episode != null) {
                scrobbleDispatcher.dispatchStop(show, episode, last.progress)
                Log.i(TAG, "Session vanished — implicit stop: ${show.title} S${episode.season}E${episode.number}")
            } else {
                DiagnosticLog.warn(TAG, "Session vanished — no match for '$stale', dispatch skipped")
            }
        } catch (e: Exception) {
            DiagnosticLog.warn(TAG, "Session vanished — stop dispatch failed for '$stale'", e)
        }
    }

    fun stopListening() {
        pollingJob?.cancel()
        scope.cancel()
        _isListening.value = false
        currentlySessionKey = null
        lastProgressBySessionKey.clear()
        _lastObservedSession.value = null
    }

    private suspend fun processPlayingMedia(
        snapshot: MediaMetadataSnapshot,
        sessionKey: String,
        progress: Float?,
        tick: PlaybackTick = PlaybackTick.UNKNOWN,
    ) {
        if (sessionKey == currentlySessionKey) return
        val candidate = matchSnapshot(snapshot, tick)
        if (candidate == null) {
            if (debugLogMediaSession) {
                DiagnosticLog.debug(TAG, "no match for '$sessionKey' — best confidence null")
            }
            return
        }
        if (candidate.confidence >= AUTO_SCROBBLE_THRESHOLD) {
            autoScrobble(candidate, progress, sessionKey)
            _lastCandidate.value = LastCandidate(candidate, System.currentTimeMillis(), autoScrobbled = true)
        } else if (candidate.confidence >= OVERLAY_THRESHOLD) {
            _pendingConfirmation.emit(candidate)
            _lastCandidate.value = LastCandidate(candidate, System.currentTimeMillis(), autoScrobbled = false)
        } else if (debugLogMediaSession) {
            DiagnosticLog.debug(
                TAG,
                "no match for '$sessionKey' — best confidence ${candidate.confidence}",
            )
        }
    }

    internal fun computeProgress(
        playbackState: PlaybackState,
        metadata: android.media.MediaMetadata
    ): Float? {
        val durationMs = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        val positionMs = playbackState.position
        if (durationMs <= 0L || positionMs < 0L) return null
        return (positionMs * 100f / durationMs).coerceIn(0f, 100f)
    }

    // ── Fuzzy Matching ────────────────────────────────────────────────────────

    /**
     * Convenience adaptor preserved so vanish/stop paths and existing tests can
     * match with only a raw title. The full multi-field + LLM fallback cascade
     * runs through [matchSnapshot] — always prefer that entry point when the
     * full `MediaMetadata` is available.
     */
    internal suspend fun matchTitle(packageName: String, rawTitle: String): ScrobbleCandidate? {
        val builder = MediaSnapshotBuilder(packageName)
        builder.add("mediaSession.title", rawTitle)
        return matchSnapshot(builder.build())
    }

    /**
     * Full match cascade:
     *   0.5 **Content-ID short-circuit** — if a `watchNext.contentId: tmdb:XXXX` line is
     *      present, look up the show in the cached library by TMDB ID. When found and
     *      season/episode numbers are available, returns a confidence-1.0 candidate
     *      immediately — no LLM, no TMDB search.
     *   1. **Phase 1 (cheap)** — try every MediaMetadata field from [snapshot] as
     *      a candidate show title, parse any `S##E##` marker, score against the
     *      cached library with [fuzzyScore], keep the highest-scoring cache hit.
     *   2. **LLM fallback** — if no field clears [OVERLAY_THRESHOLD], ask the
     *      injected [TitleExtractor] for a normalized `(showTitle, season?,
     *      episode?)` and retry the cache match with it.
     *   3. **TMDB fallback** — if the library still has no match, search TMDB
     *      with the best-scoring candidate (cheap-path best or extractor
     *      output).
     */
    /**
     * [tick] is threaded through for future consumers (#474 duration tiebreaker,
     * #471/#472 freshness gates inside enrichers) but does not influence Phase 1/2/3
     * scoring in this issue.
     */
    @Suppress("UnusedParameter")
    internal suspend fun matchSnapshot(
        snapshot: MediaMetadataSnapshot,
        tick: PlaybackTick = PlaybackTick.UNKNOWN,
    ): ScrobbleCandidate? {
        val candidates = snapshot.text.lines().map { it.stripTag() }.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return null
        val mediaTitle = candidates.first()

        // Phase 0.5: content-ID short-circuit for trusted prefixes (only `tmdb:` on day one)
        val contentIdHit = resolveByContentId(snapshot, mediaTitle)
        if (contentIdHit != null) return contentIdHit

        val (globalSeason, globalEpisode) = findGlobalEpisodeMarker(candidates)
        val cachedShows = watchedShowSource.getCachedShows()
        val bestCheap = candidates
            .map { field -> scoreCandidate(field, cachedShows) }
            .maxByOrNull { it.score }

        val cheapHit = resolveCheapHit(snapshot, bestCheap, globalSeason, globalEpisode, mediaTitle)
        if (cheapHit != null) return cheapHit

        val extraction = runCatching { titleExtractor.extract(snapshot) }
            .onFailure { DiagnosticLog.warn(TAG, "Title extractor threw", it) }
            .getOrNull()
        val llmHit = extraction?.let { resolveExtraction(snapshot, it, cachedShows, mediaTitle) }
        if (llmHit != null) return llmHit

        return tmdbFallback(snapshot, extraction, bestCheap, mediaTitle)
    }

    /**
     * Phase 0.5: if the snapshot contains a `watchNext.contentId` line with a trusted
     * prefix, resolve the show by content ID without running Levenshtein or LLM.
     *
     * Only `tmdb:` is trusted on day one — confidence 1.0 when the TMDB ID is found in
     * the user's library. Other prefixes (opaque Netflix/YouTube IDs) fall through to
     * Phase 1 so the snapshot's `watchNext.title` line can still contribute evidence.
     */
    private suspend fun resolveByContentId(
        snapshot: MediaMetadataSnapshot,
        mediaTitle: String,
    ): ScrobbleCandidate? {
        val contentId = findSnapshotTag(snapshot, "watchNext.contentId")
            ?.takeIf { it.startsWith("tmdb:") } ?: return null
        val tmdbId = contentId.removePrefix("tmdb:").toIntOrNull() ?: return null
        val cachedShows = watchedShowSource.getCachedShows()
        val entry = cachedShows.firstOrNull { it.show.ids.tmdb == tmdbId } ?: return null
        val season = findSnapshotTag(snapshot, "watchNext.season")?.toIntOrNull()
        val episode = findSnapshotTag(snapshot, "watchNext.episode")?.toIntOrNull()
        val matchedEpisode = if (season != null && episode != null) {
            TraktEpisode(season = season, number = episode)
        } else {
            null
        }
        DiagnosticLog.event(
            TAG,
            "WatchNext: content-ID short-circuit tmdbId=$tmdbId " +
                "show=${entry.show.title} S${season}E${episode} confidence=1.0",
        )
        return ScrobbleCandidate(
            packageName = snapshot.packageName,
            mediaTitle = mediaTitle,
            confidence = 1.0f,
            matchedShow = entry.show,
            matchedEpisode = matchedEpisode,
        )
    }

    /** Extracts the value portion of the first `"<tag>: <value>"` line matching [tag]. */
    private fun findSnapshotTag(snapshot: MediaMetadataSnapshot, tag: String): String? =
        snapshot.text.lines()
            .firstOrNull { it.startsWith("$tag: ") }
            ?.substringAfter("$tag: ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun findGlobalEpisodeMarker(candidates: List<String>): Pair<Int?, Int?> {
        val episodePattern = Regex("""(?i)S(\d{1,2})E(\d{1,2})""")
        val match = candidates.firstNotNullOfOrNull { episodePattern.find(it) }
        val season = match?.groupValues?.get(1)?.toIntOrNull()
        val episode = match?.groupValues?.get(2)?.toIntOrNull()
        return season to episode
    }

    private suspend fun resolveCheapHit(
        snapshot: MediaMetadataSnapshot,
        bestCheap: CheapMatch?,
        globalSeason: Int?,
        globalEpisode: Int?,
        mediaTitle: String,
    ): ScrobbleCandidate? {
        val entry = bestCheap?.cacheEntry ?: return null
        if (bestCheap.score < OVERLAY_THRESHOLD) return null
        val matchedEpisode = resolveEpisode(
            bestCheap.season ?: globalSeason,
            bestCheap.episode ?: globalEpisode,
            entry,
            bestCheap.score,
        )
        return ScrobbleCandidate(
            packageName = snapshot.packageName,
            mediaTitle = mediaTitle,
            confidence = bestCheap.score,
            matchedShow = entry.show,
            matchedEpisode = matchedEpisode,
        )
    }

    private suspend fun tmdbFallback(
        snapshot: MediaMetadataSnapshot,
        extraction: TitleExtractionResponse?,
        bestCheap: CheapMatch?,
        mediaTitle: String,
    ): ScrobbleCandidate? {
        val tmdbQuery = extraction?.showTitle?.takeIf { it.isNotBlank() }
            ?: bestCheap?.normalizedTitle?.takeIf { it.isNotBlank() }
            ?: return null
        val tmdbSeason = extraction?.season ?: bestCheap?.season
        val tmdbEpisode = extraction?.episode ?: bestCheap?.episode
        val tmdbApiKey = watchedShowSource.getTmdbApiKey() ?: return null
        return try {
            val tmdbResults = tmdbApiService.searchTv(tmdbQuery, tmdbApiKey).results
            val bestTmdbMatch = tmdbResults.maxByOrNull { fuzzyScore(it.name, tmdbQuery) }
            val tmdbScore = bestTmdbMatch?.let { fuzzyScore(it.name, tmdbQuery) } ?: 0f
            if (tmdbScore < 0.50f || bestTmdbMatch == null) {
                null
            } else {
                ScrobbleCandidate(
                    packageName = snapshot.packageName,
                    mediaTitle = mediaTitle,
                    confidence = tmdbScore,
                    matchedShow = TraktShow(
                        title = bestTmdbMatch.name,
                        year = bestTmdbMatch.first_air_date?.take(4)?.toIntOrNull(),
                        ids = TraktIds(tmdb = bestTmdbMatch.id),
                    ),
                    matchedEpisode = if (tmdbSeason != null && tmdbEpisode != null) {
                        TraktEpisode(season = tmdbSeason, number = tmdbEpisode)
                    } else {
                        null
                    },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "TMDB search failed for '$tmdbQuery'", e)
            null
        }
    }

    /**
     * Result of scoring one MediaMetadata field against the cache. [score] is
     * the best fuzzy score achieved by any cache entry against the normalized
     * show-title slice of this field; [cacheEntry] is the winning entry (null
     * when the library is empty or the field was unusable).
     */
    private data class CheapMatch(
        val rawField: String,
        val normalizedTitle: String,
        val season: Int?,
        val episode: Int?,
        val score: Float,
        val cacheEntry: TraktWatchedEntry?,
    )

    private fun scoreCandidate(
        field: String,
        cachedShows: List<TraktWatchedEntry>,
    ): CheapMatch {
        val episodePattern = Regex("""(?i)S(\d{1,2})E(\d{1,2})""")
        val match = episodePattern.find(field)
        val showTitle = if (match != null) field.substringBefore(match.value).trim() else field.trim()
        val season = match?.groupValues?.get(1)?.toIntOrNull()
        val episode = match?.groupValues?.get(2)?.toIntOrNull()
        if (showTitle.isBlank() || cachedShows.isEmpty()) {
            return CheapMatch(field, showTitle, season, episode, 0f, null)
        }
        val best = cachedShows.maxByOrNull { fuzzyScore(it.show.title, showTitle) }
        val score = best?.let { fuzzyScore(it.show.title, showTitle) } ?: 0f
        return CheapMatch(field, showTitle, season, episode, score, best)
    }

    /**
     * Takes the LLM extractor's normalized output and resolves it through the
     * same deterministic cache match the cheap path uses. This guarantees the
     * TV never scrobbles a show the LLM hallucinated that isn't in the user's
     * library — the cache match stays the source of truth.
     */
    private suspend fun resolveExtraction(
        snapshot: MediaMetadataSnapshot,
        extraction: TitleExtractionResponse,
        cachedShows: List<TraktWatchedEntry>,
        mediaTitle: String,
    ): ScrobbleCandidate? {
        // Collapse the "no usable title" and "empty cache" paths into one
        // return: if either fails, maxByOrNull returns null and we exit
        // through the single cache-miss branch.
        val showTitle = extraction.showTitle?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val bestCacheMatch = cachedShows.maxByOrNull { fuzzyScore(it.show.title, showTitle) }
            ?: return null
        val score = fuzzyScore(bestCacheMatch.show.title, showTitle)
        if (score < OVERLAY_THRESHOLD) return null
        val matchedEpisode = resolveEpisode(
            extraction.season,
            extraction.episode,
            bestCacheMatch,
            score,
        )
        DiagnosticLog.event(
            TAG,
            "LLM fallback resolved '${snapshot.text.lines().firstOrNull()?.stripTag()}' " +
                "→ '${bestCacheMatch.show.title}' " +
                "S${matchedEpisode?.season}E${matchedEpisode?.number} score=$score",
        )
        return ScrobbleCandidate(
            packageName = snapshot.packageName,
            mediaTitle = mediaTitle,
            confidence = score,
            matchedShow = bestCacheMatch.show,
            matchedEpisode = matchedEpisode,
        )
    }

    /**
     * Resolve the episode tuple for a cache-matched show. Prefers the explicit `S##E##`
     * pair parsed from MediaMetadata when available; otherwise falls back to the progress
     * hint so scrobbles from Netflix / Prime / Disney+ (which ship only the episode title
     * in `METADATA_KEY_TITLE`) aren't silently dropped (issue #401). The fallback only
     * activates when show-match confidence clears [AUTO_SCROBBLE_THRESHOLD] — below that
     * the overlay path still asks the user to confirm.
     */
    private suspend fun resolveEpisode(
        explicitSeason: Int?,
        explicitEpisode: Int?,
        cacheEntry: TraktWatchedEntry,
        confidence: Float
    ): TraktEpisode? {
        if (explicitSeason != null && explicitEpisode != null) {
            return TraktEpisode(season = explicitSeason, number = explicitEpisode)
        }
        if (confidence < AUTO_SCROBBLE_THRESHOLD) return null
        val hint = watchedShowSource.getShowHint(cacheEntry.show.ids)
        if (hint == null) {
            DiagnosticLog.warn(
                TAG,
                "scrobble dropped — no episode in title and no progress hint for '${cacheEntry.show.title}'"
            )
            return null
        }
        return ShowProgressCalculator.nextEpisodeNumbers(cacheEntry, hint)
            ?.let { TraktEpisode(season = it.first, number = it.second) }
    }

    internal fun normalize(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\bthe\\b"), "")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    internal fun fuzzyScore(a: String, b: String): Float {
        val normA = normalize(a)
        val normB = normalize(b)
        if (normA.isEmpty() || normB.isEmpty()) return 0f
        if (normA == normB) return 1.0f
        if (normA.startsWith(normB) || normB.startsWith(normA)) return 0.95f
        val distance = levenshteinDistance(normA, normB)
        val maxLen = maxOf(normA.length, normB.length)
        return (1.0f - (distance.toFloat() / maxLen)).coerceAtLeast(0f)
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }

    // ── Scrobble API ──────────────────────────────────────────────────────────

    suspend fun autoScrobble(
        candidate: ScrobbleCandidate,
        progress: Float? = null,
        sessionKey: String? = null,
    ) {
        val show = candidate.matchedShow ?: return
        val episode = candidate.matchedEpisode ?: return
        scrobbleDispatcher.dispatchStart(show, episode, progress ?: 0f)
        currentlySessionKey = sessionKey ?: this.sessionKey(candidate)
        Log.i(TAG, "Scrobble started: ${show.title} S${episode.season}E${episode.number}")
    }

    internal suspend fun handleScrobblePause(
        snapshot: MediaMetadataSnapshot,
        sessionKey: String,
        progress: Float? = null,
    ) {
        if (sessionKey != currentlySessionKey) return
        val candidate = matchSnapshot(snapshot) ?: return
        val show = candidate.matchedShow ?: return
        val episode = candidate.matchedEpisode ?: return
        scrobbleDispatcher.dispatchPause(show, episode, progress ?: 50f)
        Log.i(TAG, "Scrobble paused: ${show.title}")
    }

    internal suspend fun handleScrobbleStop(
        snapshot: MediaMetadataSnapshot,
        sessionKey: String,
        progress: Float? = null,
    ) {
        if (sessionKey != currentlySessionKey) return
        val effectiveProgress = progress ?: run {
            DiagnosticLog.warn(
                TAG,
                "scrobble stop: duration/position unavailable for '$sessionKey' — assuming 100% (watched)"
            )
            100f
        }
        val candidate = matchSnapshot(snapshot)
        val show = candidate?.matchedShow
        val episode = candidate?.matchedEpisode
        if (show == null || episode == null) return
        scrobbleDispatcher.dispatchStop(show, episode, effectiveProgress)
        currentlySessionKey = null
        Log.i(TAG, "Scrobble stopped: ${show.title} S${episode.season}E${episode.number}")
    }
}
