package com.justb81.watchbuddy.core.cache

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TimedCachedResource")
class TimedCachedResourceTest {

    private var fakeTime = 1_000_000L
    private var fetchCount = 0

    @BeforeEach
    fun setUp() {
        fakeTime = 1_000_000L
        fetchCount = 0
    }

    @Test
    fun `get returns fetched value on first call`() = runTest {
        val cache = buildCache<String, String>(ttlMillis = 60_000L) { "value-$it" }

        val result = cache.get("key")

        assertEquals("value-key", result)
        assertEquals(1, fetchCount)
    }

    @Test
    fun `get returns cached value within TTL without calling fetcher again`() = runTest {
        val cache = buildCache<String, String>(ttlMillis = 60_000L) { "value-$it" }

        cache.get("key")
        val second = cache.get("key")

        assertEquals("value-key", second)
        assertEquals(1, fetchCount)
    }

    @Test
    fun `get refetches after TTL elapses`() = runTest {
        val cache = buildCache<String, String>(ttlMillis = 1_000L) { "value-$fetchCount" }

        cache.get("key")
        fakeTime += 1_001L
        val second = cache.get("key")

        assertEquals(2, fetchCount)
        assertEquals("value-2", second)
    }

    @Test
    fun `invalidate forces refetch on next get`() = runTest {
        val cache = buildCache<String, String>(ttlMillis = 60_000L) { "value-$fetchCount" }

        cache.get("key")
        cache.invalidate("key")
        cache.get("key")

        assertEquals(2, fetchCount)
    }

    @Test
    fun `concurrent gets of the same key invoke fetcher exactly once`() = runTest {
        val barrier = kotlinx.coroutines.CompletableDeferred<Unit>()
        var callCount = 0
        val cache = TimedCachedResource<String, String>(
            ttlMillis = 60_000L,
            clock = { fakeTime },
        ) {
            callCount++
            barrier.await()
            "result"
        }

        val jobs = (1..10).map { async { cache.get("key") } }
        barrier.complete(Unit)
        val results = jobs.awaitAll()

        assertEquals(1, callCount)
        results.forEach { assertEquals("result", it) }
    }

    @Test
    fun `concurrent gets of different keys do not block each other`() = runTest {
        val results = (1..5).map { i ->
            async { buildCache<Int, Int>(ttlMillis = 60_000L) { it * 10 }.get(i) }
        }.awaitAll()

        assertEquals(listOf(10, 20, 30, 40, 50), results)
    }

    @Test
    fun `fetcher exception is rethrown and not cached`() = runTest {
        var calls = 0
        val cache = TimedCachedResource<String, String>(
            ttlMillis = 60_000L,
            clock = { fakeTime },
        ) {
            calls++
            if (calls == 1) error("fetch failed")
            "ok"
        }

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { cache.get("key") }
        }
        val second = cache.get("key")
        assertEquals("ok", second)
        assertEquals(2, calls)
    }

    @Test
    fun `Unit-keyed cache behaves as single-value cache`() = runTest {
        val cache = buildCache<Unit, Int>(ttlMillis = 60_000L) { fetchCount }

        val first = cache.get(Unit)
        val second = cache.get(Unit)

        assertEquals(1, first)
        assertEquals(1, second)
        assertEquals(1, fetchCount)
    }

    private fun <K, V> buildCache(ttlMillis: Long, fetcher: suspend (K) -> V): TimedCachedResource<K, V> {
        return TimedCachedResource(
            ttlMillis = ttlMillis,
            clock = { fakeTime },
            fetcher = { key ->
                fetchCount++
                fetcher(key)
            },
        )
    }
}
