package com.justb81.watchbuddy.tv.discovery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("IdleTimeoutMonitor")
class IdleTimeoutMonitorTest {

    private val NO_DISCOVERY_MS = 1_000L
    private val ALL_UNREACHABLE_MS = 500L

    private fun monitor() = IdleTimeoutMonitor(
        noDiscoveryTimeoutMs = NO_DISCOVERY_MS,
        allUnreachableTimeoutMs = ALL_UNREACHABLE_MS,
    )

    @Nested
    @DisplayName("phase 1 — no discovery timeout")
    inner class NoDiscovery {

        @Test
        @DisplayName("returns NO_DISCOVERY when no phone arrives within the deadline")
        fun `returns NO_DISCOVERY when no phone arrives within the deadline`() = runTest {
            val hasPhones = MutableStateFlow(false)
            val result = async { monitor().awaitTimeout(hasPhones) }
            advanceTimeBy(NO_DISCOVERY_MS + 1)
            assertEquals(IdleTimeoutMonitor.Reason.NO_DISCOVERY, result.await())
        }

        @Test
        @DisplayName("does not fire just before the deadline")
        fun `does not fire just before the deadline`() = runTest {
            val hasPhones = MutableStateFlow(false)
            val result = async { monitor().awaitTimeout(hasPhones) }
            advanceTimeBy(NO_DISCOVERY_MS - 1)
            assertFalse(result.isCompleted)
        }

        @Test
        @DisplayName("transitions to phase 2 when a phone appears before deadline")
        fun `transitions to phase 2 when a phone appears before deadline`() = runTest {
            val hasPhones = MutableStateFlow(false)
            val result = async { monitor().awaitTimeout(hasPhones) }
            advanceTimeBy(NO_DISCOVERY_MS - 1) // near deadline but not expired
            hasPhones.value = true             // phone appears — phase 1 cancels
            advanceTimeBy(ALL_UNREACHABLE_MS + 1) // phones stay present; phase 2 timer not running
            assertFalse(result.isCompleted)
        }
    }

    @Nested
    @DisplayName("phase 2 — all unreachable timeout")
    inner class AllUnreachable {

        @Test
        @DisplayName("returns ALL_UNREACHABLE when phones disappear and stay gone")
        fun `returns ALL_UNREACHABLE when phones disappear and stay gone`() = runTest {
            val hasPhones = MutableStateFlow(true)
            val result = async { monitor().awaitTimeout(hasPhones) }
            advanceUntilIdle() // phase 1 resolves (phones present); now waiting for absence
            hasPhones.value = false
            advanceTimeBy(ALL_UNREACHABLE_MS + 1)
            assertEquals(IdleTimeoutMonitor.Reason.ALL_UNREACHABLE, result.await())
        }

        @Test
        @DisplayName("does not fire when phones return before the timeout")
        fun `does not fire when phones return before the timeout`() = runTest {
            val hasPhones = MutableStateFlow(true)
            val result = async { monitor().awaitTimeout(hasPhones) }
            advanceUntilIdle()
            hasPhones.value = false
            advanceTimeBy(ALL_UNREACHABLE_MS - 1) // almost expired
            hasPhones.value = true                 // phones come back in time
            advanceTimeBy(ALL_UNREACHABLE_MS + 1) // phones still present; timer reset
            assertFalse(result.isCompleted)
        }

        @Test
        @DisplayName("returns ALL_UNREACHABLE after second sustained absence")
        fun `returns ALL_UNREACHABLE after second sustained absence`() = runTest {
            val hasPhones = MutableStateFlow(true)
            val result = async { monitor().awaitTimeout(hasPhones) }
            advanceUntilIdle()
            // First absence: phones come back before timeout
            hasPhones.value = false
            advanceTimeBy(ALL_UNREACHABLE_MS - 1)
            hasPhones.value = true
            advanceUntilIdle()
            // Second absence: phones stay gone past the timeout
            hasPhones.value = false
            advanceTimeBy(ALL_UNREACHABLE_MS + 1)
            assertEquals(IdleTimeoutMonitor.Reason.ALL_UNREACHABLE, result.await())
        }

        @Test
        @DisplayName("does not fire when phones are continuously present")
        fun `does not fire when phones are continuously present`() = runTest {
            val hasPhones = MutableStateFlow(true)
            val result = async { monitor().awaitTimeout(hasPhones) }
            advanceUntilIdle()
            // Advance well past both timeouts with phones continuously present
            advanceTimeBy(NO_DISCOVERY_MS + ALL_UNREACHABLE_MS + 10_000L)
            assertFalse(result.isCompleted)
        }
    }

    @Nested
    @DisplayName("constants")
    inner class Constants {

        @Test
        @DisplayName("NO_DISCOVERY_TIMEOUT_MS is 1 hour")
        fun `NO_DISCOVERY_TIMEOUT_MS is 1 hour`() {
            assertEquals(60 * 60_000L, DiscoveryConstants.NO_DISCOVERY_TIMEOUT_MS)
        }

        @Test
        @DisplayName("ALL_UNREACHABLE_TIMEOUT_MS is 30 minutes")
        fun `ALL_UNREACHABLE_TIMEOUT_MS is 30 minutes`() {
            assertEquals(30 * 60_000L, DiscoveryConstants.ALL_UNREACHABLE_TIMEOUT_MS)
        }

        @Test
        @DisplayName("ALL_UNREACHABLE_TIMEOUT_MS is shorter than NO_DISCOVERY_TIMEOUT_MS")
        fun `ALL_UNREACHABLE_TIMEOUT_MS is shorter than NO_DISCOVERY_TIMEOUT_MS`() {
            assert(DiscoveryConstants.ALL_UNREACHABLE_TIMEOUT_MS < DiscoveryConstants.NO_DISCOVERY_TIMEOUT_MS)
        }
    }
}
