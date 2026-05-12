package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import com.justb81.watchbuddy.core.trakt.SyncHistoryResult
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.server.EpisodeRepository
import com.justb81.watchbuddy.phone.server.ShowRepository
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ShowsRoutes")
class ShowsRoutesTest {

    private val showRepository: ShowRepository = mockk()
    private val episodeRepository: EpisodeRepository = mockk(relaxed = true)
    private val tokenRepository: TokenRepository = mockk()
    private val tokenRefreshManager: TokenRefreshManager = mockk()
    private val traktApiService: TraktApiService = mockk()

    private val deps = ShowsRouteDeps(
        showRepository = showRepository,
        episodeRepository = episodeRepository,
        tokenRepository = tokenRepository,
        tokenRefreshManager = tokenRefreshManager,
        traktApiService = traktApiService,
    )

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(WatchBuddyJson) }
            routing { showsRoutes(deps) }
        }
        block()
    }

    @Nested
    @DisplayName("GET /shows")
    inner class GetShows {

        @Test
        fun `returns 401 when no access token`() = testApp {
            every { tokenRepository.getAccessToken() } returns null

            val response = client.get("/shows")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 200 with shows list when authenticated`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns listOf(
                EnrichedShowEntry(entry = TraktWatchedEntry(TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1))))
            )

            val response = client.get("/shows")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Breaking Bad"))
        }

        @Test
        fun `returns 200 with empty list when no shows`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } returns emptyList()

            val response = client.get("/shows")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("[]", response.bodyAsText())
        }

        @Test
        fun `returns 503 on SecurityException`() = testApp {
            every { tokenRepository.getAccessToken() } throws SecurityException("Keystore locked")

            val response = client.get("/shows")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

        @Test
        fun `returns 500 on generic exception`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { showRepository.getShows() } throws RuntimeException("DB error")

            val response = client.get("/shows")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }

        @Test
        fun `respects limit query parameter`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            val shows = (1..5).map { i ->
                EnrichedShowEntry(entry = TraktWatchedEntry(TraktShow("Show $i", 2020, TraktIds(trakt = i))))
            }
            coEvery { showRepository.getShows() } returns shows

            val response = client.get("/shows?limit=2")

            val body = response.bodyAsText()
            assertTrue(body.contains("Show 1"))
            assertTrue(body.contains("Show 2"))
            assertFalse(body.contains("Show 3"))
        }

        @Test
        fun `respects offset and limit query parameters`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            val shows = (1..5).map { i ->
                EnrichedShowEntry(entry = TraktWatchedEntry(TraktShow("Show $i", 2020, TraktIds(trakt = i))))
            }
            coEvery { showRepository.getShows() } returns shows

            val response = client.get("/shows?offset=2&limit=2")

            val body = response.bodyAsText()
            assertFalse(body.contains("Show 2"))
            assertTrue(body.contains("Show 3"))
            assertTrue(body.contains("Show 4"))
            assertFalse(body.contains("Show 5"))
        }
    }

    @Nested
    @DisplayName("GET /shows/{showId}/seasons")
    inner class GetSeasons {

        @Test
        fun `returns 200 with seasons JSON`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { episodeRepository.getSeasonsWithEpisodes("42") } returns emptyList()

            val response = client.get("/shows/42/seasons")

            assertEquals(HttpStatusCode.OK, response.status)
        }

        @Test
        fun `returns 401 when no Trakt access token`() = testApp {
            every { tokenRepository.getAccessToken() } returns null

            val response = client.get("/shows/42/seasons")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `passes showId path parameter to repository`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            val slot = slot<String>()
            coEvery { episodeRepository.getSeasonsWithEpisodes(capture(slot)) } returns emptyList()

            client.get("/shows/show-xyz/seasons")

            assertEquals("show-xyz", slot.captured)
        }

        @Test
        fun `returns 503 on SecurityException`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } throws SecurityException("Keystore")

            val response = client.get("/shows/1/seasons")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

        @Test
        fun `returns 500 on generic exception`() = testApp {
            every { tokenRepository.getAccessToken() } returns "token"
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } throws RuntimeException("DB error")

            val response = client.get("/shows/1/seasons")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }
    }

    @Nested
    @DisplayName("POST /shows/add-to-library")
    inner class AddToLibrary {

        private val validBody = """
            {"show":{"title":"Stranger Things","year":2016,"ids":{"tmdb":66732}},"episode":{"season":1,"number":1,"ids":{}}}
        """.trimIndent()

        @Test
        fun `returns 401 when no access token`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns null

            val response = client.post("/shows/add-to-library") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(validBody)
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 400 when request body is invalid`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"

            val response = client.post("/shows/add-to-library") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("not-json")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `returns 200 and calls addToHistory`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
            coEvery { traktApiService.addToHistory("Bearer test-token", any()) } returns SyncHistoryResult()
            every { showRepository.invalidateCache() } just runs

            val response = client.post("/shows/add-to-library") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(validBody)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("true"))
        }

        @Test
        fun `invalidates cache after successful add`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
            coEvery { traktApiService.addToHistory(any(), any()) } returns SyncHistoryResult()
            every { showRepository.invalidateCache() } just runs

            client.post("/shows/add-to-library") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(validBody)
            }

            verify { showRepository.invalidateCache() }
        }

        @Test
        fun `returns 503 when Trakt throws`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            coEvery { traktApiService.addToHistory(any(), any()) } throws RuntimeException("Network error")

            val response = client.post("/shows/add-to-library") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(validBody)
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }
    }
}
