package com.justb81.watchbuddy.phone.server

import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbCache
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.RecapGenerator
import com.justb81.watchbuddy.phone.settings.SettingsRepository
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
    private val tmdbApiService: TmdbApiService = mockk()
    private val tmdbCache = TmdbCache()
    private val settingsRepository: SettingsRepository = mockk()

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
                tokenRepository, tmdbApiService, tmdbCache, settingsRepository
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
            coEvery { showRepository.getShows() } returns listOf(watchedEntry)

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
        fun `returns 500 when show repository throws`() = testApp {
            every { tokenRepository.getAccessToken() } returns "test-token"
            coEvery { showRepository.getShows() } throws RuntimeException("Trakt API down")

            val response = client.get("/shows")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertTrue(response.bodyAsText().contains("Trakt API down"))
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
            coEvery { showRepository.getShows() } returns listOf(noTmdbEntry)

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `returns 200 on successful recap generation`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(watchedEntry)
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
        fun `uses show from cache — does not call TMDB getShow`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(watchedEntry)
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
            coEvery { showRepository.getShows() } returns listOf(watchedEntry)
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
        fun `uses episode cache on hit — does not call TMDB getEpisode`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(watchedEntry)
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
            coEvery { showRepository.getShows() } returns listOf(watchedEntry)
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
            coEvery { showRepository.getShows() } returns listOf(watchedEntry)
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
            coEvery { showRepository.getShows() } returns listOf(watchedEntry)
            coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
            coEvery { tmdbApiService.getEpisode(any(), any(), any(), any(), any()) } throws RuntimeException("All fail")

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `returns 503 when recap generation throws`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(watchedEntry)
            coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
            stubSuccessfulEpisodes()
            coEvery { recapGenerator.generateRecap(any(), any(), any(), any()) } throws RuntimeException("LLM crashed")

            val response = client.post("/recap/1") {
                contentType(ContentType.Application.Json)
                setBody("""{"tmdbApiKey":"api-key"}""")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

        @Test
        fun `uses TMDB key from settings when body key is blank`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(watchedEntry)
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
            coEvery { showRepository.getShows() } returns listOf(manyEpisodeEntry)
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
    }

    // ── GET /auth/token ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /auth/token")
    inner class AuthTokenEndpoint {

        @Test
        fun `returns 401 when no access token`() = testApp {
            every { tokenRepository.getAccessToken() } returns null

            val response = client.get("/auth/token")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 200 with access token`() = testApp {
            every { tokenRepository.getAccessToken() } returns "my-secret-token"

            val response = client.get("/auth/token")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("my-secret-token"))
        }
    }
}
