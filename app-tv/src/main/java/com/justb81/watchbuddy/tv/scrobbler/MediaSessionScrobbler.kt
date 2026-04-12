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
 *   3. Fuzzy-match title against user's Trakt watchlist
 *   4. Confidence ≥ 0.95 → auto-scrobble; 0.70–0.95 → emit ScrobbleCandidate for UI confirmation
 *   5. On playback stop/pause → call Trakt scrobble/stop
 */
@Singleton
class MediaSessionScrobbler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktApi: TraktApiService
) {
    companion object {
        const val AUTO_SCROBBLE_THRESHOLD = 0.95f
        const val CONFIRMATION_THRESHOLD = 0.70f
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
                candidate.confidence >= CONFIRMATION_THRESHOLD  -> _pendingConfirmation.emit(candidate)
                // Below CONFIRMATION_THRESHOLD → ignore (too uncertain)
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
            matchedShow = TraktShow(title = showTitle, ids = com.justb81.watchbuddy.core.model.TraktIds()),
            matchedEpisode = if (season != null && episode != null)
                TraktEpisode(season = season, number = episode)
            else null
        )
    }

    suspend fun autoScrobble(candidate: ScrobbleCandidate) {
        // TODO: retrieve stored access_token from secure storage and call traktApi.scrobbleStart()
    }
}
