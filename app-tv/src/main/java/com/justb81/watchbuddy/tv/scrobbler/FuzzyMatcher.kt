package com.justb81.watchbuddy.tv.scrobbler

/**
 * String-matching utilities for fuzzy title comparison.
 * Pure Kotlin — no external dependencies.
 */
object FuzzyMatcher {

    /**
     * Normalize a title for comparison:
     * - lowercase
     * - remove special characters (keep alphanumeric and spaces)
     * - strip leading "the "
     * - collapse whitespace and trim
     */
    fun normalize(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("the ")
            .trim()
    }

    /**
     * Classic Levenshtein edit distance between two strings.
     * Uses O(min(m,n)) space via single-row DP.
     */
    fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // Ensure a is the shorter string for space efficiency
        val (short, long) = if (a.length <= b.length) a to b else b to a

        var prev = IntArray(short.length + 1) { it }
        var curr = IntArray(short.length + 1)

        for (i in 1..long.length) {
            curr[0] = i
            for (j in 1..short.length) {
                val cost = if (long[i - 1] == short[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,       // insertion
                    prev[j] + 1,            // deletion
                    prev[j - 1] + cost      // substitution
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[short.length]
    }

    /**
     * Compute a similarity score between two strings.
     *
     * @return Float in [0.0, 1.0] where 1.0 = exact match
     */
    fun fuzzyScore(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)

        if (na.isEmpty() || nb.isEmpty()) return 0.0f

        // Exact match after normalization
        if (na == nb) return 1.0f

        // Prefix match (one starts with the other)
        if (na.startsWith(nb) || nb.startsWith(na)) return 0.95f

        // Levenshtein-based score
        val distance = levenshteinDistance(na, nb)
        val maxLen = maxOf(na.length, nb.length)
        return (1.0f - distance.toFloat() / maxLen).coerceAtLeast(0.0f)
    }
}
