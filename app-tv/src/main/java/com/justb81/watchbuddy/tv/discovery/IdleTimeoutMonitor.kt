package com.justb81.watchbuddy.tv.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Monitors phone-discovery presence and signals when [TvDiscoveryService] should self-stop
 * to avoid holding an Android 14+ FGS quota slot unnecessarily.
 *
 * Two thresholds drive the decision:
 * - [Reason.NO_DISCOVERY]: no phone was ever discovered within [noDiscoveryTimeoutMs] (1 h).
 *   The user must re-toggle discovery to restart the service — this avoids draining the
 *   24-hour FGS cumulative limit when there is simply no companion phone in BLE range.
 * - [Reason.ALL_UNREACHABLE]: every previously-discovered phone has been absent for
 *   [allUnreachableTimeoutMs] (30 min). Triggered when the user leaves home or all
 *   phones lose power after an initial successful discovery session.
 *
 * This class has no Android dependencies — all logic is pure coroutines with virtual-time
 * support so it can be exercised in JVM unit tests.
 */
internal class IdleTimeoutMonitor(
    private val noDiscoveryTimeoutMs: Long = DiscoveryConstants.NO_DISCOVERY_TIMEOUT_MS,
    private val allUnreachableTimeoutMs: Long = DiscoveryConstants.ALL_UNREACHABLE_TIMEOUT_MS,
) {
    enum class Reason {
        /** Zero phones discovered within [noDiscoveryTimeoutMs]. */
        NO_DISCOVERY,
        /** All known phones absent for [allUnreachableTimeoutMs]. */
        ALL_UNREACHABLE,
    }

    /**
     * Suspends until an idle-timeout condition is met and returns the [Reason].
     *
     * [hasPhones] must emit `true` when at least one phone is in the discovered list
     * and `false` when the list is empty. [StateFlow] is the typical input — its current
     * value is always replayed to new collectors, so this function sees the up-to-date
     * state immediately on start.
     *
     * The caller is responsible for cancelling the coroutine when discovery is disabled
     * or the service is destroyed; cancellation propagates cleanly via [CancellationException].
     */
    suspend fun awaitTimeout(hasPhones: Flow<Boolean>): Reason {
        // Phase 1: wait for the first phone to appear. If none shows up within
        // noDiscoveryTimeoutMs the service has been running idle since boot/toggle.
        val firstDiscovery = withTimeoutOrNull(noDiscoveryTimeoutMs) {
            hasPhones.first { it }
        }
        if (firstDiscovery == null) return Reason.NO_DISCOVERY

        // Phase 2: at least one phone was seen. Loop: wait for the list to go empty, then
        // give phones allUnreachableTimeoutMs to come back before self-stopping.
        while (true) {
            hasPhones.first { !it }
            val cameBack = withTimeoutOrNull(allUnreachableTimeoutMs) {
                hasPhones.first { it }
            }
            if (cameBack == null) return Reason.ALL_UNREACHABLE
            // A phone returned — reset and watch for the next absence.
        }
    }
}
