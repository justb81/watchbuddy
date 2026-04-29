package com.justb81.watchbuddy.phone.llm

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LlmEngineCache")
class LlmEngineCacheTest {

    private val variantE2B = LlmOrchestrator.ModelVariant.GEMMA4_E2B
    private val variantE4B = LlmOrchestrator.ModelVariant.GEMMA4_E4B

    private fun fakeHandle(label: String): LiteRtLlmProvider.EngineHandle =
        object : LiteRtLlmProvider.EngineHandle {
            override fun sendMessage(prompt: String): String = label
            var closed: Boolean = false
            override fun close() { closed = true }
        }

    @Test
    fun `getOrCreateLiteRtHandle returns the same handle on repeated calls`() = runTest {
        val cache = LlmEngineCache()
        var calls = 0
        val factory = LiteRtLlmProvider.EngineFactory { _, _ ->
            calls++
            fakeHandle("h$calls")
        }
        val first = cache.getOrCreateLiteRtHandle(variantE2B, useGpu = false, "/m", factory)
        val second = cache.getOrCreateLiteRtHandle(variantE2B, useGpu = false, "/m", factory)
        assertSame(first, second)
        assertEquals(1, calls)
    }

    @Test
    fun `switching variant builds a new handle and closes the old one`() = runTest {
        val cache = LlmEngineCache()
        val handles = mutableListOf<TrackingHandle>()
        val factory = LiteRtLlmProvider.EngineFactory { _, _ ->
            TrackingHandle("v${handles.size}").also { handles += it }
        }
        cache.getOrCreateLiteRtHandle(variantE2B, useGpu = false, "/m", factory)
        cache.getOrCreateLiteRtHandle(variantE4B, useGpu = false, "/m", factory)
        assertEquals(2, handles.size)
        assertTrue(handles[0].closed, "Old handle should be closed when variant changes")
        assertFalse(handles[1].closed)
    }

    @Test
    fun `switching GPU vs CPU rebuilds the handle`() = runTest {
        val cache = LlmEngineCache()
        val handles = mutableListOf<TrackingHandle>()
        val factory = LiteRtLlmProvider.EngineFactory { _, useGpu ->
            TrackingHandle(if (useGpu) "gpu" else "cpu").also { handles += it }
        }
        cache.getOrCreateLiteRtHandle(variantE2B, useGpu = true, "/m", factory)
        cache.getOrCreateLiteRtHandle(variantE2B, useGpu = false, "/m", factory)
        assertEquals(2, handles.size)
        assertTrue(handles[0].closed)
        assertFalse(handles[1].closed)
    }

    @Test
    fun `invalidateLiteRtHandle drops the cached handle and closes it`() = runTest {
        val cache = LlmEngineCache()
        val handle = TrackingHandle("h")
        val factory = LiteRtLlmProvider.EngineFactory { _, _ -> handle }
        cache.getOrCreateLiteRtHandle(variantE2B, useGpu = false, "/m", factory)
        assertFalse(handle.closed)
        cache.invalidateLiteRtHandle()
        assertTrue(handle.closed)
        assertFalse(cache.isLiteRtGpu())
    }

    @Test
    fun `isLiteRtGpu reflects the cached backend`() = runTest {
        val cache = LlmEngineCache()
        val factory = LiteRtLlmProvider.EngineFactory { _, _ -> fakeHandle("h") }
        cache.getOrCreateLiteRtHandle(variantE2B, useGpu = true, "/m", factory)
        assertTrue(cache.isLiteRtGpu())
        cache.invalidateLiteRtHandle()
        assertFalse(cache.isLiteRtGpu())
        cache.getOrCreateLiteRtHandle(variantE2B, useGpu = false, "/m", factory)
        assertFalse(cache.isLiteRtGpu())
    }

    private class TrackingHandle(val label: String) : LiteRtLlmProvider.EngineHandle {
        var closed: Boolean = false
        override fun sendMessage(prompt: String): String = label
        override fun close() { closed = true }
    }
}
