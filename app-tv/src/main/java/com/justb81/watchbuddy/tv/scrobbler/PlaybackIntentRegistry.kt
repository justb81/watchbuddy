package com.justb81.watchbuddy.tv.scrobbler

import com.justb81.watchbuddy.core.scrobbler.PlaybackIntent
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntentProvider
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntentStats
import com.justb81.watchbuddy.tv.di.ApplicationScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TV-local, in-memory store for Watch-Now intents captured when the user taps a provider chip
 * on the ShowDetailScreen. Implements [PlaybackIntentProvider] so [MediaSessionScrobbler]
 * can consult it during Phase 0 without a direct dependency on this TV-specific class.
 *
 * Last-write-wins per [PlaybackIntent.providerPackageName]: tapping "Watch on Disney+" for
 * show A then 30 s later for show B (same app) keeps only B. Different packages coexist
 * independently.
 *
 * No persistence: TTL is 10 min, which is shorter than any realistic TV process-restart
 * cycle, so a [ConcurrentHashMap] is sufficient.
 */
@Singleton
class PlaybackIntentRegistry @Inject constructor(
    @ApplicationScope appScope: CoroutineScope,
) : PlaybackIntentProvider {

    private val store = ConcurrentHashMap<String, PlaybackIntent>()

    init {
        appScope.launch {
            while (true) {
                delay(CLEANUP_INTERVAL_MS)
                val cutoff = System.currentTimeMillis() - TTL_MS
                store.entries.removeIf { it.value.capturedAtMs < cutoff }
            }
        }
    }

    private val hitsCount = AtomicInteger(0)
    private val fallthroughsCount = AtomicInteger(0)
    private val overriddenCount = AtomicInteger(0)

    override fun record(intent: PlaybackIntent) {
        store[intent.providerPackageName] = intent
    }

    override fun peek(packageName: String): PlaybackIntent? {
        val candidate = store[packageName] ?: return null
        val nowMs = System.currentTimeMillis()
        if (nowMs - candidate.capturedAtMs > TTL_MS) {
            store.remove(packageName, candidate)
            return null
        }
        return candidate
    }

    override fun consumeIntent(packageName: String) {
        store.remove(packageName)
    }

    override fun recordHit() {
        hitsCount.incrementAndGet()
    }

    override fun recordFallthrough() {
        fallthroughsCount.incrementAndGet()
    }

    /** Increments the channel-surfing override counter. */
    fun recordOverriddenByManualMark() {
        overriddenCount.incrementAndGet()
    }

    override fun intentStats(): PlaybackIntentStats = PlaybackIntentStats(
        hits = hitsCount.get(),
        fallthroughs = fallthroughsCount.get(),
        overriddenByManualMark = overriddenCount.get(),
    )

    companion object {
        /** Intents older than 10 minutes are evicted from the registry. */
        const val TTL_MS = 10 * 60_000L

        /** How often the background cleanup coroutine sweeps for expired entries. */
        private const val CLEANUP_INTERVAL_MS = 5 * 60_000L
    }
}
