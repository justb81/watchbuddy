package com.justb81.watchbuddy.core.network

import kotlinx.serialization.json.Json

/**
 * Single lenient [Json] instance shared across all Retrofit/Ktor converters.
 * Centralises serialisation behaviour so changes propagate uniformly.
 */
val WatchBuddyJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Strict [Json] instance for trust-boundary payloads such as `/capability` responses and
 * internal IPC where schema regressions must surface immediately rather than silently
 * defaulting. Missing optional fields with `= default` still resolve normally; only
 * unknown keys, lenient number parsing, and null→default coercion are disabled.
 */
val WatchBuddyStrictJson: Json = Json {
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}
