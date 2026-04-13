package com.justb81.watchbuddy.tv.scrobbler

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.trakt.ScrobbleBody
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.tv.data.TvShowCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens to active MediaSessions on the TV and automatically scrobbles to Trakt.
 *
 * How it works:
 *   1. MediaSessionManager.getActiveSessions() returns all active sessions
 *   2. Extract package name + media title from session metadata
 *   3. Fuzzy-match title against user's Trakt watchlist (local cache first, then API)
 *   4. Confidence ≥ 0.95 → auto-scrobble; 0.70–0.95 → emit for UI confirmation; < 0.70 → ignore
 *   5. On playback stop/pause → call Trakt scrobble/stop
 */
@Singleton
class MediaSessionScrobbler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val traktApi: TraktApiService,
    private val tvShowCache: TvShowCache,
    private val tvTokenCache: TvTokenCache
) {
    companion object {
        private const val TAG = "MediaSessionScrobbler"
        private const val AUTO_SCROBBLE_THRESHOLD = 0.95f
        private const val OVERLAY_THRESHOLD = 0.70f
    }

    private val _pendingConfirmation = MutableSharedFlow<ScrobbleCandidate>()
    val pendingConfirmation: SharedFlow<ScrobbleCandidate> = _pendingConfirmation

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    /** Track which media title is currently being scrobbled to avoid duplicate starts. */
    private var currentlyScrobbling: String? = null

    fun startListening(notificationListenerComponent: ComponentName) {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as MediaSessionManager

        pollingJob = scope.launch {
            while (isActive) {
                try {
                    val sessions = sessionManager.getActiveSessions(notificationListenerComponent)
                    sessions.forEach { controller ->
                        val packageName = controller.packageName
                        val metadata = controller.metadata ?: return@forEach
                        val playbackState = controller.playbackState ?: return@forEach

                        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                            ?: return@forEach

                        when (playbackState.state) {
                            PlaybackState.STATE_PLAYING -> processPlayingMedia(packageName, title)
                            PlaybackState.STATE_PAUSED -> handleScrobblePause(title)
                            PlaybackState.STATE_STOPPED,
                            PlaybackState.STATE_NONE -> handleScrobbleStop(title)
                            else -> { /* no-op */ }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Session polling error", e)
                }
                delay(30_000) // Poll every 30 seconds
            }
        }
    }

    fun stopListening() {
        pollingJob?.cancel()
        scope.cancel()
    }

    private suspend fun processPlayingMedia(packageName: String, rawTitle: String) {
        if (rawTitle == currentlyScrobbling) return // already handling this title

        val candidate = matchTitleToTrakt(packageName, rawTitle) ?: return

        if (candidate.confidence >= AUTO_SCROBBLE_THRESHOLD) {
            autoScrobble(candidate)
        } else if (candidate.confidence >= OVERLAY_THRESHOLD) {
            _pendingConfirmation.emit(candidate)
        }
        // confidence < 0.70 → too uncertain, don't scrobble
    }

    // ── Fuzzy Matching (Issue #15) ───────────────────────────────────────────

    /**
     * Fuzzy-match the media title to a show+episode in the user's Trakt history.
     * Searches the local show cache first, then falls back to the Trakt search API.
     */
    private suspend fun matchTitleToTrakt(packageName: String, rawTitle: String): ScrobbleCandidate? {
        val episodePattern = Regex("""(?i)S(\d{1,2})E(\d{1,2})""")
        val match = episodePattern.find(rawTitle)

        val showTitle = if (match != null) rawTitle.substringBefore(match.value).trim() else rawTitle
        val season = match?.groupValues?.get(1)?.toIntOrNull()
        val episode = match?.groupValues?.get(2)?.toIntOrNull()

        if (showTitle.isBlank()) return null

        // 1. Search local cache first
        val cachedShows = tvShowCache.getCachedShows()
        if (cachedShows.isNotEmpty()) {
            val bestCacheMatch = cachedShows.maxByOrNull { fuzzyScore(it.show.title, showTitle) }
            val cacheScore = bestCacheMatch?.let { fuzzyScore(it.show.title, showTitle) } ?: 0f

            if (cacheScore >= 0.70f && bestCacheMatch != null) {
                return ScrobbleCandidate(
                    packageName = packageName,
                    mediaTitle = rawTitle,
                    confidence = cacheScore,
                    matchedShow = bestCacheMatch.show,
                    matchedEpisode = if (season != null && episode != null)
                        TraktEpisode(season = season, number = episode) else null
                )
            }
        }

        // 2. API fallback when cache has no good match
        val token = tvTokenCache.getToken() ?: return null
        return try {
            val apiResults = traktApi.searchShow("Bearer $token", showTitle)
            val apiMatch = apiResults
                .filter { it.show != null }
                .maxByOrNull { fuzzyScore(it.show!!.title, showTitle) }
            val apiScore = apiMatch?.show?.let { fuzzyScore(it.title, showTitle) } ?: 0f

            if (apiScore < 0.50f || apiMatch?.show == null) return null

            ScrobbleCandidate(
                packageName = packageName,
                mediaTitle = rawTitle,
                confidence = apiScore,
                matchedShow = apiMatch.show,
                matchedEpisode = if (season != null && episode != null)
                    TraktEpisode(season = season, number = episode) else null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Trakt search API failed for '$showTitle'", e)
            null
        }
    }

    /**
     * Normalizes a title for comparison: lowercases, strips special chars and
     * leading articles ("the"), collapses whitespace.
     */
    internal fun normalize(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\bthe\\b"), "")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Computes a fuzzy similarity score between two titles (0.0–1.0).
     * Uses exact match → prefix match → Levenshtein distance.
     */
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

    // ── Scrobble API (Issue #16) ─────────────────────────────────────────────

    /**
     * Sends a scrobble/start to Trakt for the given candidate.
     * Called automatically for high-confidence matches or via [ScrobbleViewModel] after user confirmation.
     */
    suspend fun autoScrobble(candidate: ScrobbleCandidate) {
        val token = tvTokenCache.getToken()
        if (token == null) {
            Log.w(TAG, "No access token available — scrobble skipped")
            return
        }

        val show = candidate.matchedShow ?: return
        val episode = candidate.matchedEpisode ?: return

        try {
            traktApi.scrobbleStart(
                bearer = "Bearer $token",
                body = ScrobbleBody(
                    show = show,
                    episode = episode,
                    progress = 0f
                )
            )
            currentlyScrobbling = candidate.mediaTitle
            Log.i(TAG, "Scrobble started: ${show.title} S${episode.season}E${episode.number}")
        } catch (e: Exception) {
            Log.e(TAG, "Scrobble start failed", e)
        }
    }

    private suspend fun handleScrobblePause(rawTitle: String) {
        if (rawTitle != currentlyScrobbling) return

        val token = tvTokenCache.getToken() ?: return
        val candidate = matchTitleToTrakt("", rawTitle) ?: return
        val show = candidate.matchedShow ?: return
        val episode = candidate.matchedEpisode ?: return

        try {
            traktApi.scrobblePause(
                bearer = "Bearer $token",
                body = ScrobbleBody(show = show, episode = episode, progress = 50f)
            )
            Log.i(TAG, "Scrobble paused: ${show.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Scrobble pause failed", e)
        }
    }

    private suspend fun handleScrobbleStop(rawTitle: String) {
        if (rawTitle != currentlyScrobbling) return

        val token = tvTokenCache.getToken() ?: return
        val candidate = matchTitleToTrakt("", rawTitle) ?: return
        val show = candidate.matchedShow ?: return
        val episode = candidate.matchedEpisode ?: return

        try {
            traktApi.scrobbleStop(
                bearer = "Bearer $token",
                body = ScrobbleBody(show = show, episode = episode, progress = 100f)
            )
            currentlyScrobbling = null
            Log.i(TAG, "Scrobble stopped (watched): ${show.title} S${episode.season}E${episode.number}")
        } catch (e: Exception) {
            Log.e(TAG, "Scrobble stop failed", e)
        }
    }
}
