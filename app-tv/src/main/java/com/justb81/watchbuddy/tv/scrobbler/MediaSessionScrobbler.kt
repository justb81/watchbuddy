package com.justb81.watchbuddy.tv.scrobbler

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.trakt.ScrobbleBody
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
 *   3. Fuzzy-match title against user's Trakt watchlist
 *   4. Confidence ≥ 0.9 → auto-scrobble; < 0.9 → emit ScrobbleCandidate for UI confirmation
 *   5. On playback stop/pause → call Trakt scrobble/stop or scrobble/pause
 *
 * Scrobble lifecycle:
 *   STATE_PLAYING  → scrobble/start (progress = 0)
 *   STATE_PAUSED   → scrobble/pause
 *   STATE_STOPPED / ≥ 80% → scrobble/stop (marks episode as watched)
 */
@Singleton
class MediaSessionScrobbler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktApi: TraktApiService,
    private val tvTokenCache: TvTokenCache
) {
    companion object {
        private const val TAG = "MediaSessionScrobbler"
        const val AUTO_SCROBBLE_THRESHOLD = 0.90f
        private const val WATCHED_THRESHOLD = 80f
    }

    private val _pendingConfirmation = MutableSharedFlow<ScrobbleCandidate>()
    val pendingConfirmation: SharedFlow<ScrobbleCandidate> = _pendingConfirmation

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    /** Currently active scrobble — tracks which episode we told Trakt about. */
    private var activeCandidate: ScrobbleCandidate? = null
    private var lastPlaybackState: Int = PlaybackState.STATE_NONE

    fun startListening(notificationListenerComponent: ComponentName) {
        val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as MediaSessionManager

        pollingJob = scope.launch {
            while (isActive) {
                try {
                    val sessions = sessionManager.getActiveSessions(notificationListenerComponent)
                    if (sessions.isEmpty()) {
                        handlePlaybackStateChange(PlaybackState.STATE_STOPPED, progress = 100f)
                    } else {
                        sessions.forEach { controller ->
                            processSession(controller)
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
        scope.launch { handlePlaybackStateChange(PlaybackState.STATE_STOPPED, progress = 100f) }
    }

    private suspend fun processSession(controller: MediaController) {
        val packageName = controller.packageName
        val metadata = controller.metadata ?: return
        val playbackState = controller.playbackState ?: return
        val state = playbackState.state

        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: return
        val duration = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)
        val position = playbackState.position

        val progress = if (duration > 0) (position.toFloat() / duration * 100f) else 0f

        when (state) {
            PlaybackState.STATE_PLAYING -> {
                val candidate = matchTitleToTrakt(packageName, title)
                if (candidate != null) {
                    if (candidate.confidence >= AUTO_SCROBBLE_THRESHOLD) {
                        if (activeCandidate?.mediaTitle != candidate.mediaTitle) {
                            activeCandidate = candidate
                            autoScrobble(candidate, progress)
                        }
                        lastPlaybackState = state
                    } else if (activeCandidate?.mediaTitle != candidate.mediaTitle) {
                        _pendingConfirmation.emit(candidate)
                    }
                }
            }
            PlaybackState.STATE_PAUSED -> handlePlaybackStateChange(state, progress)
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE ->
                handlePlaybackStateChange(PlaybackState.STATE_STOPPED, progress)
            else -> { /* ignore buffering etc. */ }
        }
    }

    private suspend fun handlePlaybackStateChange(newState: Int, progress: Float) {
        val candidate = activeCandidate ?: return
        if (newState == lastPlaybackState) return

        when (newState) {
            PlaybackState.STATE_PAUSED -> {
                scrobblePause(candidate, progress)
                lastPlaybackState = newState
            }
            PlaybackState.STATE_STOPPED -> {
                scrobbleStop(candidate, progress.coerceAtLeast(WATCHED_THRESHOLD))
                activeCandidate = null
                lastPlaybackState = PlaybackState.STATE_NONE
            }
        }
    }

    /**
     * Fuzzy-match the media title to a show+episode in the user's Trakt history.
     * Parses common patterns like "Show Title S02E04", "Show Title · Season 2", etc.
     */
    private suspend fun matchTitleToTrakt(packageName: String, rawTitle: String): ScrobbleCandidate? {
        // Common patterns: "Breaking Bad S03E07", "The Boys Season 2 Episode 4"
        val episodePattern = Regex("""(?i)S(\d{1,2})E(\d{1,2})""")
        val match = episodePattern.find(rawTitle)

        val showTitle = if (match != null) rawTitle.substringBefore(match.value).trim() else rawTitle
        val season = match?.groupValues?.get(1)?.toIntOrNull()
        val episode = match?.groupValues?.get(2)?.toIntOrNull()

        if (showTitle.isBlank()) return null

        // TODO: search user's local Trakt cache first, then API fallback
        val confidence = if (match != null) 0.85f else 0.50f  // TODO: real fuzzy score

        return ScrobbleCandidate(
            packageName = packageName,
            mediaTitle = rawTitle,
            confidence = confidence,
            matchedShow = TraktShow(title = showTitle, ids = TraktIds()),
            matchedEpisode = if (season != null && episode != null)
                TraktEpisode(season = season, number = episode)
            else null
        )
    }

    private suspend fun autoScrobble(candidate: ScrobbleCandidate, progress: Float) {
        val token = tvTokenCache.getToken() ?: run {
            Log.w(TAG, "No access token available — skipping scrobble")
            return
        }
        val episode = candidate.matchedEpisode ?: run {
            Log.w(TAG, "No matched episode — skipping scrobble")
            return
        }
        val show = candidate.matchedShow ?: return
        try {
            traktApi.scrobbleStart(
                bearer = "Bearer $token",
                body = ScrobbleBody(show = show, episode = episode, progress = progress)
            )
            Log.i(TAG, "Scrobble started: ${show.title} S${episode.season}E${episode.number}")
        } catch (e: Exception) {
            Log.e(TAG, "scrobble/start failed: ${e.message}")
        }
    }

    private suspend fun scrobblePause(candidate: ScrobbleCandidate, progress: Float) {
        val token = tvTokenCache.getToken() ?: return
        val episode = candidate.matchedEpisode ?: return
        val show = candidate.matchedShow ?: return
        try {
            traktApi.scrobblePause(
                bearer = "Bearer $token",
                body = ScrobbleBody(show = show, episode = episode, progress = progress)
            )
            Log.i(TAG, "Scrobble paused: ${show.title} at ${progress.toInt()}%")
        } catch (e: Exception) {
            Log.e(TAG, "scrobble/pause failed: ${e.message}")
        }
    }

    private suspend fun scrobbleStop(candidate: ScrobbleCandidate, progress: Float) {
        val token = tvTokenCache.getToken() ?: return
        val episode = candidate.matchedEpisode ?: return
        val show = candidate.matchedShow ?: return
        try {
            traktApi.scrobbleStop(
                bearer = "Bearer $token",
                body = ScrobbleBody(show = show, episode = episode, progress = progress)
            )
            Log.i(TAG, "Scrobble stopped: ${show.title} S${episode.season}E${episode.number} (${progress.toInt()}%)")
        } catch (e: Exception) {
            Log.e(TAG, "scrobble/stop failed: ${e.message}")
        }
    }
}
