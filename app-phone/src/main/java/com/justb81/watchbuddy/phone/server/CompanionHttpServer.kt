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
import com.justb81.watchbuddy.core.model.TraktIds
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
import com.justb81.watchbuddy.phone.data.ProviderCatalogRepository
import com.justb81.watchbuddy.phone.llm.LlmBusyException
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
import io.ktor.server.routing.delete
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CompanionHttpServer"
private const val DEFAULT_PAGE_SIZE = 30
private const val MAX_PAGE_SIZE = 200

// Security limits — adjust values before changing the rate-limit tests.
private const val HEAVY_RATE_LIMIT = 6 // req/min for LLM-heavy endpoints
private const val STANDARD_RATE_LIMIT = 60 // req/min for all other endpoints
private const val RATE_LIMIT_WINDOW_MS = 60_000L
private const val MAX_CONCURRENT_PER_IP = 4 // max simultaneous in-flight requests per source IP
private const val MAX_EXTRACT_BODY_BYTES = 64 * 1024L // 64 KB cap for /scrobble/extract (#525)
private const val ETAG_HEX_CHARS = 16 // hex chars taken from SHA-256 for ETag prefix

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
        }.onFailure { e -> DiagnosticLog.e(TAG, "Failed to start HTTP server", e) }
    }

    fun stop() {
        server?.stop()
        server = null
    }
}

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
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