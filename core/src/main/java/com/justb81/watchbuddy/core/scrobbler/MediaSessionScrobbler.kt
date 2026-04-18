package com.justb81.watchbuddy.core.scrobbler

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

    private val _pendingConfirmation = MutableSharedFlow<ScrobbleCandidate>()
    val pendingConfirmation: SharedFlow<ScrobbleCandidate> = _pendingConfirmation

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

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
                        val progress = computeProgress(playbackState, metadata)
                        when (playbackState.state) {
                            PlaybackState.STATE_PLAYING -> processPlayingMedia(packageName, title, progress)
                            PlaybackState.STATE_PAUSED -> handleScrobblePause(title, progress)
                            PlaybackState.STATE_STOPPED,
                            PlaybackState.STATE_NONE -> handleScrobbleStop(title, progress)
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

    fun stopListening() {
        pollingJob?.cancel()
        scope.cancel()
    }

    private suspend fun processPlayingMedia(packageName: String, rawTitle: String, progress: Float?) {
        if (rawTitle == currentlyScrobbling) return
        val candidate = matchTitle(packageName, rawTitle) ?: return
        if (candidate.confidence >= AUTO_SCROBBLE_THRESHOLD) {
            autoScrobble(candidate, progress)
        } else if (candidate.confidence >= OVERLAY_THRESHOLD) {
            _pendingConfirmation.emit(candidate)
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
