package com.justb81.watchbuddy.tv.discovery

/**
 * Timing and threshold constants shared between discovery and scrobble staleness checks.
 *
 * Invariant: PRESENCE_STALENESS_MS must be strictly greater than HEARTBEAT_INTERVAL_MS so that
 * a single missed heartbeat tick does not immediately evict a healthy phone from the list.
 */
internal object DiscoveryConstants {
    /** How often the TV re-fetches /capability for each known phone. */
    const val HEARTBEAT_INTERVAL_MS = 60_000L

    /**
     * A phone is considered stale (and excluded from scrobbling) if no successful /capability
     * response has been received within this window. Set to 2× the heartbeat interval so that
     * exactly one missed heartbeat is tolerated before the phone is treated as unreachable.
     */
    const val PRESENCE_STALENESS_MS = 2 * HEARTBEAT_INTERVAL_MS

    /** Number of consecutive /capability failures before a phone is removed from the list. */
    const val MAX_CONSECUTIVE_FAILURES = 3

    /**
     * [TvDiscoveryService] self-stops if zero phones have been discovered within this window.
     * Requires the user to re-enable discovery, preventing an indefinite FGS slot when no
     * companion phone is in BLE range (addresses Android 14+ 24-hour FGS quota).
     */
    const val NO_DISCOVERY_TIMEOUT_MS = 60 * 60_000L // 1 h

    /**
     * [TvDiscoveryService] self-stops if all previously-discovered phones have been absent
     * (i.e. evicted from the heartbeat list) for this duration. Handles the case where the
     * user leaves home or all companion phones lose power after an initial discovery.
     */
    const val ALL_UNREACHABLE_TIMEOUT_MS = 30 * 60_000L // 30 min
}
