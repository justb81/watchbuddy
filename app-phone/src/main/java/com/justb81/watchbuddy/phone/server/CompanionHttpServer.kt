package com.justb81.watchbuddy.phone.server

import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbCache
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.data.ProviderCatalogRepository
import com.justb81.watchbuddy.phone.llm.LlmTitleExtractor
import com.justb81.watchbuddy.phone.llm.RecapGenerator
import com.justb81.watchbuddy.phone.server.routes.AvatarRouteDeps
import com.justb81.watchbuddy.phone.server.routes.CapabilityRouteDeps
import com.justb81.watchbuddy.phone.server.routes.ProviderCatalogRouteDeps
import com.justb81.watchbuddy.phone.server.routes.RecapRouteDeps
import com.justb81.watchbuddy.phone.server.routes.ScrobbleRouteDeps
import com.justb81.watchbuddy.phone.server.routes.ShowsRouteDeps
import com.justb81.watchbuddy.phone.server.routes.WatchedRouteDeps
import com.justb81.watchbuddy.phone.server.routes.avatarRoutes
import com.justb81.watchbuddy.phone.server.routes.capabilityRoutes
import com.justb81.watchbuddy.phone.server.routes.providerCatalogRoutes
import com.justb81.watchbuddy.phone.server.routes.recapRoutes
import com.justb81.watchbuddy.phone.server.routes.scrobbleRoutes
import com.justb81.watchbuddy.phone.server.routes.showsRoutes
import com.justb81.watchbuddy.phone.server.routes.watchedRoutes
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
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CompanionHttpServer"

// Security limits — adjust values before changing the rate-limit tests.
private const val HEAVY_RATE_LIMIT = 6 // req/min for LLM-heavy endpoints
private const val STANDARD_RATE_LIMIT = 60 // req/min for all other endpoints
private const val RATE_LIMIT_WINDOW_MS = 60_000L
private const val MAX_CONCURRENT_PER_IP = 4 // max simultaneous in-flight requests per source IP

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
            val next = if (now - w.start >= windowMs) {
                Window(1, now)
            } else if (w.count >= limit) {
                return false
            } else {
                Window(w.count + 1, w.start)
            }
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
 *   GET  /provider-catalog     → Versioned provider catalog JSON (unauthenticated)
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
 *   GET  /shows/{showId}/seasons → All seasons + episodes for the given show (Trakt)
 *   POST /watched              → Mark a single episode watched in this user's Trakt account
 *   DELETE /watched            → Remove a single episode from this user's Trakt history
 *
 * Route handlers live under [com.justb81.watchbuddy.phone.server.routes].
 */
@Suppress("LongParameterList")
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
    private val providerCatalogRepository: ProviderCatalogRepository,
    private val episodeRepository: EpisodeRepository,
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
                    bearerTokenRepository, providerCatalogRepository, episodeRepository,
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
 *
 * Route handlers are delegated to per-feature extension functions in
 * [com.justb81.watchbuddy.phone.server.routes].
 */
@Suppress("LongParameterList", "LongMethod")
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
    providerCatalogRepository: ProviderCatalogRepository,
    episodeRepository: EpisodeRepository,
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
        val limiter = if (path.startsWith("/recap/") || path == "/scrobble/extract") {
            heavyLimiter
        } else {
            standardLimiter
        }
        if (!limiter.allow(ip)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorBody("Rate limit exceeded"))
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
            call.respond(HttpStatusCode.TooManyRequests, ErrorBody("Too many concurrent requests"))
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
        // /capability and /provider-catalog are intentionally unauthenticated — the TV
        // must be able to reach them before it has received the bearer token from BLE.
        capabilityRoutes(CapabilityRouteDeps(capabilityProvider, stateManager))
        providerCatalogRoutes(ProviderCatalogRouteDeps(providerCatalogRepository))

        authenticate("phone-tv") {
            avatarRoutes(AvatarRouteDeps(avatarImageStore))
            showsRoutes(
                ShowsRouteDeps(
                    showRepository = showRepository,
                    episodeRepository = episodeRepository,
                    tokenRepository = tokenRepository,
                    tokenRefreshManager = tokenRefreshManager,
                    traktApiService = traktApiService,
                )
            )
            recapRoutes(
                RecapRouteDeps(
                    recapGenerator = recapGenerator,
                    showRepository = showRepository,
                    tokenRepository = tokenRepository,
                    tmdbApiService = tmdbApiService,
                    tmdbCache = tmdbCache,
                    settingsRepository = settingsRepository,
                )
            )
            scrobbleRoutes(
                ScrobbleRouteDeps(
                    tokenRefreshManager = tokenRefreshManager,
                    traktApiService = traktApiService,
                    stateManager = stateManager,
                    titleExtractor = titleExtractor,
                )
            )
            watchedRoutes(
                WatchedRouteDeps(
                    tokenRefreshManager = tokenRefreshManager,
                    episodeRepository = episodeRepository,
                )
            )
        }
    }
}

@Serializable
private data class ErrorBody(val error: String)
