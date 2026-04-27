package com.justb81.watchbuddy.tv.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Cached JustWatch deep link for a specific show + season + episode + provider + country.
 *
 * Primary key: (tmdb_show_id, season, episode, provider_id, country_code)
 *   - season = 0, episode = 0 → show-level fallback row (no per-episode data from JustWatch)
 *   - standardWebUrl = null → negative cache entry (JustWatch returned no offer for this key)
 *
 * Cache eviction:
 *   - Positive hits (url non-null): cached permanently (until user clears via Diagnostics)
 *   - Negative entries (url null): expire after [NEGATIVE_TTL_MS] (30 days)
 */
@Entity(
    tableName = "justwatch_deep_link",
    primaryKeys = ["tmdb_show_id", "season", "episode", "provider_id", "country_code"],
)
data class JustWatchDeepLink(
    @ColumnInfo(name = "tmdb_show_id") val tmdbShowId: Int,
    val season: Int,
    val episode: Int,
    @ColumnInfo(name = "provider_id") val providerId: Int,
    @ColumnInfo(name = "country_code") val countryCode: String,
    @ColumnInfo(name = "standard_web_url") val standardWebUrl: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
) {
    companion object {
        const val NEGATIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
