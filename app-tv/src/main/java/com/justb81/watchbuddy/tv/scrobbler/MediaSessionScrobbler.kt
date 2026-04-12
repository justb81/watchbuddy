package com.justb81.watchbuddy.tv.scrobbler

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.trakt.TraktApiService
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
    @ApplicationContext private val context: Context,
    private val traktApi: TraktApiService,
    private val showCacheProvider: ShowCacheProvider
) {
    companion object {
        const val AUTO_SCROBBLE_THRESHOLD = 0.95f
        const val OVERLAY_THRESHOLD = 0.70f
        const val CACHE_CONFIDENCE_THRESHOLD = 0.85f
        const val MIN_MATCH_THRESHOLD = 0.50f
    }

    private val _pendingConfirmation = MutableSharedFlow<ScrobbleCandidate>()
    val pendingConfirmation: SharedFlow<ScrobbleCandidate> = _pendingConfirmation

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    fun startListening(notificationListenerComponent: ComponentName) {
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

                        if (playbackState.state == PlaybackState.STATE_PLAYING) {
                            val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                                ?: return@forEach

                            processPlayingMedia(packageName, title)
                        }
                    }
                } catch (e: Exception) {
                    // Session access denied or device quirk — continue polling
                }
                delay(30_000) // Poll every 30 seconds
            }
        }
    }

    fun stopListening() {
        pollingJob?.cancel()
    }

    private suspend fun processPlayingMedia(packageName: String, rawTitle: String) {
        val candidate = matchTitleToTrakt(packageName, rawTitle)
        if (candidate != null) {
            when {
                candidate.confidence >= AUTO_SCROBBLE_THRESHOLD -> autoScrobble(candidate)
                candidate.confidence >= OVERLAY_THRESHOLD -> _pendingConfirmation.emit(candidate)
                // < OVERLAY_THRESHOLD → ignore
            }
        }
    }

    /**
     * Fuzzy-match the media title to a show+episode in the user's Trakt history.
     * Parses common patterns like "Show Title S02E04", "Show Title · Season 2", etc.
     *
     * Strategy:
     *   1. Extract episode info (SxxExx pattern) from raw title
     *   2. Search local cache (ShowCacheProvider) with fuzzy score
     *   3. If cache score < 0.85 → fall back to Trakt API searchShow()
     *   4. Return null if best score < 0.50
     */
    internal suspend fun matchTitleToTrakt(packageName: String, rawTitle: String): ScrobbleCandidate? {
        val parsed = parseEpisodeInfo(rawTitle)
        if (parsed.showTitle.isBlank()) return null

        // 1. Search local cache
        val cacheResult = searchCache(parsed.showTitle)

        // 2. If cache hit is strong enough, use it
        if (cacheResult != null && cacheResult.second >= CACHE_CONFIDENCE_THRESHOLD) {
            return buildCandidate(packageName, rawTitle, cacheResult.first, cacheResult.second, parsed)
        }

        // 3. Trakt API fallback
        val apiResult = searchTraktApi(parsed.showTitle)

        // 4. Pick the best result between cache and API
        val best = listOfNotNull(cacheResult, apiResult).maxByOrNull { it.second }
            ?: return null

        if (best.second < MIN_MATCH_THRESHOLD) return null

        return buildCandidate(packageName, rawTitle, best.first, best.second, parsed)
    }

    private data class ParsedEpisodeInfo(
        val showTitle: String,
        val season: Int?,
        val episode: Int?
    )

    private fun parseEpisodeInfo(rawTitle: String): ParsedEpisodeInfo {
        val episodePattern = Regex("""(?i)S(\d{1,2})E(\d{1,2})""")
        val match = episodePattern.find(rawTitle)

        val showTitle = if (match != null) rawTitle.substringBefore(match.value).trim() else rawTitle
        val season = match?.groupValues?.get(1)?.toIntOrNull()
        val episode = match?.groupValues?.get(2)?.toIntOrNull()

        return ParsedEpisodeInfo(showTitle, season, episode)
    }

    private fun searchCache(showTitle: String): Pair<TraktShow, Float>? {
        val cachedShows = showCacheProvider.getCachedShows()
        if (cachedShows.isEmpty()) return null

        return cachedShows
            .map { entry -> entry.show to FuzzyMatcher.fuzzyScore(showTitle, entry.show.title) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= MIN_MATCH_THRESHOLD }
    }

    private suspend fun searchTraktApi(showTitle: String): Pair<TraktShow, Float>? {
        return try {
            val results = traktApi.searchShow(bearer = "", query = showTitle, limit = 5)
            results
                .mapNotNull { result ->
                    val show = result.show ?: return@mapNotNull null
                    show to FuzzyMatcher.fuzzyScore(showTitle, show.title)
                }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= MIN_MATCH_THRESHOLD }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildCandidate(
        packageName: String,
        rawTitle: String,
        show: TraktShow,
        score: Float,
        parsed: ParsedEpisodeInfo
    ): ScrobbleCandidate {
        return ScrobbleCandidate(
            packageName = packageName,
            mediaTitle = rawTitle,
            confidence = score,
            matchedShow = show,
            matchedEpisode = if (parsed.season != null && parsed.episode != null)
                TraktEpisode(season = parsed.season, number = parsed.episode)
            else null
        )
    }

    private suspend fun autoScrobble(candidate: ScrobbleCandidate) {
        // TODO: retrieve stored access_token from secure storage and call traktApi.scrobbleStart()
    }
}
