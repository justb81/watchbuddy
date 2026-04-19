package com.justb81.watchbuddy.phone.server

import android.util.Log
import com.justb81.watchbuddy.core.locale.LocaleHelper
import com.justb81.watchbuddy.core.model.ScrobbleAction
import com.justb81.watchbuddy.core.model.ScrobbleDisplayEvent
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbCache
import com.justb81.watchbuddy.core.trakt.ScrobbleBody
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.RecapGenerator
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionStateManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CompanionHttpServer"
private const val DEFAULT_PAGE_SIZE = 30
private const val MAX_PAGE_SIZE = 200

/**
 * Local HTTP server running on the phone (port 8765).
 * The TV app discovers this via NSD (mDNS) and calls its endpoints.
 *
 * Endpoints:
 *   GET  /capability           → DeviceCapability (device score, RAM, LLM backend)
 *   GET  /shows                → List of watched shows for this user (from Trakt cache)
 *   POST /recap/{traktShowId}  → Generate + return HTML recap for a show
 *   GET  /auth/token           → Current access token for TV app usage (show search)
 *   POST /scrobble/start       → Forward scrobble start to this user's Trakt account
 *   POST /scrobble/pause       → Forward scrobble pause to this user's Trakt account
 *   POST /scrobble/stop        → Forward scrobble stop to this user's Trakt account
 */
@Singleton
class CompanionHttpServer @Inject constructor(
    private val recapGenerator: RecapGenerator,
    private val capabilityProvider: DeviceCapabilityProvider,
    private val showRepository: ShowRepository,
    private val tokenRepository: TokenRepository,
    private val tokenRefreshManager: TokenRefreshManager,
    private val traktApiService: TraktApiService,
    private val tmdbApiService: TmdbApiService,
    private val tmdbCache: TmdbCache,
    private val settingsRepository: SettingsRepository,
    private val stateManager: CompanionStateManager
) {
    companion object {
        const val PORT = 8765
    }

    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        // Bind explicitly to 0.0.0.0 so Netty never falls back to loopback-only
        // on devices where the default binding behaves unexpectedly — the NSD
        // advertisement pins the Wi-Fi IPv4, so the listener must accept
        // connections on that interface (#265).
        server = embeddedServer(Netty, host = "0.0.0.0", port = PORT) {
            configureCompanionRoutes(
                recapGenerator, capabilityProvider, showRepository,
                tokenRepository, tokenRefreshManager, traktApiService, tmdbApiService, tmdbCache,
                settingsRepository, stateManager
            )
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        server = null
    }
}

/**
 * Configures the Ktor application with all companion server routes.
 * Extracted as a top-level function so it can be tested via [io.ktor.server.testing.testApplication].
 */
internal fun Application.configureCompanionRoutes(
    recapGenerator: RecapGenerator,
    capabilityProvider: DeviceCapabilityProvider,
    showRepository: ShowRepository,
    tokenRepository: TokenRepository,
    tokenRefreshManager: TokenRefreshManager,
    traktApiService: TraktApiService,
    tmdbApiService: TmdbApiService,
    tmdbCache: TmdbCache,
    settingsRepository: SettingsRepository,
    stateManager: CompanionStateManager
) {
    install(ContentNegotiation) {
        json(WatchBuddyJson)
    }
    routing {
        get("/capability") {
            stateManager.onCapabilityChecked()
            call.respond(capabilityProvider.getCapability())
        }

        get("/shows") {
            try {
                tokenRepository.getAccessToken()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    ?.coerceIn(1, MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
                val shows = showRepository.getShows().drop(offset).take(limit)
                call.respond(shows)
            } catch (e: SecurityException) {
                Log.e(TAG, "Keystore unavailable", e)
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Service unavailable"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch shows", e)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
            }
        }

        post("/recap/{traktShowId}") {
            val showId = call.parameters["traktShowId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid show ID"))

            try {
                tokenRepository.getAccessToken()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))

                val body = try { call.receive<RecapRequest>() } catch (_: Exception) { RecapRequest() }
                val apiKey = body.tmdbApiKey.ifBlank {
                    settingsRepository.getTmdbApiKey().first()
                }

                if (apiKey.isBlank()) {
                    return@post call.respond(
                        HttpStatusCode.PreconditionFailed,
                        ErrorResponse("TMDB API key not configured")
                    )
                }

                val tmdbLanguage = LocaleHelper.getTmdbLanguage()

                val shows = showRepository.getShows()
                val watchedEntry = shows.find { it.entry.show.ids.trakt == showId }?.entry
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Show not found"))

                val tmdbId = watchedEntry.show.ids.tmdb
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("No TMDB ID for show"))

                val tmdbShow = tmdbCache.getShow(tmdbId)
                    ?: tmdbApiService.getShow(tmdbId, apiKey, language = tmdbLanguage)
                        .also { tmdbCache.putShow(tmdbId, it) }

                // Collect watched episode numbers from Trakt data
                val watchedEpisodeRefs = watchedEntry.seasons.flatMap { season ->
                    season.episodes.map { ep -> season.number to ep.number }
                }

                // Load episode details from TMDB for the last 8 watched episodes in parallel
                val tmdbEpisodes = coroutineScope {
                    watchedEpisodeRefs
                        .takeLast(8)
                        .map { (season, episode) ->
                            async {
                                try {
                                    tmdbCache.getEpisode(tmdbId, season, episode)
                                        ?: tmdbApiService.getEpisode(tmdbId, season, episode, apiKey, language = tmdbLanguage)
                                            .also { tmdbCache.putEpisode(tmdbId, season, episode, it) }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to load TMDB episode S${season}E${episode}", e)
                                    null
                                }
                            }
                        }
                        .awaitAll()
                        .filterNotNull()
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
                    apiKey = apiKey
                )
                call.respond(mapOf("html" to html))
            } catch (e: SecurityException) {
                Log.e(TAG, "Keystore unavailable", e)
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Service unavailable"))
            } catch (e: Exception) {
                Log.e(TAG, "Recap generation failed for show $showId", e)
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Recap generation failed"))
            }
        }

        get("/auth/token") {
            val token = tokenRefreshManager.getValidAccessToken()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))
            call.respond(TokenResponse(accessToken = token))
        }

        ScrobbleAction.entries.forEach { action ->
            post("/scrobble/${action.name.lowercase()}") {
                call.handleScrobble(action, tokenRefreshManager, traktApiService, stateManager)
            }
        }
    }
}

private suspend fun ApplicationCall.handleScrobble(
    action: ScrobbleAction,
    tokenRefreshManager: TokenRefreshManager,
    traktApiService: TraktApiService,
    stateManager: CompanionStateManager,
) {
    val token = tokenRefreshManager.getValidAccessToken()
        ?: return respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))
    val body = try { receive<ScrobbleRequestBody>() } catch (_: Exception) {
        return respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
    }
    try {
        val scrobbleBody = ScrobbleBody(show = body.show, episode = body.episode, progress = body.progress)
        when (action) {
            ScrobbleAction.START -> traktApiService.scrobbleStart("Bearer $token", scrobbleBody)
            ScrobbleAction.PAUSE -> traktApiService.scrobblePause("Bearer $token", scrobbleBody)
            ScrobbleAction.STOP -> traktApiService.scrobbleStop("Bearer $token", scrobbleBody)
        }
        stateManager.onScrobbleEvent(
            ScrobbleDisplayEvent(action, body.show, body.episode, body.progress, System.currentTimeMillis())
        )
        respond(ScrobbleActionResponse(success = true))
    } catch (e: Exception) {
        Log.e(TAG, "Scrobble ${action.name.lowercase()} failed", e)
        respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Scrobble failed"))
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

@Serializable
private data class ScrobbleRequestBody(
    val show: TraktShow,
    val episode: TraktEpisode,
    val progress: Float
)

@Serializable
private data class ScrobbleActionResponse(
    val success: Boolean
)
