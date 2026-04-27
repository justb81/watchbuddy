package com.justb81.watchbuddy.tv.scrobbler

import android.util.Log
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.scrobbler.ScrobbleDispatcher
import com.justb81.watchbuddy.tv.discovery.DiscoveryConstants
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.discovery.PhoneScrobbleRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * When no phones are reachable at dispatch time, the event is held in a bounded
 * in-memory queue (max [QUEUE_MAX_SIZE] entries, drop-oldest). The queue is drained
 * automatically on the next [PhoneDiscoveryManager.discoveredPhones] emission that
 * contains at least one reachable phone, provided the event is still within
 * [QUEUE_TTL_MS].
 */
@Singleton
class TvScrobbleDispatcher(
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val phoneApiClientFactory: PhoneApiClientFactory,
    private val replayScope: CoroutineScope,
    private val clock: () -> Long,
) : ScrobbleDispatcher {

    @Inject
    constructor(
        phoneDiscovery: PhoneDiscoveryManager,
        phoneApiClientFactory: PhoneApiClientFactory,
    ) : this(
        phoneDiscovery,
        phoneApiClientFactory,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        System::currentTimeMillis,
    )

    companion object {
        private const val TAG = "TvScrobbleDispatcher"
        internal const val QUEUE_MAX_SIZE = 16
        internal const val QUEUE_TTL_MS = 5 * 60 * 1_000L
    }

    internal enum class ScrobbleAction { START, PAUSE, STOP }

    internal data class QueuedScrobble(
        val action: ScrobbleAction,
        val show: TraktShow,
        val episode: TraktEpisode,
        val progress: Float,
        val capturedAtMs: Long,
    )

    private val queueMutex = Mutex()
    private val pendingQueue = ArrayDeque<QueuedScrobble>()

    init {
        replayScope.launch {
            phoneDiscovery.discoveredPhones.collect { _ ->
                if (availablePhones().isNotEmpty()) {
                    drainQueue()
                }
            }
        }
    }

    private fun availablePhones(): List<PhoneDiscoveryManager.DiscoveredPhone> {
        val now = clock()
        return phoneDiscovery.discoveredPhones.value
            .filter { it.capability?.isAvailable == true }
            .filter { now - it.lastSuccessfulCheck < DiscoveryConstants.PRESENCE_STALENESS_MS }
    }

    private suspend fun drainQueue() {
        val now = clock()
        val toReplay = queueMutex.withLock {
            val fresh = pendingQueue.filter { now - it.capturedAtMs <= QUEUE_TTL_MS }
            pendingQueue.clear()
            fresh
        }
        if (toReplay.isEmpty()) return
        toReplay.forEach { q ->
            dispatchToPhones(q.action, q.show, q.episode, q.progress)
        }
        DiagnosticLog.event(TAG, "replayed ${toReplay.size} queued scrobbles")
    }

    private suspend fun dispatchToPhones(
        action: ScrobbleAction,
        show: TraktShow,
        episode: TraktEpisode,
        progress: Float,
    ) {
        val phones = availablePhones()
        if (phones.isEmpty()) return
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

    private suspend fun dispatch(
        action: ScrobbleAction,
        show: TraktShow,
        episode: TraktEpisode,
        progress: Float,
    ) {
        val phones = availablePhones()
        if (phones.isEmpty()) {
            DiagnosticLog.warn(
                TAG,
                "scrobble ${action.name.lowercase()} dropped — no phones reachable",
            )
            val queued = QueuedScrobble(action, show, episode, progress, clock())
            queueMutex.withLock {
                if (pendingQueue.size >= QUEUE_MAX_SIZE) {
                    pendingQueue.removeFirst()
                }
                pendingQueue.addLast(queued)
            }
            return
        }
        dispatchToPhones(action, show, episode, progress)
    }

    override suspend fun dispatchStart(show: TraktShow, episode: TraktEpisode, progress: Float) =
        dispatch(ScrobbleAction.START, show, episode, progress)

    override suspend fun dispatchPause(show: TraktShow, episode: TraktEpisode, progress: Float) =
        dispatch(ScrobbleAction.PAUSE, show, episode, progress)

    override suspend fun dispatchStop(show: TraktShow, episode: TraktEpisode, progress: Float) =
        dispatch(ScrobbleAction.STOP, show, episode, progress)
}
