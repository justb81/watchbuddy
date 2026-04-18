package com.justb81.watchbuddy.tv.scrobbler

import android.util.Log
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.scrobbler.ScrobbleDispatcher
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.discovery.PhoneScrobbleRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TV implementation of [ScrobbleDispatcher].
 *
 * Fans scrobble events in parallel to every available connected phone's HTTP API.
 * Each phone records the episode on its own Trakt account. A failure for one phone
 * does not block scrobbling for the others.
 */
@Singleton
class TvScrobbleDispatcher @Inject constructor(
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val phoneApiClientFactory: PhoneApiClientFactory
) : ScrobbleDispatcher {

    companion object {
        private const val TAG = "TvScrobbleDispatcher"
        private const val PRESENCE_STALENESS_MS = 2 * 60_000L
    }

    private fun availablePhones(): List<PhoneDiscoveryManager.DiscoveredPhone> {
        val now = System.currentTimeMillis()
        return phoneDiscovery.discoveredPhones.value
            .filter { it.capability?.isAvailable == true }
            .filter { now - it.lastSuccessfulCheck < PRESENCE_STALENESS_MS }
    }

    override suspend fun dispatchStart(show: TraktShow, episode: TraktEpisode, progress: Float) {
        val phones = availablePhones()
        if (phones.isEmpty()) {
            Log.w(TAG, "No phones available — scrobble start skipped")
            return
        }
        val request = PhoneScrobbleRequest(show = show, episode = episode, progress = progress)
        coroutineScope {
            phones.forEach { phone ->
                launch {
                    try {
                        phoneApiClientFactory.createClient(phone.baseUrl).scrobbleStart(request)
                        Log.i(TAG, "Scrobble start via ${phone.baseUrl}: ${show.title} S${episode.season}E${episode.number}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Scrobble start failed for ${phone.baseUrl}", e)
                    }
                }
            }
        }
    }

    override suspend fun dispatchPause(show: TraktShow, episode: TraktEpisode, progress: Float) {
        val phones = availablePhones()
        if (phones.isEmpty()) return
        val request = PhoneScrobbleRequest(show = show, episode = episode, progress = progress)
        coroutineScope {
            phones.forEach { phone ->
                launch {
                    try {
                        phoneApiClientFactory.createClient(phone.baseUrl).scrobblePause(request)
                        Log.i(TAG, "Scrobble pause via ${phone.baseUrl}: ${show.title}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Scrobble pause failed for ${phone.baseUrl}", e)
                    }
                }
            }
        }
    }

    override suspend fun dispatchStop(show: TraktShow, episode: TraktEpisode, progress: Float) {
        val phones = availablePhones()
        if (phones.isEmpty()) return
        val request = PhoneScrobbleRequest(show = show, episode = episode, progress = progress)
        coroutineScope {
            phones.forEach { phone ->
                launch {
                    try {
                        phoneApiClientFactory.createClient(phone.baseUrl).scrobbleStop(request)
                        Log.i(TAG, "Scrobble stop via ${phone.baseUrl}: ${show.title} S${episode.season}E${episode.number}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Scrobble stop failed for ${phone.baseUrl}", e)
                    }
                }
            }
        }
    }
}
