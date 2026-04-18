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
}
