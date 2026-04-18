package com.justb81.watchbuddy.phone.scrobbler

import android.util.Log
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.scrobbler.ScrobbleDispatcher
import com.justb81.watchbuddy.core.trakt.ScrobbleBody
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone implementation of [ScrobbleDispatcher].
 *
 * Calls the Trakt scrobble API directly using the phone's own stored access token.
 * The phone holds Trakt credentials and records the episode on the authenticated user's account.
 */
@Singleton
class PhoneScrobbleDispatcher @Inject constructor(
    private val traktApi: TraktApiService,
    private val tokenRepository: TokenRepository
) : ScrobbleDispatcher {

    companion object {
        private const val TAG = "PhoneScrobbleDispatcher"
    }

    override suspend fun dispatchStart(show: TraktShow, episode: TraktEpisode, progress: Float) {
        val token = tokenRepository.getAccessToken() ?: run {
            Log.w(TAG, "No access token — scrobble start skipped")
            return
        }
        try {
            traktApi.scrobbleStart("Bearer $token", ScrobbleBody(show, episode, progress))
            Log.i(TAG, "Scrobble start recorded: ${show.title} S${episode.season}E${episode.number}")
        } catch (e: Exception) {
            Log.e(TAG, "Scrobble start failed", e)
        }
    }

    override suspend fun dispatchPause(show: TraktShow, episode: TraktEpisode, progress: Float) {
        val token = tokenRepository.getAccessToken() ?: run {
            Log.w(TAG, "No access token — scrobble pause skipped")
            return
        }
        try {
            traktApi.scrobblePause("Bearer $token", ScrobbleBody(show, episode, progress))
            Log.i(TAG, "Scrobble pause recorded: ${show.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Scrobble pause failed", e)
        }
    }

    override suspend fun dispatchStop(show: TraktShow, episode: TraktEpisode, progress: Float) {
        val token = tokenRepository.getAccessToken() ?: run {
            Log.w(TAG, "No access token — scrobble stop skipped")
            return
        }
        try {
            traktApi.scrobbleStop("Bearer $token", ScrobbleBody(show, episode, progress))
            Log.i(TAG, "Scrobble stop recorded: ${show.title} S${episode.season}E${episode.number}")
        } catch (e: Exception) {
            Log.e(TAG, "Scrobble stop failed", e)
        }
    }
}
