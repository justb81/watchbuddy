package com.justb81.watchbuddy.tv.scrobbler

import android.util.Log
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.AmbiguousScrobbleEvent
import com.justb81.watchbuddy.core.model.PhoneAddToLibraryRequest
import com.justb81.watchbuddy.core.model.ScrobbleAction
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
import java.util.concurrent.ConcurrentHashMap
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

        /** At-most-once delivery window for ambiguous prompts. Keys older than this are evicted. */
        internal const val AMBIGUOUS_KEY_TTL_MS = 30 * 60 * 1_000L
    }

    internal data class QueuedScrobble(
        val action: ScrobbleAction,
        val show: TraktShow,
        val episode: TraktEpisode,
        val progress: Float,
        val capturedAtMs: Long,
    )

    private val queueMutex = Mutex()
    private val pendingQueue = ArrayDeque<QueuedScrobble>()

    /**
     * Session keys for which an ambiguous prompt has already been dispatched, mapped to the
     * timestamp (ms) at which the key was first registered.
     *
     * At-most-once semantics: the key is kept even when no phones are available so a transient
     * phone outage never causes the same overlay to appear twice. Entries are evicted lazily
     * after [AMBIGUOUS_KEY_TTL_MS] (30 min) so the map does not grow without bound.
     * Explicit removal happens via [clearResolvedPrompt] once the phone confirms resolution.
     */
    private val dispatchedAmbiguousKeys: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /**
     * TMDB IDs for which the user has already confirmed an add-to-library this session.
     * Prevents duplicate Trakt history writes when the overlay is confirmed more than once
     * for the same unknown show. Cleared on service restart.
     */
    private val confirmedUnknownTmdbIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

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

    /**
     * Fans an add-to-library request to every reachable phone in parallel so the episode
     * is written to each user's Trakt history immediately after overlay confirmation.
     *
     * Idempotent per TMDB id — a second confirmation for the same unknown show within the
     * same session is silently dropped to prevent duplicate Trakt history entries.
     * Per-phone failures are logged and do not block other phones.
     */
    override suspend fun dispatchAddToLibrary(show: TraktShow, episode: TraktEpisode) {
        val tmdbId = show.ids.tmdb ?: run {
            DiagnosticLog.warn(TAG, "add-to-library: skipped — show has no TMDB id '${show.title}'")
            return
        }
        if (!confirmedUnknownTmdbIds.add(tmdbId)) {
            DiagnosticLog.event(TAG, "add-to-library: dedup skip tmdbId=$tmdbId '${show.title}'")
            return
        }
        DiagnosticLog.event(
            TAG,
            "add-to-library-confirmed: '${show.title}' S${episode.season}E${episode.number} tmdbId=$tmdbId",
        )
        val phones = availablePhones()
        if (phones.isEmpty()) {
            DiagnosticLog.warn(TAG, "add-to-library: no phones reachable for tmdbId=$tmdbId '${show.title}'")
            return
        }
        coroutineScope {
            phones.forEach { phone ->
                launch {
                    try {
                        val client = phoneApiClientFactory.createClient(phone.baseUrl)
                        client.addShowToLibrary(PhoneAddToLibraryRequest(show = show, episode = episode))
                        Log.i(TAG, "add-to-library-ok ${phone.baseUrl}: ${show.title} S${episode.season}E${episode.number}")
                        DiagnosticLog.event(TAG, "add-to-library-ok phone=${phone.baseUrl} '${show.title}'")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        Log.e(TAG, "add-to-library failed for ${phone.baseUrl}", e)
                        DiagnosticLog.warn(TAG, "add-to-library-err phone=${phone.baseUrl}: ${e.message}")
                    } catch (e: HttpException) {
                        Log.e(TAG, "add-to-library HTTP error for ${phone.baseUrl}: ${e.code()}", e)
                        DiagnosticLog.warn(TAG, "add-to-library-err phone=${phone.baseUrl} http=${e.code()}")
                    }
                }
            }
        }
    }

    /**
     * Fans [event] to every reachable phone in parallel. Idempotent on [AmbiguousScrobbleEvent.sessionKey]:
     * if the same session key has been dispatched already (and not yet resolved via
     * [clearResolvedPrompt]), this is a no-op so repeated poll cycles don't stack notifications.
     *
     * At-most-once delivery: the key is kept even when no phones are reachable at dispatch time.
     * A transient outage must not cause the phone to display the same overlay again when it
     * reconnects. Stale keys are evicted lazily after [AMBIGUOUS_KEY_TTL_MS].
     */
    suspend fun dispatchAmbiguous(event: AmbiguousScrobbleEvent) {
        val now = clock()
        // Lazily evict keys whose TTL has elapsed so the map does not grow without bound.
        dispatchedAmbiguousKeys.entries.removeIf { (_, registeredAtMs) ->
            now - registeredAtMs > AMBIGUOUS_KEY_TTL_MS
        }
        // putIfAbsent returns null when the key is new (successfully inserted) and the
        // existing timestamp when the key was already present.
        if (dispatchedAmbiguousKeys.putIfAbsent(event.sessionKey, now) != null) return
        val phones = availablePhones()
        if (phones.isEmpty()) {
            DiagnosticLog.warn(TAG, "ambiguous prompt held — no phones reachable for '${event.sessionKey}', will not retry")
            return
        }
        coroutineScope {
            phones.forEach { phone ->
                launch {
                    try {
                        val client = phoneApiClientFactory.createClient(phone.baseUrl)
                        client.scrobblePrompt(event)
                        Log.i(TAG, "Ambiguous prompt sent to ${phone.baseUrl}: ${event.sessionKey}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        Log.e(TAG, "Ambiguous prompt failed for ${phone.baseUrl}", e)
                    } catch (e: HttpException) {
                        Log.e(TAG, "Ambiguous prompt HTTP error for ${phone.baseUrl}: ${e.code()}", e)
                    }
                }
            }
        }
    }

    /** Call when the phone reports that [sessionKey] has been resolved, so it won't be re-dispatched. */
    fun clearResolvedPrompt(sessionKey: String) {
        dispatchedAmbiguousKeys.remove(sessionKey)
    }
}
