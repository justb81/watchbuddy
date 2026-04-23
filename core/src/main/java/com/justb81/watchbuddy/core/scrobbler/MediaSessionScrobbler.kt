package com.justb81.watchbuddy.core.scrobbler

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
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
     * view so the user can see exactly which `MediaMetadata` fields the streaming
     * app published — `METADATA_KEY_TITLE` is frequently null on Plex, Jellyfin,
     * and some Netflix skins, so surfacing only the title row hides everything.
     */
    data class LastObservedSession(
        val snapshot: MediaMetadataSnapshot,
        val playbackState: Int,
        val positionMs: Long,
        val durationMs: Long,
        val observedAtMs: Long,
    )

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

    fun startListening(notificationListenerComponent: ComponentName) {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        _isListening.value = true
        val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as MediaSessionManager

        pollingJob = scope.launch {
            while (isActive) {
                try {
                    val sessions = sessionManager.getActiveSessions(notificationListenerComponent)
                    val liveKeys = mutableSetOf<String>()
                    var publishedDiagnostics = false
                    sessions.forEach { controller ->
                        val packageName = controller.packageName
                        val metadata = controller.metadata
                        val playbackState = controller.playbackState
                        val snapshot = if (metadata != null) {
                            buildSnapshot(packageName, metadata)
                        } else {
                            MediaMetadataSnapshot(packageName = packageName)
                        }
                        val durationMs = metadata
                            ?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: -1L
                        val state = playbackState?.state ?: -1
                        val positionMs = playbackState?.position ?: -1L
                        if (!publishedDiagnostics) {
                            publishObservedSession(snapshot, state, positionMs, durationMs)
                            publishedDiagnostics = true
                        }
                        logSessionIfDebug(snapshot, state, positionMs, durationMs)
                        val key = snapshot.sessionKey() ?: return@forEach
                        liveKeys.add(key)
                        if (metadata == null || playbackState == null) return@forEach
                        val progress = computeProgress(playbackState, metadata)
                        if (progress != null) recordProgress(key, snapshot, progress)
                        when (playbackState.state) {
                            PlaybackState.STATE_PLAYING -> processPlayingMedia(snapshot, key, progress)
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
     * Stable identity for a media session across polls. Uses the first non-blank
     * field from [MediaMetadataSnapshot.candidateStrings] so sessions where
     * `METADATA_KEY_TITLE` is null (Plex ships the show in `ALBUM_ARTIST`,
     * Jellyfin in `ALBUM`) still get a durable key instead of being dropped.
     * Returns null when every string field is blank — caller skips scrobble but
     * still publishes the snapshot to [lastObservedSession] for diagnostics.
     */
    internal fun MediaMetadataSnapshot.sessionKey(): String? =
        candidateStrings().firstOrNull()?.let { "$packageName:$it" }

    internal fun sessionKey(candidate: ScrobbleCandidate): String =
        "${candidate.packageName}:${candidate.mediaTitle}"

    internal fun publishObservedSession(
        snapshot: MediaMetadataSnapshot,
        playbackState: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        _lastObservedSession.value = LastObservedSession(
            snapshot = snapshot,
            playbackState = playbackState,
            positionMs = positionMs,
            durationMs = durationMs,
            observedAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * Reads every MediaMetadata field a streaming app might use to ship the show
     * name or `S##E##` marker. Netflix/Prime/Disney+ ship only the episode title
     * in `METADATA_KEY_TITLE`; Plex publishes the show in `ALBUM_ARTIST`;
     * Jellyfin uses `ALBUM`; some Netflix skins use `DISPLAY_SUBTITLE`. Pulling
     * all of them up front lets the match cascade score each candidate and keep
     * the best one without round-tripping back to the controller.
     */
    internal fun buildSnapshot(
        packageName: String,
        metadata: android.media.MediaMetadata,
    ): MediaMetadataSnapshot = MediaMetadataSnapshot(
        packageName = packageName,
        title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
        displayTitle = metadata.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
        displaySubtitle = metadata.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
        displayDescription = metadata.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
        artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
        albumArtist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
        album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
    )

    internal fun recordProgress(sessionKey: String, snapshot: MediaMetadataSnapshot?, progress: Float) {
        lastProgressBySessionKey[sessionKey] = LastKnownProgress(snapshot, progress)
    }

    internal fun recordProgress(sessionKey: String, progress: Float) {
        recordProgress(sessionKey, null, progress)
    }

    /**
     * Writes a breadcrumb listing every non-null `MediaMetadata` string field
     * the streaming app published, plus playback state / position / duration.
     * Previous versions only logged `title`, which hid the fact that Plex /
     * Jellyfin / some Netflix skins ship the show in other fields and leave
     * `METADATA_KEY_TITLE` null — the "Debug: log every media session" toggle
     * needs to surface the full picture so the user can see which field
     * actually carries signal.
     */
    internal fun logSessionIfDebug(
        snapshot: MediaMetadataSnapshot,
        state: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (!debugLogMediaSession) return
        val head = "session pkg=${snapshot.packageName} state=$state " +
            "pos=${positionMs}ms dur=${durationMs}ms"
        val fields = buildList {
            add("title" to snapshot.title)
            add("displayTitle" to snapshot.displayTitle)
            add("displaySubtitle" to snapshot.displaySubtitle)
            add("displayDescription" to snapshot.displayDescription)
            add("artist" to snapshot.artist)
            add("albumArtist" to snapshot.albumArtist)
            add("album" to snapshot.album)
        }
        val tail = fields.joinToString(" ") { (name, value) ->
            "$name='${value ?: "(null)"}'"
        }
        DiagnosticLog.event(TAG, "$head $tail")
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
    ) {
        if (sessionKey == currentlySessionKey) return
        val candidate = matchSnapshot(snapshot)
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
    internal suspend fun matchTitle(packageName: String, rawTitle: String): ScrobbleCandidate? =
        matchSnapshot(MediaMetadataSnapshot(packageName = packageName, title = rawTitle))

    /**
     * Full match cascade:
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
    internal suspend fun matchSnapshot(snapshot: MediaMetadataSnapshot): ScrobbleCandidate? {
        val candidates = snapshot.candidateStrings()
        if (candidates.isEmpty()) return null
        val mediaTitle = snapshot.title ?: candidates.first()

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
            "LLM fallback resolved '${snapshot.title}' → '${bestCacheMatch.show.title}' " +
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
        if (progress == null) {
            Log.w(TAG, "Scrobble stop skipped — playback position/duration unavailable for '$sessionKey'")
            currentlySessionKey = null
            return
        }
        val candidate = matchSnapshot(snapshot)
        val show = candidate?.matchedShow
        val episode = candidate?.matchedEpisode
        if (show == null || episode == null) return
        scrobbleDispatcher.dispatchStop(show, episode, progress)
        currentlySessionKey = null
        Log.i(TAG, "Scrobble stopped: ${show.title} S${episode.season}E${episode.number}")
    }
}
