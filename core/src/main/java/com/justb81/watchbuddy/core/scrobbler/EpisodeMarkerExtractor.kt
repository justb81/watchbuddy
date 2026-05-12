package com.justb81.watchbuddy.core.scrobbler

/**
 * Pure functions for building episode-marker pattern lists and extracting
 * `(season, episode)` pairs from text strings.
 *
 * No external dependencies — no caches, no logging, no coroutines. Callers
 * are responsible for logging any diagnostics at the call site.
 */
object EpisodeMarkerExtractor {

    /**
     * A resolved `(season, episode)` pair extracted from a text field.
     */
    data class Marker(val season: Int, val episode: Int)

    /**
     * Tries every pattern in [profile.markerRegexes] (if any), then the
     * generic `S##E##` fallback, and returns the first [Marker] found in
     * [text]. Returns null when no pattern matches.
     */
    fun extractFromText(text: String, profile: AppProfile?): Marker? {
        val patterns = buildPatterns(profile)
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val season = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            val episode = match.groupValues.getOrNull(2)?.toIntOrNull() ?: continue
            return Marker(season, episode)
        }
        return null
    }

    /**
     * Strips an `S##E##`-style marker suffix (using the profile's patterns plus
     * the generic fallback) and any trailing colon or whitespace from [field],
     * returning the remaining show-title portion.
     *
     * Example: `"Breaking Bad S03E07"` → `"Breaking Bad"`
     * Example: `"The Crown:"` → `"The Crown"`
     */
    fun normalizeTitle(field: String, profile: AppProfile?): String {
        val patterns = buildPatterns(profile)
        val match = patterns.firstNotNullOfOrNull { it.find(field) }
        val withoutMarker = if (match != null) field.substringBefore(match.value) else field
        return withoutMarker.trimEnd(':', ' ', '\t')
    }

    /**
     * Returns profile-specific marker regexes followed by the generic `S##E##`
     * pattern. Callers should iterate this list and stop at the first match.
     */
    internal fun buildPatterns(profile: AppProfile?): List<Regex> = buildList {
        profile?.markerRegexes?.let { addAll(it) }
        add(Regex("""(?i)S(\d{1,2})E(\d{1,2})"""))
    }
}
