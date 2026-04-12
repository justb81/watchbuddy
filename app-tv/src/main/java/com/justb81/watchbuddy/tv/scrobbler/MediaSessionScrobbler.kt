package com.justb81.watchbuddy.tv.scrobbler

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.ScrobbleBody
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.tv.data.TvShowCache
import com.justb81.watchbuddy.tv.network.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaSessionScrobbler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktApi: TraktApiService,
    private val showCache: TvShowCache,
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val phoneApiClientFactory: PhoneApiClientFactory
) {
    companion object {
        const val AUTO_SCROBBLE_THRESHOLD = 0.90f
        private const val TAG = "MediaSessionScrobbler"
    }

    private val _pendingConfirmation = MutableSharedFlow<ScrobbleCandidate>()
    val pendingConfirmation: SharedFlow<ScrobbleCandidate> = _pendingConfirmation

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0L

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
                    Log.w(TAG, "Session polling error", e)
                }
                delay(30_000)
            }
        }
    }

    fun stopListening() {
        pollingJob?.cancel()
    }

    private suspend fun processPlayingMedia(packageName: String, rawTitle: String) {
        val candidate = matchTitleToTrakt(packageName, rawTitle)
        if (candidate != null) {
            if (candidate.confidence >= AUTO_SCROBBLE_THRESHOLD) {
                autoScrobble(candidate)
            } else if (candidate.confidence >= 0.70f) {
                _pendingConfirmation.emit(candidate)
            }
        }
    }

    private suspend fun matchTitleToTrakt(packageName: String, rawTitle: String): ScrobbleCandidate? {
        val episodePattern = Regex("""(?i)S(\d{1,2})E(\d{1,2})""")
        val match = episodePattern.find(rawTitle)

        val showTitle = if (match != null) rawTitle.substringBefore(match.value).trim() else rawTitle
        val season = match?.groupValues?.get(1)?.toIntOrNull()
        val episode = match?.groupValues?.get(2)?.toIntOrNull()

        if (showTitle.isBlank()) return null

        // 1. Search local cache first
        val cachedShows = showCache.getCachedShows()
        val bestCacheMatch = cachedShows.maxByOrNull { fuzzyScore(it.show.title, showTitle) }
        val cacheScore = bestCacheMatch?.let { fuzzyScore(it.show.title, showTitle) } ?: 0f

        if (cacheScore >= 0.70f && bestCacheMatch != null) {
            return ScrobbleCandidate(
                packageName = packageName,
                mediaTitle = rawTitle,
                confidence = cacheScore,
                matchedShow = bestCacheMatch.show,
                matchedEpisode = if (season != null && episode != null)
                    TraktEpisode(season = season, number = episode)
                else null
            )
        }

        // 2. Fallback to Trakt API search
        return try {
            val token = getToken() ?: return null
            val results = traktApi.searchShow("Bearer $token", showTitle)
            val bestResult = results.firstOrNull()?.show ?: return null
            val apiScore = fuzzyScore(bestResult.title, showTitle)

            if (apiScore >= 0.50f) {
                ScrobbleCandidate(
                    packageName = packageName,
                    mediaTitle = rawTitle,
                    confidence = apiScore,
                    matchedShow = bestResult,
                    matchedEpisode = if (season != null && episode != null)
                        TraktEpisode(season = season, number = episode)
                    else null
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Trakt search failed", e)
            null
        }
    }

    suspend fun autoScrobble(candidate: ScrobbleCandidate) {
        val token = getToken() ?: run {
            Log.w(TAG, "No access token — scrobble skipped")
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
            Log.i(TAG, "Scrobble started: ${show.title} S${episode.season}E${episode.number}")
        } catch (e: Exception) {
            Log.e(TAG, "Scrobble failed", e)
        }
    }

    private suspend fun getToken(): String? {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiry) {
            return cachedToken
        }
        val phone = phoneDiscovery.getBestPhone() ?: return null
        if (phone.baseUrl.isBlank()) return null
        return try {
            val client = phoneApiClientFactory.createClient(phone.baseUrl)
            val response = client.getAccessToken()
            cachedToken = response.access_token
            tokenExpiry = System.currentTimeMillis() + (3600 * 1000L) // 1 hour cache
            cachedToken
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch token from phone", e)
            null
        }
    }

    companion object FuzzyMatch {
        fun normalize(title: String): String {
            return title
                .lowercase()
                .replace(Regex("[^a-z0-9\\s]"), "")
                .replace(Regex("\\bthe\\b"), "")
                .trim()
                .replace(Regex("\\s+"), " ")
        }

        fun fuzzyScore(a: String, b: String): Float {
            val normA = normalize(a)
            val normB = normalize(b)

            if (normA == normB) return 1.0f
            if (normA.startsWith(normB) || normB.startsWith(normA)) return 0.95f

            val distance = levenshteinDistance(normA, normB)
            val maxLen = maxOf(normA.length, normB.length)
            if (maxLen == 0) return 0f
            return 1.0f - (distance.toFloat() / maxLen)
        }

        fun levenshteinDistance(a: String, b: String): Int {
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
    }
}
