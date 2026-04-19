package com.justb81.watchbuddy.phone.server

import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.model.ScrobbleAction
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbCache
import com.justb81.watchbuddy.core.trakt.ScrobbleResponse
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.RecapGenerator
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionStateManager
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CompanionHttpServer routes")
class CompanionHttpServerTest {

    // ── Mocked dependencies ───────────────────────────────────────────────────

    private val recapGenerator: RecapGenerator = mockk()
    private val capabilityProvider: DeviceCapabilityProvider = mockk()
    private val showRepository: ShowRepository = mockk()
    private val tokenRepository: TokenRepository = mockk()
    private val tokenRefreshManager: TokenRefreshManager = mockk()
    private val traktApiService: TraktApiService = mockk()
    private val tmdbApiService: TmdbApiService = mockk()
    private val tmdbCache = TmdbCache()
    private val settingsRepository: SettingsRepository = mockk()
    private val stateManager = CompanionStateManager()

    // ── Shared test fixtures ──────────────────────────────────────────────────

    private val capability = DeviceCapability(
        deviceId = "dev-1",
        userName = "alice",
        deviceName = "Pixel 9",
        llmBackend = LlmBackend.LITERT,
        modelQuality = 75,
        freeRamMb = 4096,
        isAvailable = true,
        tmdbConfigured = true
    )

    private val tmdbShow = TmdbShow(100, "Breaking Bad", "A chemistry teacher turns to crime.")

    /** TraktWatchedEntry whose trakt ID = 1 and TMDB ID = 100, with 3 watched episodes. */
    private val watchedEntry = TraktWatchedEntry(
        show = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1, tmdb = 100)),
        seasons = listOf(
            TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1), TraktWatchedEpisode(2))),
            TraktWatchedSeason(2, listOf(TraktWatchedEpisode(1)))
        )
    )
    private val enrichedEntry = EnrichedShowEntry(entry = watchedEntry)

    private val ep1 = TmdbEpisode(1, "Pilot", "First episode.", "/s1e1.jpg", 1, 1)
    private val ep2 = TmdbEpisode(2, "Cat's in the Bag", null, null, 1, 2)
    private val ep3 = TmdbEpisode(3, "Seven Thirty-Seven", null, null, 2, 1)

    @BeforeEach
    fun setUp() {
        clearAllMocks()
        tmdbCache.clear()
    }

    // ── Helper: configure test application with all mocked dependencies ───────

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureCompanionRoutes(
                recapGenerator, capabilityProvider, showRepository,
                tokenRepository, tokenRefreshManager, traktApiService, tmdbApiService, tmdbCache, settingsRepository,
                stateManager
            )
        }
        block()
    }

    // ── GET /capability ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /capability")
    inner class CapabilityEndpoint {

        @Test
        fun `returns 200 with capability JSON`() = testApp {
            coEvery { capabilityProvider.getCapability() } returns capability

            val response = client.get("/capability")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"userName\":\"alice\""))
            assertTrue(body.contains("\"modelQuality\":75"))
        }
    }

    // ── GET /shows ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /shows")
    inner class ShowsEndpoint {

        @Test
        fun `returns 401 when no access token`() = testApp {
            every { tokenRepository.getAccessToken() } returns null

            val response = client.get("/shows")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 200 with shows list when authenticated`() = testApp {
            every { tokenRepository.getAccessToken() } returns "test-token"
            coEvery { showRepository.getShows() } returns listOf(enrichedEntry)

            val response = client.get("/shows")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Breaking Bad"))
        }

        @Test
        fun `returns 200 with empty list when no shows`() = testApp {
            every { tokenRepository.getAccessToken() } returns "test-token"
            coEvery { showRepository.getShows() } returns emptyList()

            val response = client.get("/shows")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("[]", response.bodyAsText())
        }

        @Test
        fun `returns 500 when show repository throws - no exception detail leaked`() = testApp {
            every { tokenRepository.getAccessToken() } returns "test-token"
            coEvery { showRepository.getShows() } throws RuntimeException("Trakt API down")

            val response = client.get("/shows")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertTrue(response.bodyAsText().contains("Internal server error"))
            assertFalse(response.bodyAsText().contains("Trakt API down"))
        }

        @Test
        fun `returns first page when limit param is provided`() = testApp {
            every { tokenRepository.getAccessToken() } returns "test-token"
            val shows = (1..5).map { i ->
                TraktWatchedEntry(TraktShow("Show $i", 2020 + i, TraktIds(trakt = i)))
            }
            coEvery { showRepository.getShows() } returns shows.map { EnrichedShowEntry(entry = it) }

            val response = client.get("/shows?limit=2")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Show 1"))
            assertTrue(body.contains("Show 2"))
            assertFalse(body.contains("Show 3"))
        }

        @Test
        fun `returns correct page when offset and limit are provided`() = testApp {
            every { tokenRepository.getAccessToken() } returns "test-token"
            val shows = (1..5).map { i ->
                TraktWatchedEntry(TraktShow("Show $i", 2020 + i, TraktIds(trakt = i)))
            }
            coEvery { showRepository.getShows() } returns shows.map { EnrichedShowEntry(entry = it) }

            val response = client.get("/shows?offset=2&limit=2")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains("Show 1"))
            assertFalse(body.contains("Show 2"))
            assertTrue(body.contains("Show 3"))
            assertTrue(body.contains("Show 4"))
            assertFalse(body.contains("Show 5"))
        }

        @Test
        fun `returns empty list when offset exceeds total shows`() = testApp {
            every { tokenRepository.getAccessToken() } returns "test-token"
            val shows = (1..3).map { i ->
                TraktWatchedEntry(TraktShow("Show $i", 2020 + i, TraktIds(trakt = i)))
            }
            coEvery { showRepository.getShows() } returns shows.map { EnrichedShowEntry(entry = it) }

            val response = client.get("/shows?offset=10&limit=5")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("[]", response.bodyAsText())
        }

        @Test
        fun `defaults to first 30 shows when no params provided`() = testApp {
            every { tokenRepository.getAccessToken() } returns "test-token"
            // Create 35 shows - only first 30 should be returned
            val shows = (1..35).map { i ->
                TraktWatchedEntry(TraktShow("Show $i", 2020, TraktIds(trakt = i)))
            }
            coEvery { showRepository.getShows() } returns shows.map { EnrichedShowEntry(entry = it) }

            val response = client.get("/shows")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Show 1"))
            assertTrue(body.contains("Show 30"))
            assertFalse(body.contains("Show 31"))
        }

        @Test
        fun `clamps negative offset to 0`() = testApp {
            every { tokenRepository.getAccessToken() } returns "test-token"
            val shows = (1..3).map { i ->
                TraktWatchedEntry(TraktShow("Show $i", 2020 + i, TraktIds(trakt = i)))
            }
            coEvery { showRepository.getShows() } returns shows.map { EnrichedShowEntry(entry = it) }

            val response = client.get("/shows?offset=-5&limit=2")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Show 1"))
            assertTrue(body.contains("Show 2"))
        }

        @Test
        fun `ignores invalid non-numeric offset and limit`() = testApp {
            every { tokenRepository.getAccessToken() } returns "test-token"
            coEvery { showRepository.getShows() } returns listOf(enrichedEntry)

            val response = client.get("/shows?offset=abc&limit=xyz")

            // Falls back to defaults (offset=0, limit=30)
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Breaking Bad"))
        }

        @Test
        fun `returns 503 when getAccessToken throws SecurityException`() = testApp {
            every { tokenRepository.getAccessToken() } throws
                SecurityException("Keystore operation failed")

            val response = client.get("/shows")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("Service unavailable"))
        }
    }

    // ── POST /recap/{traktShowId} ─────────────────────────────────────────────

    @Nested
    @DisplayName("POST /recap/{traktShowId}")
    inner class RecapEndpoint {

        private fun stubSuccessfulEpisodes(apiKey: String = "api-key") {
            coEvery { tmdbApiService.getEpisode(100, 1, 1, apiKey, any()) } returns ep1
            coEvery { tmdbApiService.getEpisode(100, 1, 2, apiKey, any()) } returns ep2
            coEvery { tmdbApiService.getEpisode(100, 2, 1, apiKey, any()) } returns ep3
        }

        @Test
        fun `returns 400 for non-numeric show ID`() = testApp {
            val response = client.post("/recap/not-a-number")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `returns 401 when no access token`() = testApp {
            every { tokenRepository.getAccessToken() } returns null

            val response = client.post("/recap/1")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 412 when TMDB key absent from body and settings`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            every { settingsRepository.getTmdbApiKey() } returns flowOf("")

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":""}""")
            }

            assertEquals(HttpStatusCode.PreconditionFailed, response.status)
        }

        @Test
        fun `returns 404 when show not in watched list`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns emptyList()

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `returns 404 when show has no TMDB ID`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            val noTmdbEntry = TraktWatchedEntry(
                show = TraktShow("No TMDB Show", 2020, TraktIds(trakt = 1, tmdb = null)),
                seasons = listOf(TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1))))
            )
            coEvery { showRepository.getShows() } returns listOf(EnrichedShowEntry(entry = noTmdbEntry))

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `returns 200 on successful recap generation`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(enrichedEntry)
            coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
            stubSuccessfulEpisodes()
            coEvery { recapGenerator.generateRecap(any(), any(), any(), any()) } returns "<div>Recap HTML</div>"

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Recap HTML"))
        }

        @Test
        fun `uses show from cache - does not call TMDB getShow`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(enrichedEntry)
            tmdbCache.putShow(100, tmdbShow)
            stubSuccessfulEpisodes()
            coEvery { recapGenerator.generateRecap(any(), any(), any(), any()) } returns "<div>ok</div>"

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify(exactly = 0) { tmdbApiService.getShow(any(), any(), any()) }
        }

        @Test
        fun `fetches show from TMDB on cache miss and stores it in cache`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(enrichedEntry)
            coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
            stubSuccessfulEpisodes()
            coEvery { recapGenerator.generateRecap(any(), any(), any(), any()) } returns "<div>ok</div>"

            client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            coVerify(exactly = 1) { tmdbApiService.getShow(100, "api-key", any()) }
            assertNotNull(tmdbCache.getShow(100))
            assertEquals("Breaking Bad", tmdbCache.getShow(100)!!.name)
        }

        @Test
        fun `uses episode cache on hit - does not call TMDB getEpisode`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(enrichedEntry)
            coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
            // Pre-populate episode cache
            tmdbCache.putEpisode(100, 1, 1, ep1)
            tmdbCache.putEpisode(100, 1, 2, ep2)
            tmdbCache.putEpisode(100, 2, 1, ep3)
            coEvery { recapGenerator.generateRecap(any(), any(), any(), any()) } returns "<div>ok</div>"

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify(exactly = 0) { tmdbApiService.getEpisode(any(), any(), any(), any(), any()) }
        }

        @Test
        fun `fetches episodes from TMDB on cache miss and stores them in cache`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(enrichedEntry)
            coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
            stubSuccessfulEpisodes()
            coEvery { recapGenerator.generateRecap(any(), any(), any(), any()) } returns "<div>ok</div>"

            client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            // All 3 episodes fetched from API
            coVerify(exactly = 1) { tmdbApiService.getEpisode(100, 1, 1, "api-key", any()) }
            coVerify(exactly = 1) { tmdbApiService.getEpisode(100, 1, 2, "api-key", any()) }
            coVerify(exactly = 1) { tmdbApiService.getEpisode(100, 2, 1, "api-key", any()) }
            // All 3 episodes now in cache
            assertNotNull(tmdbCache.getEpisode(100, 1, 1))
            assertNotNull(tmdbCache.getEpisode(100, 1, 2))
            assertNotNull(tmdbCache.getEpisode(100, 2, 1))
        }

        @Test
        fun `returns 200 when one episode fails but others succeed`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(enrichedEntry)
            coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
            coEvery { tmdbApiService.getEpisode(100, 1, 1, "api-key", any()) } returns ep1
            coEvery { tmdbApiService.getEpisode(100, 1, 2, "api-key", any()) } throws RuntimeException("Network error")
            coEvery { tmdbApiService.getEpisode(100, 2, 1, "api-key", any()) } returns ep3
            coEvery { recapGenerator.generateRecap(any(), any(), any(), any()) } returns "<div>ok</div>"

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
        }

        @Test
        fun `returns 404 when all episodes fail to load`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(enrichedEntry)
            coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
            coEvery { tmdbApiService.getEpisode(any(), any(), any(), any(), any()) } throws RuntimeException("All fail")

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `returns 503 when recap generation throws - no exception detail leaked`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(enrichedEntry)
            coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
            stubSuccessfulEpisodes()
            coEvery { recapGenerator.generateRecap(any(), any(), any(), any()) } throws RuntimeException("LLM crashed")

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("Recap generation failed"))
            assertFalse(response.bodyAsText().contains("LLM crashed"))
        }

        @Test
        fun `uses TMDB key from settings when body key is blank`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(enrichedEntry)
            every { settingsRepository.getTmdbApiKey() } returns flowOf("settings-key")
            coEvery { tmdbApiService.getShow(100, "settings-key", any()) } returns tmdbShow
            coEvery { tmdbApiService.getEpisode(100, 1, 1, "settings-key", any()) } returns ep1
            coEvery { tmdbApiService.getEpisode(100, 1, 2, "settings-key", any()) } returns ep2
            coEvery { tmdbApiService.getEpisode(100, 2, 1, "settings-key", any()) } returns ep3
            coEvery { recapGenerator.generateRecap(any(), any(), any(), any()) } returns "<div>ok</div>"

            // Send no body → key must come from settings
            val response = client.post("/recap/1")

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify { tmdbApiService.getShow(100, "settings-key", any()) }
        }

        @Test
        fun `limits episode fetches to the last 8 watched episodes`() = testApp {
            // Build an entry with 10 watched episodes across seasons
            val manyEpisodeEntry = TraktWatchedEntry(
                show = TraktShow("Long Show", 2010, TraktIds(trakt = 1, tmdb = 100)),
                seasons = listOf(
                    TraktWatchedSeason(1, (1..5).map { TraktWatchedEpisode(it) }),
                    TraktWatchedSeason(2, (1..5).map { TraktWatchedEpisode(it) })
                )
            )
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(EnrichedShowEntry(entry = manyEpisodeEntry))
            coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
            // Only stub the last 8 episodes (S1E3..S1E5, S2E1..S2E5)
            coEvery { tmdbApiService.getEpisode(100, 1, 3, "api-key", any()) } returns TmdbEpisode(3, "S1E3", null, null, 1, 3)
            coEvery { tmdbApiService.getEpisode(100, 1, 4, "api-key", any()) } returns TmdbEpisode(4, "S1E4", null, null, 1, 4)
            coEvery { tmdbApiService.getEpisode(100, 1, 5, "api-key", any()) } returns TmdbEpisode(5, "S1E5", null, null, 1, 5)
            coEvery { tmdbApiService.getEpisode(100, 2, 1, "api-key", any()) } returns TmdbEpisode(6, "S2E1", null, null, 2, 1)
            coEvery { tmdbApiService.getEpisode(100, 2, 2, "api-key", any()) } returns TmdbEpisode(7, "S2E2", null, null, 2, 2)
            coEvery { tmdbApiService.getEpisode(100, 2, 3, "api-key", any()) } returns TmdbEpisode(8, "S2E3", null, null, 2, 3)
            coEvery { tmdbApiService.getEpisode(100, 2, 4, "api-key", any()) } returns TmdbEpisode(9, "S2E4", null, null, 2, 4)
            coEvery { tmdbApiService.getEpisode(100, 2, 5, "api-key", any()) } returns TmdbEpisode(10, "S2E5", null, null, 2, 5)
            coEvery { recapGenerator.generateRecap(any(), any(), any(), any()) } returns "<div>ok</div>"

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            // First 2 episodes (S1E1, S1E2) should NOT be fetched
            coVerify(exactly = 0) { tmdbApiService.getEpisode(100, 1, 1, any(), any()) }
            coVerify(exactly = 0) { tmdbApiService.getEpisode(100, 1, 2, any(), any()) }
        }

        @Test
        fun `returns 503 when getAccessToken throws SecurityException`() = testApp {
            every { tokenRepository.getAccessToken() } throws
                SecurityException("Keystore operation failed")

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("Service unavailable"))
        }
    }

    // ── GET /auth/token ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /auth/token")
    inner class AuthTokenEndpoint {

        @Test
        fun `returns 401 when token refresh returns null`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns null

            val response = client.get("/auth/token")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 200 with valid access token after refresh`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "my-secret-token"

            val response = client.get("/auth/token")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("my-secret-token"))
        }
    }

    // ── POST /scrobble/start, /scrobble/pause, /scrobble/stop ────────────────

    @Nested
    @DisplayName("POST /scrobble/*")
    inner class ScrobbleEndpoints {

        private val show = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1))
        private val episode = TraktEpisode(season = 1, number = 1)
        private val scrobbleBody = """{"show":{"title":"Breaking Bad","year":2008,"ids":{"trakt":1}},"episode":{"season":1,"number":1,"ids":{}},"progress":0.0}"""

        private fun stubSuccessfulScrobbleStart() {
            coEvery { traktApiService.scrobbleStart(any(), any()) } returns ScrobbleResponse(
                id = 1L, action = "start", progress = 0f, show = show, episode = episode
            )
        }

        private fun stubSuccessfulScrobblePause() {
            coEvery { traktApiService.scrobblePause(any(), any()) } returns ScrobbleResponse(
                id = 1L, action = "pause", progress = 50f, show = show, episode = episode
            )
        }

        private fun stubSuccessfulScrobbleStop() {
            coEvery { traktApiService.scrobbleStop(any(), any()) } returns ScrobbleResponse(
                id = 1L, action = "stop", progress = 100f, show = show, episode = episode
            )
        }

        @Test
        fun `scrobble start returns 401 when no access token`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns null

            val response = client.post("/scrobble/start") {
                contentType(ContentType.Application.Json)
                setBody(scrobbleBody)
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `scrobble start returns 400 when body is invalid`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"

            val response = client.post("/scrobble/start") {
                contentType(ContentType.Application.Json)
                setBody("not-valid-json")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `scrobble start returns 200 and calls Trakt API`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
            stubSuccessfulScrobbleStart()

            val response = client.post("/scrobble/start") {
                contentType(ContentType.Application.Json)
                setBody(scrobbleBody)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
            coVerify { traktApiService.scrobbleStart("Bearer test-token", any()) }
        }

        @Test
        fun `scrobble start returns 503 when Trakt API throws`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            coEvery { traktApiService.scrobbleStart(any(), any()) } throws RuntimeException("Trakt down")

            val response = client.post("/scrobble/start") {
                contentType(ContentType.Application.Json)
                setBody(scrobbleBody)
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertFalse(response.bodyAsText().contains("Trakt down"))
        }

        @Test
        fun `scrobble pause returns 401 when no access token`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns null

            val response = client.post("/scrobble/pause") {
                contentType(ContentType.Application.Json)
                setBody(scrobbleBody)
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `scrobble pause returns 200 and calls Trakt API`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
            stubSuccessfulScrobblePause()

            val pauseBody = """{"show":{"title":"Breaking Bad","year":2008,"ids":{"trakt":1}},"episode":{"season":1,"number":1,"ids":{}},"progress":50.0}"""
            val response = client.post("/scrobble/pause") {
                contentType(ContentType.Application.Json)
                setBody(pauseBody)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify { traktApiService.scrobblePause("Bearer test-token", any()) }
        }

        @Test
        fun `scrobble stop returns 401 when no access token`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns null

            val response = client.post("/scrobble/stop") {
                contentType(ContentType.Application.Json)
                setBody(scrobbleBody)
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `scrobble stop returns 200 and calls Trakt API`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
            stubSuccessfulScrobbleStop()

            val stopBody = """{"show":{"title":"Breaking Bad","year":2008,"ids":{"trakt":1}},"episode":{"season":1,"number":1,"ids":{}},"progress":100.0}"""
            val response = client.post("/scrobble/stop") {
                contentType(ContentType.Application.Json)
                setBody(stopBody)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify { traktApiService.scrobbleStop("Bearer test-token", any()) }
        }

        @Test
        fun `scrobble stop returns 503 when Trakt API throws - no exception detail leaked`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            coEvery { traktApiService.scrobbleStop(any(), any()) } throws RuntimeException("Network error")

            val stopBody = """{"show":{"title":"Breaking Bad","year":2008,"ids":{"trakt":1}},"episode":{"season":1,"number":1,"ids":{}},"progress":100.0}"""
            val response = client.post("/scrobble/stop") {
                contentType(ContentType.Application.Json)
                setBody(stopBody)
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertFalse(response.bodyAsText().contains("Network error"))
        }

        @Test
        fun `scrobble start updates stateManager with START action`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            stubSuccessfulScrobbleStart()

            client.post("/scrobble/start") {
                contentType(ContentType.Application.Json)
                setBody(scrobbleBody)
            }

            assertEquals(ScrobbleAction.START, stateManager.lastScrobbleEvent.value?.action)
        }

        @Test
        fun `scrobble pause updates stateManager with PAUSE action`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            stubSuccessfulScrobblePause()

            client.post("/scrobble/pause") {
                contentType(ContentType.Application.Json)
                setBody(scrobbleBody)
            }

            assertEquals(ScrobbleAction.PAUSE, stateManager.lastScrobbleEvent.value?.action)
        }

        @Test
        fun `scrobble stop updates stateManager with STOP action`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            stubSuccessfulScrobbleStop()

            client.post("/scrobble/stop") {
                contentType(ContentType.Application.Json)
                setBody(scrobbleBody)
            }

            assertEquals(ScrobbleAction.STOP, stateManager.lastScrobbleEvent.value?.action)
        }

        @Test
        fun `scrobble pause returns 400 when body is invalid`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"

            val response = client.post("/scrobble/pause") {
                contentType(ContentType.Application.Json)
                setBody("not-valid-json")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `scrobble stop returns 400 when body is invalid`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"

            val response = client.post("/scrobble/stop") {
                contentType(ContentType.Application.Json)
                setBody("not-valid-json")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }
}
