package com.justb81.watchbuddy.phone.server

import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.tmdb.TmdbCache
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.data.ProviderCatalogRepository
import com.justb81.watchbuddy.phone.llm.LlmTitleExtractor
import com.justb81.watchbuddy.phone.llm.RecapGenerator
import com.justb81.watchbuddy.phone.settings.AvatarImageStore
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionStateManager
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("CompanionHttpServer — /shows/{id}/seasons, /watched endpoints")
class CompanionHttpServerWatchedTest {

    @TempDir
    lateinit var tempDir: File

    private val recapGenerator: RecapGenerator = mockk()
    private val capabilityProvider: DeviceCapabilityProvider = mockk()
    private val showRepository: ShowRepository = mockk()
    private val tokenRepository: TokenRepository = mockk()
    private val tokenRefreshManager: TokenRefreshManager = mockk()
    private val traktApiService: TraktApiService = mockk()
    private val tmdbApiService: TmdbApiService = mockk()
    private val tmdbCache = TmdbCache()
    private val settingsRepository: SettingsRepository = mockk()
    private val avatarImageStore: AvatarImageStore = mockk(relaxed = true)
    private val stateManager = CompanionStateManager()
    private val titleExtractor: LlmTitleExtractor = mockk(relaxed = true)
    private val testToken = "test-bearer-token"
    private val bearerTokenRepository: BearerTokenRepository = mockk()
    private val providerCatalogRepository: ProviderCatalogRepository = mockk(relaxed = true)
    private val episodeRepository: EpisodeRepository = mockk(relaxed = true)

    private val seasonFixture = listOf(
        TraktSeasonWithEpisodes(
            number = 1,
            episodes = listOf(
                TraktEpisode(1, 1, "Pilot"),
                TraktEpisode(1, 2, "Episode 2"),
            )
        )
    )

    @BeforeEach
    fun setUp() {
        clearAllMocks()
        every { bearerTokenRepository.token } returns testToken
        every { avatarImageStore.exists() } returns false
    }

    inner class TestScope(builder: ApplicationTestBuilder) {
        val unauthClient: HttpClient = builder.client
        val client: HttpClient = builder.createClient {}.also { httpClient ->
            httpClient.plugin(HttpSend).intercept { request ->
                request.headers.append(HttpHeaders.Authorization, "Bearer $testToken")
                execute(request)
            }
        }
    }

    private fun testApp(block: suspend TestScope.() -> Unit) = testApplication {
        application {
            configureCompanionRoutes(
                recapGenerator, capabilityProvider, showRepository,
                tokenRepository, tokenRefreshManager, traktApiService, tmdbApiService, tmdbCache,
                settingsRepository, avatarImageStore, stateManager, titleExtractor,
                bearerTokenRepository, providerCatalogRepository, episodeRepository,
            )
        }
        TestScope(this).block()
    }

    // ── GET /shows/{showId}/seasons ───────────────────────────────────────────

    @Nested
    @DisplayName("GET /shows/{showId}/seasons")
    inner class GetSeasonsEndpoint {

        @Test
        fun `returns 200 with seasons JSON`() = testApp {
            every { tokenRepository.getAccessToken() } returns "trakt-token"
            coEvery { episodeRepository.getSeasonsWithEpisodes("1234") } returns seasonFixture

            val response = client.get("/shows/1234/seasons")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"number\":1"))
            assertTrue(body.contains("\"Pilot\""))
        }

        @Test
        fun `returns 401 when no bearer token`() = testApp {
            val response = unauthClient.get("/shows/1234/seasons")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 401 when no Trakt access token`() = testApp {
            every { tokenRepository.getAccessToken() } returns null

            val response = client.get("/shows/1234/seasons")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 500 on repository failure`() = testApp {
            every { tokenRepository.getAccessToken() } returns "trakt-token"
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } throws RuntimeException("DB error")

            val response = client.get("/shows/1234/seasons")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }

        @Test
        fun `returns 503 on SecurityException`() = testApp {
            every { tokenRepository.getAccessToken() } returns "trakt-token"
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } throws SecurityException("Keystore locked")

            val response = client.get("/shows/1234/seasons")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

        @Test
        fun `passes the showId path parameter correctly`() = testApp {
            every { tokenRepository.getAccessToken() } returns "trakt-token"
            val slot = slot<String>()
            coEvery { episodeRepository.getSeasonsWithEpisodes(capture(slot)) } returns emptyList()

            client.get("/shows/show-abc/seasons")

            assertEquals("show-abc", slot.captured)
        }
    }

    // ── POST /watched ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /watched")
    inner class MarkWatchedEndpoint {

        private val validBody = """
            {"showIds":{"trakt":1},"season":2,"episode":3}
        """.trimIndent()

        @Test
        fun `returns 200 on success`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "trakt-token"
            coEvery { episodeRepository.markEpisodeWatched(any(), any(), any()) } returns Result.success(Unit)

            val response = client.post("/watched") {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"success\":true"))
        }

        @Test
        fun `returns 401 when no bearer token`() = testApp {
            val response = unauthClient.post("/watched") {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 401 when no Trakt access token`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns null

            val response = client.post("/watched") {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 400 on malformed body`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "trakt-token"

            val response = client.post("/watched") {
                contentType(ContentType.Application.Json)
                setBody("not-json")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `returns 503 when repository fails`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "trakt-token"
            coEvery { episodeRepository.markEpisodeWatched(any(), any(), any()) } returns
                Result.failure(RuntimeException("Trakt error"))

            val response = client.post("/watched") {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

        @Test
        fun `captures season and episode correctly`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "trakt-token"
            val seasonSlot = slot<Int>()
            val episodeSlot = slot<Int>()
            coEvery {
                episodeRepository.markEpisodeWatched(any(), capture(seasonSlot), capture(episodeSlot))
            } returns Result.success(Unit)

            client.post("/watched") {
                contentType(ContentType.Application.Json)
                setBody("""{"showIds":{"trakt":5},"season":3,"episode":7}""")
            }

            assertEquals(3, seasonSlot.captured)
            assertEquals(7, episodeSlot.captured)
        }

        @Test
        fun `accepts optional resolvesSessionKey`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "trakt-token"
            coEvery { episodeRepository.markEpisodeWatched(any(), any(), any()) } returns Result.success(Unit)

            val response = client.post("/watched") {
                contentType(ContentType.Application.Json)
                setBody("""{"showIds":{"trakt":1},"season":1,"episode":1,"resolvesSessionKey":"sess-abc"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ── DELETE /watched ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /watched")
    inner class MarkUnwatchedEndpoint {

        private val validBody = """
            {"showIds":{"trakt":1},"season":2,"episode":3}
        """.trimIndent()

        @Test
        fun `returns 200 on success`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "trakt-token"
            coEvery { episodeRepository.markEpisodeUnwatched(any(), any(), any()) } returns Result.success(Unit)

            val response = client.delete("/watched") {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"success\":true"))
        }

        @Test
        fun `returns 401 when no bearer token`() = testApp {
            val response = unauthClient.delete("/watched") {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 401 when no Trakt access token`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns null

            val response = client.delete("/watched") {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 400 on malformed body`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "trakt-token"

            val response = client.delete("/watched") {
                contentType(ContentType.Application.Json)
                setBody("bad")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `returns 503 when repository fails`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "trakt-token"
            coEvery { episodeRepository.markEpisodeUnwatched(any(), any(), any()) } returns
                Result.failure(RuntimeException("Trakt error"))

            val response = client.delete("/watched") {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

        @Test
        fun `captures season and episode correctly`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "trakt-token"
            val seasonSlot = slot<Int>()
            val episodeSlot = slot<Int>()
            coEvery {
                episodeRepository.markEpisodeUnwatched(any(), capture(seasonSlot), capture(episodeSlot))
            } returns Result.success(Unit)

            client.delete("/watched") {
                contentType(ContentType.Application.Json)
                setBody("""{"showIds":{"trakt":5},"season":4,"episode":9}""")
            }

            assertEquals(4, seasonSlot.captured)
            assertEquals(9, episodeSlot.captured)
        }
    }
}
