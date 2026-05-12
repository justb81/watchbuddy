package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.server.EpisodeRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post

private const val TAG = "WatchedRoutes"

data class WatchedRouteDeps(
    val tokenRefreshManager: TokenRefreshManager,
    val episodeRepository: EpisodeRepository,
)

fun Route.watchedRoutes(deps: WatchedRouteDeps) {
    post("/watched") {
        deps.tokenRefreshManager.getValidAccessToken()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))
        val body = try {
            call.receive<WatchedToggleRequest>()
        } catch (_: Exception) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
        }
        val result = deps.episodeRepository.markEpisodeWatched(body.showIds, body.season, body.episode)
        if (result.isSuccess) {
            call.respond(WatchedToggleResponse(success = true))
        } else {
            DiagnosticLog.error(TAG, "markEpisodeWatched failed S${body.season}E${body.episode}", result.exceptionOrNull())
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Mark watched failed"))
        }
    }

    delete("/watched") {
        deps.tokenRefreshManager.getValidAccessToken()
            ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))
        val body = try {
            call.receive<WatchedToggleRequest>()
        } catch (_: Exception) {
            return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
        }
        val result = deps.episodeRepository.markEpisodeUnwatched(body.showIds, body.season, body.episode)
        if (result.isSuccess) {
            call.respond(WatchedToggleResponse(success = true))
        } else {
            DiagnosticLog.error(TAG, "markEpisodeUnwatched failed S${body.season}E${body.episode}", result.exceptionOrNull())
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Mark unwatched failed"))
        }
    }
}
