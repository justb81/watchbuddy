package com.justb81.watchbuddy.core.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic TTL-based cache that deduplicates concurrent in-flight fetches per key.
 *
 * @param K Key type. Use [Unit] for single-value caches.
 * @param V Value type returned by the fetcher.
 * @param ttlMillis Time-to-live in milliseconds. An entry is considered stale
 *   after this many milliseconds have elapsed since it was stored.
 * @param clock Supplier of the current time in milliseconds. Override in tests
 *   to control TTL expiry without real-time delays.
 * @param fetcher Suspending function that produces a value for a given key.
 *   Invoked at most once per key per TTL window; concurrent callers with the
 *   same key wait for the first in-flight fetch to complete.
 */
class TimedCachedResource<K, V>(
    private val ttlMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetcher: suspend (K) -> V,
) {
    private data class Entry<V>(val value: V, val storedAtMs: Long)

    private val store = ConcurrentHashMap<K, Entry<V>>()
    private val keyMutexes = ConcurrentHashMap<K, Mutex>()

    /**
     * Returns the cached value for [key] if it was stored within [ttlMillis],
     * or invokes [fetcher] to produce a fresh value and caches it before returning.
     *
     * Concurrent calls with the same [key] are serialised: only one [fetcher]
     * invocation runs at a time per key; all others wait and receive the same result.
     *
     * If [fetcher] throws, the exception is propagated to the caller and no
     * value is cached, so the next call for the same key will retry.
     */
    suspend fun get(key: K): V {
        val now = clock()
        store[key]?.let { entry ->
            if (now - entry.storedAtMs < ttlMillis) return entry.value
        }
        val mutex = keyMutexes.getOrPut(key) { Mutex() }
        return mutex.withLock {
            val now2 = clock()
            store[key]?.let { entry ->
                if (now2 - entry.storedAtMs < ttlMillis) return@withLock entry.value
            }
            val fresh = fetcher(key)
            store[key] = Entry(fresh, clock())
            fresh
        }
    }

    /**
     * Removes the cached entry for [key]. The next [get] call for this key will
     * invoke [fetcher] regardless of the remaining TTL.
     */
    suspend fun invalidate(key: K) {
        val mutex = keyMutexes.getOrPut(key) { Mutex() }
        mutex.withLock { store.remove(key) }
    }

    /**
     * Removes all cached entries. Every subsequent [get] call will invoke [fetcher].
     */
    suspend fun invalidateAll() {
        val allKeys = store.keys.toList()
        for (key in allKeys) {
            invalidate(key)
        }
    }
}
