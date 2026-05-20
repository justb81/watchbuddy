package com.justb81.watchbuddy.tv.discovery

/**
 * Timing and threshold constants for phone discovery, the heartbeat poller,
 * and scrobble-staleness checks.
 *
 * The heartbeat is driven by a fast `HEARTBEAT_TICK_MS` driver that consults
 * a per-phone schedule. Each phone's next-poll-due time is advanced
 * independently:
 *  - On a successful `/capability` response: schedule the next poll
 *    `POLL_BASE_INTERVAL_MS` ahead.
 *  - On a failure: schedule using exponential backoff
 *    (`POLL_BACKOFF_INITIAL_MS`, doubling each consecutive failure, capped
 *    at `POLL_BACKOFF_MAX_MS`).
 *
 * Eviction is consecutive-failure based: a phone is removed only after
 * `MAX_CONSECUTIVE_FAILURES` in a row, which combined with the backoff
 * gives ~5 minutes of grace before a transient blip can evict a healthy
 * phone.
 *
 * Invariants enforced by tests:
 *  - `POLL_BASE_INTERVAL_MS < PRESENCE_STALENESS_MS` so a steady-state poll
 *    keeps a healthy phone fresh.
 *  - `POLL_BACKOFF_INITIAL_MS < POLL_BACKOFF_MAX_MS`.
 *  - `HEARTBEAT_TICK_MS <= POLL_BACKOFF_INITIAL_MS` so the driver wakes up
 *    at least once per backoff interval.
 */
internal object DiscoveryConstants {
    /** Driver tick — how often the heartbeat loop wakes up to check what's due. */
    const val HEARTBEAT_TICK_MS = 10_000L

    /** Cadence between successful `/capability` polls in steady state. */
    const val POLL_BASE_INTERVAL_MS = 60_000L

    /**
     * A phone is considered stale once two consecutive poll intervals have elapsed without
     * a successful `/capability` response. Set to 2× the steady-state poll cadence so
     * exactly one missed poll is tolerated before the phone is treated as unreachable
     * (independent of the eviction decision).
     */
    const val PRESENCE_STALENESS_MS = 2 * POLL_BASE_INTERVAL_MS

    /** First retry delay after a `/capability` failure. */
    const val POLL_BACKOFF_INITIAL_MS = 10_000L

    /** Backoff cap — no single retry waits longer than this. */
    const val POLL_BACKOFF_MAX_MS = 5 * 60_000L

    /**
     * Number of consecutive failures before a phone is evicted. Combined with
     * the exponential backoff schedule (10 s + 20 s + 40 s + 80 s + 160 s), an
     * unreachable phone is evicted after ~5 minutes of real failures, while a
     * 2-minute Wi-Fi blip never accumulates this many failures.
     */
    const val MAX_CONSECUTIVE_FAILURES = 5

    /**
     * [TvDiscoveryService] self-stops if zero phones have been discovered within this window.
     * Requires the user to re-enable discovery, preventing an indefinite FGS slot when no
     * companion phone is in BLE range (addresses Android 14+ 24-hour FGS quota).
     */
    const val NO_DISCOVERY_TIMEOUT_MS = 60 * 60_000L // 1 h
    const val NO_DISCOVERY_TIMEOUT_MINUTES = NO_DISCOVERY_TIMEOUT_MS / 60_000L

    /**
     * [TvDiscoveryService] self-stops if all previously-discovered phones have been absent
     * (i.e. evicted from the heartbeat list) for this duration. Handles the case where the
     * user leaves home or all companion phones lose power after an initial discovery.
     */
    const val ALL_UNREACHABLE_TIMEOUT_MS = 30 * 60_000L // 30 min
    const val ALL_UNREACHABLE_TIMEOUT_MINUTES = ALL_UNREACHABLE_TIMEOUT_MS / 60_000L
}
