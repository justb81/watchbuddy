package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.PhoneAddToLibraryRequest
import com.justb81.watchbuddy.core.tracking.TrackingProvider
import com.justb81.watchbuddy.core.trakt.SyncHistoryEpisodeItem
import com.justb81.watchbuddy.core.trakt.SyncHistorySeasonItem
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.server.EpisodeRepository
import com.justb81.watchbuddy.phone.server.ShowRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private const val TAG = "ShowsRoutes"
private const val DEFAULT_PAGE_SIZE = 30
private const val MAX_PAGE_SIZE = 200

data class ShowsRouteDeps(
    val showRepository: ShowRepository,
    val episodeRepository: EpisodeRepository,
    val tokenRepository: TokenRepository,
    val tokenRefreshManager: TokenRefreshManager,
    val trackingProvider: TrackingProvider,
)

fun Route.showsRoutes(deps: ShowsRouteDeps) {
    get("/shows") {
        try {
            deps.tokenRepository.getAccessToken()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                ?.coerceIn(1, MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
            val shows = deps.showRepository.getShows().drop(offset).take(limit)
            call.respond(shows)
        } catch (e: SecurityException) {
            DiagnosticLog.error(TAG, "Keystore unavailable", e)
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Service unavailable"))
        } catch (e: Exception) {
            DiagnosticLog.error(TAG, "Failed to fetch shows", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }

    get("/shows/{showId}/seasons") {
        val showId = call.parameters["showId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing show ID"))
        try {
            deps.tokenRepository.getAccessToken()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))
            val seasons = deps.episodeRepository.getSeasonsWithEpisodes(showId)
            call.respond(seasons)
        } catch (e: SecurityException) {
            DiagnosticLog.error(TAG, "Keystore unavailable in /shows/$showId/seasons", e)
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Service unavailable"))
        } catch (e: Exception) {
            DiagnosticLog.error(TAG, "Failed to fetch seasons for show $showId", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }

    post("/shows/add-to-library") {
        val token = deps.tokenRefreshManager.getValidAccessToken()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))
        val body = try {
            call.receive<PhoneAddToLibraryRequest>()
        } catch (_: Exception) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
        }
        try {
            val seasons = listOf(
                SyncHistorySeasonItem(
                    number = body.episode.season,
                    episodes = listOf(SyncHistoryEpisodeItem(number = body.episode.number))
                )
            )
            deps.trackingProvider.markWatched("Bearer $token", body.show.ids, seasons).getOrThrow()
            deps.showRepository.invalidateCache()
            DiagnosticLog.event(
                TAG,
                "add-to-library ok show='${body.show.title}' S${body.episode.season}E${body.episode.number}",
            )
            call.respond(AddToLibraryResponse(success = true))
        } catch (e: Exception) {
            DiagnosticLog.error(TAG, "add-to-library failed for '${body.show.title}'", e)
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Add to library failed"))
        }
    }
}
