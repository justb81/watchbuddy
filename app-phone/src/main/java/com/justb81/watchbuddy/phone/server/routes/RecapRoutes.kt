package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.core.locale.LocaleHelper
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbCache
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.LlmBusyException
import com.justb81.watchbuddy.phone.llm.RecapGenerator
import com.justb81.watchbuddy.phone.server.ShowRepository
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

private const val TAG = "RecapRoutes"

data class RecapRouteDeps(
    val recapGenerator: RecapGenerator,
    val showRepository: ShowRepository,
    val tokenRepository: TokenRepository,
    val tmdbApiService: TmdbApiService,
    val tmdbCache: TmdbCache,
    val settingsRepository: SettingsRepository,
)

fun Route.recapRoutes(deps: RecapRouteDeps) {
    post("/recap/{traktShowId}") {
        val showId = call.parameters["traktShowId"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid show ID"))

        try {
            deps.tokenRepository.getAccessToken()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))

            val body = try { call.receive<RecapRequest>() } catch (_: Exception) { RecapRequest() }
            val apiKey = body.tmdbApiKey.ifBlank {
                deps.settingsRepository.getTmdbApiKey().first()
            }

            if (apiKey.isBlank()) {
                return@post call.respond(
                    HttpStatusCode.PreconditionFailed,
                    ErrorResponse("TMDB API key not configured")
                )
            }

            val tmdbLanguage = LocaleHelper.getTmdbLanguage()

            val shows = deps.showRepository.getShows()
            val watchedEntry = shows.find { it.entry.show.ids.trakt == showId }?.entry
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Show not found"))

            val tmdbId = watchedEntry.show.ids.tmdb
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("No TMDB ID for show"))

            val tmdbShow = deps.tmdbCache.getShow(tmdbId)
                ?: deps.tmdbApiService.getShow(tmdbId, apiKey, language = tmdbLanguage)
                    .also { deps.tmdbCache.putShow(tmdbId, it) }

            val watchedEpisodeRefs = watchedEntry.seasons.flatMap { season ->
                season.episodes.map { ep -> season.number to ep.number }
            }

            val tmdbEpisodes = coroutineScope {
                watchedEpisodeRefs
                    .takeLast(8)
                    .map { (season, episode) ->
                        async {
                            try {
                                deps.tmdbCache.getEpisode(tmdbId, season, episode)
                                    ?: deps.tmdbApiService.getEpisode(tmdbId, season, episode, apiKey, language = tmdbLanguage)
                                        .also { deps.tmdbCache.putEpisode(tmdbId, season, episode, it) }
                            } catch (e: Exception) {
                                DiagnosticLog.warn(TAG, "Failed to load TMDB episode S${season}E${episode}", e)
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

            val targetEpisode = tmdbEpisodes.last()
            val watchedEpisodes = tmdbEpisodes.dropLast(1).ifEmpty { tmdbEpisodes }

            val html = deps.recapGenerator.generateRecap(
                show = tmdbShow,
                watchedEpisodes = watchedEpisodes,
                targetEpisode = targetEpisode
            )
            call.respond(mapOf("html" to html))
        } catch (e: SecurityException) {
            DiagnosticLog.error(TAG, "Keystore unavailable", e)
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Service unavailable"))
        } catch (e: LlmBusyException) {
            DiagnosticLog.warn(TAG, "recap: LLM busy, rejecting request for show $showId", e)
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("LLM busy, try again later"))
        } catch (e: Exception) {
            DiagnosticLog.error(TAG, "Recap generation failed for show $showId", e)
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Recap generation failed"))
        }
    }
}
