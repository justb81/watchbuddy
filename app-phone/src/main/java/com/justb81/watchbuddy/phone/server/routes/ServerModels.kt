package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.core.model.TraktIds
import kotlinx.serialization.Serializable

@Serializable
internal data class ErrorResponse(val error: String)

@Serializable
internal data class ScrobbleRequestBody(
    val show: com.justb81.watchbuddy.core.model.TraktShow,
    val episode: com.justb81.watchbuddy.core.model.TraktEpisode,
    val progress: Float,
)

@Serializable
internal data class ScrobbleActionResponse(val success: Boolean)

@Serializable
internal data class RecapRequest(val tmdbApiKey: String = "")

@Serializable
internal data class AddToLibraryResponse(val success: Boolean)

@Serializable
data class WatchedToggleRequest(
    val showIds: TraktIds,
    val season: Int,
    val episode: Int,
    val resolvesSessionKey: String? = null,
)

@Serializable
data class WatchedToggleResponse(val success: Boolean)

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}
