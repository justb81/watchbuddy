package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.AmbiguousScrobbleEvent
import com.justb81.watchbuddy.core.model.ScrobbleAction
import com.justb81.watchbuddy.core.model.ScrobbleDisplayEvent
import com.justb81.watchbuddy.core.model.TitleExtractionRequest
import com.justb81.watchbuddy.core.model.TitleExtractionResponse
import com.justb81.watchbuddy.core.trakt.ScrobbleBody
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.llm.LlmBusyException
import com.justb81.watchbuddy.phone.llm.LlmTitleExtractor
import com.justb81.watchbuddy.service.CompanionStateManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

private const val TAG = "ScrobbleRoutes"
private const val MAX_EXTRACT_BODY_BYTES = 64 * 1024L

data class ScrobbleRouteDeps(
    val tokenRefreshManager: TokenRefreshManager,
    val traktApiService: TraktApiService,
    val stateManager: CompanionStateManager,
    val titleExtractor: LlmTitleExtractor,
)

fun Route.scrobbleRoutes(deps: ScrobbleRouteDeps) {
    ScrobbleAction.entries.forEach { action ->
        post("/scrobble/${action.name.lowercase()}") {
            val token = deps.tokenRefreshManager.getValidAccessToken()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))
            val body = try { call.receive<ScrobbleRequestBody>() } catch (_: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
            }
            try {
                val scrobbleBody = ScrobbleBody(show = body.show, episode = body.episode, progress = body.progress)
                when (action) {
                    ScrobbleAction.START -> deps.traktApiService.scrobbleStart("Bearer $token", scrobbleBody)
                    ScrobbleAction.PAUSE -> deps.traktApiService.scrobblePause("Bearer $token", scrobbleBody)
                    ScrobbleAction.STOP -> deps.traktApiService.scrobbleStop("Bearer $token", scrobbleBody)
                }
                deps.stateManager.onScrobbleEvent(
                    ScrobbleDisplayEvent(action, body.show, body.episode, body.progress, System.currentTimeMillis())
                )
                DiagnosticLog.event(
                    TAG,
                    "scrobble ${action.name.lowercase()} ok show=${body.show.title} " +
                        "S${body.episode.season}E${body.episode.number} progress=${body.progress}"
                )
                call.respond(ScrobbleActionResponse(success = true))
            } catch (e: Exception) {
                DiagnosticLog.error(TAG, "scrobble ${action.name.lowercase()} failed", e)
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Scrobble failed"))
            }
        }
    }

    post("/scrobble/extract") {
        val contentLength = call.request.contentLength() ?: 0L
        if (contentLength > MAX_EXTRACT_BODY_BYTES) {
            return@post call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse("Request body too large")
            )
        }
        val body = try {
            call.receive<TitleExtractionRequest>()
        } catch (_: Exception) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
        }
        try {
            val response = deps.titleExtractor.extract(body.snapshot, body.libraryHints)
                ?: TitleExtractionResponse(confidence = 0f)
            call.respond(response)
        } catch (e: LlmBusyException) {
            DiagnosticLog.warn(TAG, "extract: LLM busy, rejecting request", e)
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("LLM busy, try again later"))
        } catch (e: Exception) {
            DiagnosticLog.warn(TAG, "title extraction failed", e)
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Extraction failed"))
        }
    }

    post("/scrobble/prompt") {
        val event = try {
            call.receive<AmbiguousScrobbleEvent>()
        } catch (_: Exception) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
        }
        deps.stateManager.onAmbiguousPrompt(event)
        DiagnosticLog.event(
            TAG,
            "ambiguous prompt received sessionKey='${event.sessionKey}' candidates=${event.candidates.size}",
        )
        call.respond(HttpStatusCode.NoContent)
    }
}
