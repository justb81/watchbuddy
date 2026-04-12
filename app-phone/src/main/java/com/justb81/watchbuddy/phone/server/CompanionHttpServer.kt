package com.justb81.watchbuddy.phone.server

import android.util.Log
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.RecapGenerator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local HTTP server running on the phone (port 8765).
 * The TV app discovers this via NSD (mDNS) and calls its endpoints.
 *
 * Endpoints:
 *   GET  /capability           → DeviceCapability (device score, RAM, LLM backend)
 *   GET  /shows                → List of watched shows for this user (from Trakt cache)
 *   POST /recap/{traktShowId}  → Generate + return HTML recap for a show
 *   GET  /auth/token           → Current access token for TV app usage
 */
@Singleton
class CompanionHttpServer @Inject constructor(
    private val recapGenerator: RecapGenerator,
    private val capabilityProvider: DeviceCapabilityProvider,
    private val showRepository: ShowRepository,
    private val tokenRepository: TokenRepository,
    private val tmdbApiService: TmdbApiService
) {
    companion object {
        const val PORT = 8765
        private const val TAG = "CompanionHttpServer"
    }

    private var server: ApplicationEngine? = null

    fun start() {
        server = embeddedServer(Netty, port = PORT) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            routing {
                get("/capability") {
                    call.respond(capabilityProvider.getCapability())
                }

                get("/shows") {
                    tokenRepository.getAccessToken()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))
                    try {
                        val shows = showRepository.getShows()
                        call.respond(shows)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch shows", e)
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Unknown error"))
                    }
                }

                post("/recap/{traktShowId}") {
                    val showId = call.parameters["traktShowId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid show ID"))

                    tokenRepository.getAccessToken()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))

                    try {
                        val body = try { call.receive<RecapRequest>() } catch (_: Exception) { RecapRequest() }

                        val shows = showRepository.getShows()
                        val watchedEntry = shows.find { it.show.ids.trakt == showId }
                            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Show not found"))

                        val tmdbId = watchedEntry.show.ids.tmdb
                            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("No TMDB ID for show"))

                        val tmdbShow = tmdbApiService.getShow(tmdbId, body.tmdbApiKey)

                        // Collect watched episode numbers from Trakt data
                        val watchedEpisodeRefs = watchedEntry.seasons.flatMap { season ->
                            season.episodes.map { ep -> season.number to ep.number }
                        }

                        // Load episode details from TMDB for the last 8 watched episodes
                        val tmdbEpisodes = watchedEpisodeRefs
                            .takeLast(8)
                            .mapNotNull { (season, episode) ->
                                try {
                                    tmdbApiService.getEpisode(tmdbId, season, episode, body.tmdbApiKey)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to load TMDB episode S${season}E${episode}", e)
                                    null
                                }
                            }

                        if (tmdbEpisodes.isEmpty()) {
                            return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("No episode data available"))
                        }

                        // Use last episode as the "target" (next to watch)
                        val targetEpisode = tmdbEpisodes.last()
                        val watchedEpisodes = tmdbEpisodes.dropLast(1).ifEmpty { tmdbEpisodes }

                        val html = recapGenerator.generateRecap(
                            show = tmdbShow,
                            watchedEpisodes = watchedEpisodes,
                            targetEpisode = targetEpisode,
                            apiKey = body.tmdbApiKey
                        )
                        call.respond(mapOf("html" to html))
                    } catch (e: Exception) {
                        Log.e(TAG, "Recap generation failed for show $showId", e)
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Recap generation failed: ${e.message}"))
                    }
                }

                get("/auth/token") {
                    val token = tokenRepository.getAccessToken()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))
                    call.respond(TokenResponse(accessToken = token))
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        server = null
    }
}

@Serializable
private data class RecapRequest(
    val tmdbApiKey: String = ""
)

@Serializable
private data class TokenResponse(
    val accessToken: String
)

@Serializable
private data class ErrorResponse(
    val error: String
)
