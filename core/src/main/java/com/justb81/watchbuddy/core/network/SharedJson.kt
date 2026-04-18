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
