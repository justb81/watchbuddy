package com.justb81.watchbuddy.phone.llm

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("LlmEventLog")
class LlmEventLogTest {

    @Test
    fun `record assigns monotonic ids and snapshot returns newest first`() {
        val log = LlmEventLog()
        val a = log.record(CALLER, BACKEND, 0, 10, "p1", "r1", LlmEventLog.Status.SUCCESS)
        val b = log.record(CALLER, BACKEND, 1, 20, "p2", "r2", LlmEventLog.Status.SUCCESS)
        val c = log.record(CALLER, BACKEND, 2, 30, "p3", null, LlmEventLog.Status.EMPTY)

        assertTrue(a.id < b.id && b.id < c.id, "ids must be monotonic")
        val snap = log.snapshot()
        assertEquals(listOf(c.id, b.id, a.id), snap.map { it.id })
    }

    @Test
    fun `findById returns event while present and null after eviction`() {
        val log = LlmEventLog()
        val first = log.record(CALLER, BACKEND, 0, 0, "p", "r", LlmEventLog.Status.SUCCESS)
        assertNotNull(log.findById(first.id))

        // Overflow the 100-entry ring; the very first entry must get evicted.
        repeat(100) { i ->
            log.record(CALLER, BACKEND, i.toLong(), 0, "p$i", "r$i", LlmEventLog.Status.SUCCESS)
        }
        assertNull(log.findById(first.id))
        assertEquals(100, log.snapshot().size)
    }

    @Test
    fun `clear empties the ring`() {
        val log = LlmEventLog()
        log.record(CALLER, BACKEND, 0, 0, "p", "r", LlmEventLog.Status.SUCCESS)
        log.record(CALLER, BACKEND, 0, 0, "p", "r", LlmEventLog.Status.SUCCESS)
        log.clear()
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun `error events carry errorSummary and null response`() {
        val log = LlmEventLog()
        val e = log.record(
            caller = CALLER,
            backend = BACKEND,
            startedAtMs = 0,
            durationMs = 5,
            prompt = "p",
            response = null,
            status = LlmEventLog.Status.ERROR,
            errorSummary = "IllegalStateException: boom",
        )
        assertEquals(LlmEventLog.Status.ERROR, e.status)
        assertEquals("IllegalStateException: boom", e.errorSummary)
        assertNull(e.response)
    }

    @Test
    fun `updates flow emits on record and on clear`() = runTest {
        val log = LlmEventLog()
        log.updates.test {
            log.record(CALLER, BACKEND, 0, 0, "p", "r", LlmEventLog.Status.SUCCESS)
            awaitItem()
            log.clear()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val CALLER = "recap"
        const val BACKEND = "AICore"
    }
}
