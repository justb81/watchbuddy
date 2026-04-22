package com.justb81.watchbuddy.core.scrobbler

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
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
    private val scrobbleDispatcher: ScrobbleDispatcher
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

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private var currentlyScrobbling: String? = null

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
     * Last observed playback progress per media title, captured on every poll that yields
     * a usable `position/duration`. Feeds the implicit-stop dispatched by [reconcileVanished]
     * when a session disappears without ever emitting STATE_STOPPED (issue #402).
     */
    private val lastProgressByTitle = ConcurrentHashMap<String, Float>()

    fun startListening(notificationListenerComponent: ComponentName) {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        _isListening.value = true
        val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as MediaSessionManager

        pollingJob = scope.launch {
            while (isActive) {
                try {
                    val sessions = sessionManager.getActiveSessions(notificationListenerComponent)
                    val liveTitles = sessions
                        .mapNotNull { it.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) }
                        .toSet()
                    reconcileVanished(liveTitles)
                    sessions.forEach { controller ->
                        val packageName = controller.packageName
                        val metadata = controller.metadata
                        val playbackState = controller.playbackState
                        val rawTitle = metadata
                            ?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                        val durationMs = metadata
                            ?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: -1L
                        val state = playbackState?.state ?: -1
                        val positionMs = playbackState?.position ?: -1L
                        logSessionIfDebug(packageName, rawTitle, state, positionMs, durationMs)
                        if (metadata == null || playbackState == null || rawTitle == null) return@forEach
                        val progress = computeProgress(playbackState, metadata)
                        if (progress != null) recordProgress(rawTitle, progress)
                        when (playbackState.state) {
                            PlaybackState.STATE_PLAYING -> processPlayingMedia(packageName, rawTitle, progress)
                            PlaybackState.STATE_PAUSED -> handleScrobblePause(rawTitle, progress)
                            PlaybackState.STATE_STOPPED,
                            PlaybackState.STATE_NONE -> handleScrobbleStop(rawTitle, progress)
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Session polling error", e)
                }
                delay(30_000)
            }
        }
    }

    internal fun recordProgress(title: String, progress: Float) {
        lastProgressByTitle[title] = progress
    }

    internal fun logSessionIfDebug(
        packageName: String,
        title: String?,
        state: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (!debugLogMediaSession) return
        DiagnosticLog.event(
            TAG,
            "session pkg=$packageName title='${title ?: "(null)"}' state=$state " +
                "pos=${positionMs}ms dur=${durationMs}ms",
        )
    }

    /**
     * Detect the case where a streaming app destroys its MediaSession instead of
     * transitioning through STATE_STOPPED (common on YouTube and some Fire TV skins —
     * issue #402). Without this reconciliation `currentlyScrobbling` would stay pinned
     * to the last episode forever, silently blocking every future scrobble of the same
     * title inside [processPlayingMedia]'s dedup guard.
     *
     * When the previously-tracked title is no longer in the live session set, dispatch
     * an implicit stop with the last observed progress (best-effort — we don't know the
     * real endpoint) and clear local state so the next playback can scrobble again.
     */
    internal suspend fun reconcileVanished(liveTitles: Set<String>) {
        val stale = currentlyScrobbling ?: return
        if (stale in liveTitles) return
        val lastProgress = lastProgressByTitle.remove(stale)
        currentlyScrobbling = null
        if (lastProgress == null) {
            DiagnosticLog.warn(TAG, "Session vanished without captured progress — cleared '$stale'")
            return
        }
        try {
            val candidate = matchTitle("", stale)
            val show = candidate?.matchedShow
            val episode = candidate?.matchedEpisode
            if (show != null && episode != null) {
                scrobbleDispatcher.dispatchStop(show, episode, lastProgress)
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
        currentlyScrobbling = null
        lastProgressByTitle.clear()
    }

    private suspend fun processPlayingMedia(packageName: String, rawTitle: String, progress: Float?) {
        if (rawTitle == currentlyScrobbling) return
        val candidate = matchTitle(packageName, rawTitle)
        if (candidate == null) {
            if (debugLogMediaSession) {
                DiagnosticLog.debug(TAG, "no match for '$rawTitle' — best confidence null")
            }
            return
        }
        if (candidate.confidence >= AUTO_SCROBBLE_THRESHOLD) {
            autoScrobble(candidate, progress)
            _lastCandidate.value = LastCandidate(candidate, System.currentTimeMillis(), autoScrobbled = true)
        } else if (candidate.confidence >= OVERLAY_THRESHOLD) {
            _pendingConfirmation.emit(candidate)
            _lastCandidate.value = LastCandidate(candidate, System.currentTimeMillis(), autoScrobbled = false)
        } else if (debugLogMediaSession) {
            DiagnosticLog.debug(
                TAG,
                "no match for '$rawTitle' — best confidence ${candidate.confidence}",
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

    internal suspend fun matchTitle(packageName: String, rawTitle: String): ScrobbleCandidate? {
        val episodePattern = Regex("""(?i)S(\d{1,2})E(\d{1,2})""")
        val match = episodePattern.find(rawTitle)
        val showTitle = if (match != null) rawTitle.substringBefore(match.value).trim() else rawTitle
        val season = match?.groupValues?.get(1)?.toIntOrNull()
        val episode = match?.groupValues?.get(2)?.toIntOrNull()
        if (showTitle.isBlank()) return null

        val cachedShows = watchedShowSource.getCachedShows()
        if (cachedShows.isNotEmpty()) {
            val bestCacheMatch = cachedShows.maxByOrNull { fuzzyScore(it.show.title, showTitle) }
            val cacheScore = bestCacheMatch?.let { fuzzyScore(it.show.title, showTitle) } ?: 0f
            if (cacheScore >= 0.70f && bestCacheMatch != null) {
                val matchedEpisode = resolveEpisode(season, episode, bestCacheMatch, cacheScore)
                return ScrobbleCandidate(
                    packageName = packageName,
                    mediaTitle = rawTitle,
                    confidence = cacheScore,
                    matchedShow = bestCacheMatch.show,
                    matchedEpisode = matchedEpisode
                )
            }
        }

        val tmdbApiKey = watchedShowSource.getTmdbApiKey() ?: return null
        return try {
            val tmdbResults = tmdbApiService.searchTv(showTitle, tmdbApiKey).results
            val bestTmdbMatch = tmdbResults.maxByOrNull { fuzzyScore(it.name, showTitle) }
            val tmdbScore = bestTmdbMatch?.let { fuzzyScore(it.name, showTitle) } ?: 0f
            if (tmdbScore < 0.50f || bestTmdbMatch == null) return null
            ScrobbleCandidate(
                packageName = packageName,
                mediaTitle = rawTitle,
                confidence = tmdbScore,
                matchedShow = TraktShow(
                    title = bestTmdbMatch.name,
                    year = bestTmdbMatch.first_air_date?.take(4)?.toIntOrNull(),
                    ids = TraktIds(tmdb = bestTmdbMatch.id)
                ),
                matchedEpisode = if (season != null && episode != null)
                    TraktEpisode(season = season, number = episode) else null
            )
        } catch (e: Exception) {
            Log.w(TAG, "TMDB search failed for '$showTitle'", e)
            null
        }
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

    suspend fun autoScrobble(candidate: ScrobbleCandidate, progress: Float? = null) {
        val show = candidate.matchedShow ?: return
        val episode = candidate.matchedEpisode ?: return
        scrobbleDispatcher.dispatchStart(show, episode, progress ?: 0f)
        currentlyScrobbling = candidate.mediaTitle
        Log.i(TAG, "Scrobble started: ${show.title} S${episode.season}E${episode.number}")
    }

    internal suspend fun handleScrobblePause(rawTitle: String, progress: Float? = null) {
        if (rawTitle != currentlyScrobbling) return
        val candidate = matchTitle("", rawTitle) ?: return
        val show = candidate.matchedShow ?: return
        val episode = candidate.matchedEpisode ?: return
        scrobbleDispatcher.dispatchPause(show, episode, progress ?: 50f)
        Log.i(TAG, "Scrobble paused: ${show.title}")
    }

    internal suspend fun handleScrobbleStop(rawTitle: String, progress: Float? = null) {
        if (rawTitle != currentlyScrobbling) return
        if (progress == null) {
            Log.w(TAG, "Scrobble stop skipped — playback position/duration unavailable for '$rawTitle'")
            currentlyScrobbling = null
            return
        }
        val candidate = matchTitle("", rawTitle) ?: return
        val show = candidate.matchedShow ?: return
        val episode = candidate.matchedEpisode ?: return
        scrobbleDispatcher.dispatchStop(show, episode, progress)
        currentlyScrobbling = null
        Log.i(TAG, "Scrobble stopped: ${show.title} S${episode.season}E${episode.number}")
    }
}
