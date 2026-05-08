package com.justb81.watchbuddy.phone.server

import com.justb81.watchbuddy.core.locale.LocaleHelper
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.AmbiguousScrobbleEvent
import com.justb81.watchbuddy.core.model.PhoneAddToLibraryRequest
import com.justb81.watchbuddy.core.model.ScrobbleAction
import com.justb81.watchbuddy.core.model.ScrobbleDisplayEvent
import com.justb81.watchbuddy.core.model.TitleExtractionRequest
import com.justb81.watchbuddy.core.model.TitleExtractionResponse
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbCache
import com.justb81.watchbuddy.core.trakt.ScrobbleBody
import com.justb81.watchbuddy.core.trakt.SyncHistoryBody
import com.justb81.watchbuddy.core.trakt.SyncHistoryEpisodeItem
import com.justb81.watchbuddy.core.trakt.SyncHistorySeasonItem
import com.justb81.watchbuddy.core.trakt.SyncHistoryShowItem
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.LlmTitleExtractor
import com.justb81.watchbuddy.phone.llm.RecapGenerator
import com.justb81.watchbuddy.phone.settings.AvatarImageStore
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionStateManager
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

private const val TAG = "CompanionHttpServer"
private const val DEFAULT_PAGE_SIZE = 30
private const val MAX_PAGE_SIZE = 200

// Security limits — adjust values before changing the rate-limit tests.
private const val HEAVY_RATE_LIMIT = 6          // req/min for LLM-heavy endpoints
private const val STANDARD_RATE_LIMIT = 60      // req/min for all other endpoints
private const val RATE_LIMIT_WINDOW_MS = 60_000L
private const val MAX_CONCURRENT_PER_IP = 4     // max simultaneous in-flight requests per source IP
private const val MAX_EXTRACT_BODY_BYTES = 64 * 1024L // 64 KB cap for /scrobble/extract (#525)

/**
 * Fixed-window per-IP rate limiter. Thread-safe and lock-free via CAS.
 *
 * At most [limit] calls are allowed within any [windowMs]-ms window per IP key.
 */
private class IpRateLimiter(val limit: Int, val windowMs: Long) {
    private data class Window(val count: Int, val start: Long)
    private val windows = ConcurrentHashMap<String, AtomicReference<Window>>()

    fun allow(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val ref = windows.getOrPut(ip) { AtomicReference(Window(0, now)) }
        // CAS retry loop: typically executes once; spins only under rare contention.
        while (true) {
            val w = ref.get()
            val next = if (now - w.start >= windowMs) Window(1, now)
                       else if (w.count >= limit) return false
                       else Window(w.count + 1, w.start)
            if (ref.compareAndSet(w, next)) return true
        }
    }
}

/**
 * Local HTTP server running on the phone (port 8765).
 * The TV discovers this via BLE advertisement and calls its endpoints over plain HTTP.
 *
 * Authentication:
 *   All endpoints except `/capability` require `Authorization: Bearer <token>`.
 *   The bearer token is distributed to the TV via the BLE scan-response payload
 *   (see [BearerTokenRepository]). `/capability` is intentionally unauthenticated
 *   so the TV can call it before it has loaded the token from BLE.
 *
 * Endpoints:
 *   GET  /capability           → DeviceCapability (unauthenticated)
 *   GET  /shows                → List of watched shows for this user (from Trakt cache)
 *   POST /recap/{traktShowId}  → Generate + return HTML recap for a show
 *   POST /scrobble/start       → Forward scrobble start to this user's Trakt account
 *   POST /scrobble/pause       → Forward scrobble pause to this user's Trakt account
 *   POST /scrobble/stop        → Forward scrobble stop to this user's Trakt account
 *   POST /scrobble/extract     → LLM fallback — normalize raw MediaSession
 *                                metadata into (showTitle, season?, episode?)
 *   POST /scrobble/prompt      → Deliver ambiguous-scrobble prompt; consumed via state stream
 *   POST /shows/add-to-library → Add an episode to Trakt history (unknown-show overlay confirm)
 *   GET  /avatar               → Custom user avatar JPEG
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
    private val avatarImageStore: AvatarImageStore,
    private val stateManager: CompanionStateManager,
    private val titleExtractor: LlmTitleExtractor,
    private val bearerTokenRepository: BearerTokenRepository,
) {
    companion object {
        const val PORT = 8765
    }

    private var server: EmbeddedServer<*, *>? = null

    /**
     * Starts the Ktor/Netty server bound to [host].
     *
     * [host] should be the phone's current Wi-Fi IPv4 address so that the server
     * is only reachable on the LAN interface and not on VPN tunnels, USB-tethering
     * adapters, or hotspot clients (#525). The caller is responsible for stopping
     * and restarting whenever the Wi-Fi interface changes.
     */
    fun start(host: String) {
        if (server != null) return
        runCatching {
            server = embeddedServer(Netty, host = host, port = PORT) {
                configureCompanionRoutes(
                    recapGenerator, capabilityProvider, showRepository,
                    tokenRepository, tokenRefreshManager, traktApiService, tmdbApiService, tmdbCache,
                    settingsRepository, avatarImageStore, stateManager, titleExtractor,
                    bearerTokenRepository,
                )
            }.start(wait = false)
        }.onFailure {
            DiagnosticLog.error(TAG, "Netty bind $host:$PORT failed", it)
        }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        server = null
        DiagnosticLog.event(TAG, "Netty stopped")
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
    avatarImageStore: AvatarImageStore,
    stateManager: CompanionStateManager,
    titleExtractor: LlmTitleExtractor,
    bearerTokenRepository: BearerTokenRepository,
) {
    val expectedToken = bearerTokenRepository.token

    install(Authentication) {
        bearer("phone-tv") {
            authenticate { credential ->
                if (credential.token == expectedToken) UserIdPrincipal("tv") else null
            }
        }
    }
    install(ContentNegotiation) {
        json(WatchBuddyJson)
    }
    // Request-level breadcrumb. Only method / path / status / latency — never
    // body or query to avoid leaking tokens or TMDB/Trakt identifiers. This
    // makes connectivity issues (TV calling /capability but getting 500, TV
    // never reaching /scrobble/start, etc.) visible in shared diagnostics.
    intercept(ApplicationCallPipeline.Monitoring) {
        val started = System.currentTimeMillis()
        try {
            proceed()
        } finally {
            val path = call.request.path()
            val status = call.response.status()?.value ?: 0
            val latency = System.currentTimeMillis() - started
            DiagnosticLog.event(
                TAG,
                "${call.request.httpMethod.value} $path → $status ${latency}ms"
            )
        }
    }
    // Per-IP rate limiter: heavy endpoints (LLM) get 6 req/min; everything
    // else gets 60 req/min. Runs before auth so even unauthenticated bursts
    // on /capability are bounded (#525).
    val standardLimiter = IpRateLimiter(limit = STANDARD_RATE_LIMIT, windowMs = RATE_LIMIT_WINDOW_MS)
    val heavyLimiter = IpRateLimiter(limit = HEAVY_RATE_LIMIT, windowMs = RATE_LIMIT_WINDOW_MS)
    intercept(ApplicationCallPipeline.Plugins) {
        val ip = call.request.local.remoteAddress
        val path = call.request.path()
        val limiter = if (path.startsWith("/recap/") || path == "/scrobble/extract") heavyLimiter
                      else standardLimiter
        if (!limiter.allow(ip)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Rate limit exceeded"))
            finish()
            return@intercept
        }
        proceed()
    }
    // Per-IP concurrent-request limiter. Guards the LLM thread pool and Netty
    // workers against a single peer issuing many parallel requests (#525).
    val activeRequestsPerIp = ConcurrentHashMap<String, AtomicInteger>()
    intercept(ApplicationCallPipeline.Plugins) {
        val ip = call.request.local.remoteAddress
        val counter = activeRequestsPerIp.getOrPut(ip) { AtomicInteger(0) }
        if (counter.incrementAndGet() > MAX_CONCURRENT_PER_IP) {
            counter.decrementAndGet()
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many concurrent requests"))
            finish()
            return@intercept
        }
        try {
            proceed()
        } finally {
            if (counter.decrementAndGet() <= 0) activeRequestsPerIp.remove(ip)
        }
    }
    routing {
        // /capability is intentionally unauthenticated — the TV must be able
        // to reach it before it has received the bearer token from BLE.
        get("/capability") {
            stateManager.onCapabilityChecked()
            call.respond(capabilityProvider.getCapability())
        }

        authenticate("phone-tv") {
        get("/avatar") {
            val file = avatarImageStore.file()
            if (!avatarImageStore.exists()) {
                return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("No custom avatar"))
            }
            val etag = "\"${sha256Hex(file.readBytes())}\""
            if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
                return@get call.respond(HttpStatusCode.NotModified)
            }
            call.response.header(HttpHeaders.CacheControl, "private, max-age=60")
            call.response.header(HttpHeaders.ETag, etag)
            call.respondFile(file)
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
                DiagnosticLog.error(TAG, "Keystore unavailable", e)
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Service unavailable"))
            } catch (e: Exception) {
                DiagnosticLog.error(TAG, "Failed to fetch shows", e)
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

                // Use last episode as the "target" (next to watch)
                val targetEpisode = tmdbEpisodes.last()
                val watchedEpisodes = tmdbEpisodes.dropLast(1).ifEmpty { tmdbEpisodes }

                val html = recapGenerator.generateRecap(
                    show = tmdbShow,
                    watchedEpisodes = watchedEpisodes,
                    targetEpisode = targetEpisode
                )
                call.respond(mapOf("html" to html))
            } catch (e: SecurityException) {
                DiagnosticLog.error(TAG, "Keystore unavailable", e)
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Service unavailable"))
            } catch (e: Exception) {
                DiagnosticLog.error(TAG, "Recap generation failed for show $showId", e)
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Recap generation failed"))
            }
        }

        ScrobbleAction.entries.forEach { action ->
            post("/scrobble/${action.name.lowercase()}") {
                call.handleScrobble(action, tokenRefreshManager, traktApiService, stateManager)
            }
        }

        post("/scrobble/extract") {
            // Reject oversized bodies before reading — guards against metadata blobs
            // exhausting the LLM thread pool memory (#525).
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
            // Old TV clients that pre-date the text-blob migration send the snapshot
            // with named fields and no `text` field; WatchBuddyJson ignores unknown
            // keys and defaults `text` to "". Detect this and return confidence=0
            // gracefully instead of running inference on empty evidence.
            if (body.snapshot.text.isBlank()) {
                DiagnosticLog.warn(TAG, "extract: empty snapshot text — likely old-format client, returning confidence=0")
                return@post call.respond(TitleExtractionResponse(confidence = 0f))
            }
            try {
                val response = titleExtractor.extract(body.snapshot, body.libraryHints)
                    ?: TitleExtractionResponse(confidence = 0f)
                call.respond(response)
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
            stateManager.onAmbiguousPrompt(event)
            DiagnosticLog.event(
                TAG,
                "ambiguous prompt received sessionKey='${event.sessionKey}' candidates=${event.candidates.size}",
            )
            call.respond(HttpStatusCode.NoContent)
        }

        post("/shows/add-to-library") {
            val token = tokenRefreshManager.getValidAccessToken()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("No access token"))
            val body = try {
                call.receive<PhoneAddToLibraryRequest>()
            } catch (_: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
            }
            try {
                val syncBody = SyncHistoryBody(
                    shows = listOf(
                        SyncHistoryShowItem(
                            ids = body.show.ids,
                            seasons = listOf(
                                SyncHistorySeasonItem(
                                    number = body.episode.season,
                                    episodes = listOf(SyncHistoryEpisodeItem(number = body.episode.number))
                                )
                            )
                        )
                    )
                )
                traktApiService.addToHistory("Bearer $token", syncBody)
                showRepository.invalidateCache()
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
        } // authenticate("phone-tv")
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
        DiagnosticLog.event(
            TAG,
            "scrobble ${action.name.lowercase()} ok show=${body.show.title} " +
                "S${body.episode.season}E${body.episode.number} progress=${body.progress}"
        )
        respond(ScrobbleActionResponse(success = true))
    } catch (e: Exception) {
        DiagnosticLog.error(TAG, "scrobble ${action.name.lowercase()} failed", e)
        respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Scrobble failed"))
    }
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}

@Serializable
private data class RecapRequest(
    val tmdbApiKey: String = ""
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

@Serializable
private data class AddToLibraryResponse(
    val success: Boolean
)
