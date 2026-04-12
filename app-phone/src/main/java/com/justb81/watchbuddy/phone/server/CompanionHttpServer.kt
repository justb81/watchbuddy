package com.justb81.watchbuddy.phone.server

import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.data.ShowRepository
import com.justb81.watchbuddy.phone.llm.RecapGenerator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompanionHttpServer @Inject constructor(
    private val recapGenerator: RecapGenerator,
    private val capabilityProvider: DeviceCapabilityProvider,
    private val showRepository: ShowRepository,
    private val tokenRepository: TokenRepository,
    private val tmdbApi: TmdbApiService
) {
    companion object {
        const val PORT = 8765
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
                    val token = tokenRepository.getAccessToken()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, "No token")
                    try {
                        val shows = showRepository.getShows()
                        call.respond(shows)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error")
                    }
                }

                get("/auth/token") {
                    val token = tokenRepository.getAccessToken()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, "No token")
                    call.respond(mapOf("access_token" to token))
                }

                post("/recap/{traktShowId}") {
                    val showId = call.parameters["traktShowId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid show ID")
                    val token = tokenRepository.getAccessToken()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, "No token")

                    try {
                        val shows = showRepository.getShows()
                        val entry = shows.find { it.show.ids.trakt == showId }
                            ?: return@post call.respond(HttpStatusCode.NotFound, "Show not found")

                        val tmdbId = entry.show.ids.tmdb
                            ?: return@post call.respond(HttpStatusCode.NotFound, "No TMDB ID")

                        val tmdbShow = tmdbApi.getShow(tmdbId, "")
                        val lastSeason = entry.seasons.maxByOrNull { it.number }
                        val lastEpisode = lastSeason?.episodes?.maxByOrNull { it.number }
                        val nextSeason = lastSeason?.number ?: 1
                        val nextEpisode = (lastEpisode?.number ?: 0) + 1

                        val targetEpisode = try {
                            tmdbApi.getEpisode(tmdbId, nextSeason, nextEpisode, "")
                        } catch (_: Exception) {
                            tmdbApi.getEpisode(tmdbId, nextSeason, 1, "")
                        }

                        val watchedEpisodes = entry.seasons.flatMap { season ->
                            season.episodes.mapNotNull { ep ->
                                try {
                                    tmdbApi.getEpisode(tmdbId, season.number, ep.number, "")
                                } catch (_: Exception) { null }
                            }
                        }.takeLast(8)

                        val html = recapGenerator.generateRecap(
                            tmdbShow, watchedEpisodes, targetEpisode, ""
                        )
                        call.respond(mapOf("html" to html))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error")
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        server = null
    }
}
