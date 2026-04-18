package com.justb81.watchbuddy.tv.scrobbler

import android.util.Log
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.scrobbler.ScrobbleDispatcher
import com.justb81.watchbuddy.tv.discovery.DiscoveryConstants
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.discovery.PhoneScrobbleRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
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
    }

    internal enum class ScrobbleAction { START, PAUSE, STOP }

    private fun availablePhones(): List<PhoneDiscoveryManager.DiscoveredPhone> {
        val now = System.currentTimeMillis()
        return phoneDiscovery.discoveredPhones.value
            .filter { it.capability?.isAvailable == true }
            .filter { now - it.lastSuccessfulCheck < DiscoveryConstants.PRESENCE_STALENESS_MS }
    }

    private suspend fun dispatch(
        action: ScrobbleAction,
        show: TraktShow,
        episode: TraktEpisode,
        progress: Float,
    ) {
        val phones = availablePhones()
        if (phones.isEmpty()) {
            Log.w(TAG, "No phones available — scrobble ${action.name.lowercase()} skipped")
            return
        }
        val request = PhoneScrobbleRequest(show = show, episode = episode, progress = progress)
        coroutineScope {
            phones.forEach { phone ->
                launch {
                    try {
                        val client = phoneApiClientFactory.createClient(phone.baseUrl)
                        when (action) {
                            ScrobbleAction.START -> {
                                client.scrobbleStart(request)
                                Log.i(TAG, "Scrobble start via ${phone.baseUrl}: ${show.title} S${episode.season}E${episode.number}")
                            }
                            ScrobbleAction.PAUSE -> {
                                client.scrobblePause(request)
                                Log.i(TAG, "Scrobble pause via ${phone.baseUrl}: ${show.title}")
                            }
                            ScrobbleAction.STOP -> {
                                client.scrobbleStop(request)
                                Log.i(TAG, "Scrobble stop via ${phone.baseUrl}: ${show.title} S${episode.season}E${episode.number}")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        Log.e(TAG, "Scrobble ${action.name.lowercase()} failed for ${phone.baseUrl}", e)
                    } catch (e: HttpException) {
                        Log.e(TAG, "Scrobble ${action.name.lowercase()} HTTP error for ${phone.baseUrl}: ${e.code()}", e)
                    }
                }
            }
        }
    }

    override suspend fun dispatchStart(show: TraktShow, episode: TraktEpisode, progress: Float) =
        dispatch(ScrobbleAction.START, show, episode, progress)

    override suspend fun dispatchPause(show: TraktShow, episode: TraktEpisode, progress: Float) =
        dispatch(ScrobbleAction.PAUSE, show, episode, progress)

    override suspend fun dispatchStop(show: TraktShow, episode: TraktEpisode, progress: Float) =
        dispatch(ScrobbleAction.STOP, show, episode, progress)
}
