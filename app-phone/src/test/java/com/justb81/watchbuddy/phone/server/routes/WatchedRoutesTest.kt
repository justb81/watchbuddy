package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.core.network.WatchBuddyJson
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.server.EpisodeRepository
import io.ktor.client.request.*
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("WatchedRoutes")
class WatchedRoutesTest {

    private val tokenRefreshManager: TokenRefreshManager = mockk()
    private val episodeRepository: EpisodeRepository = mockk(relaxed = true)

    private val deps = WatchedRouteDeps(
        tokenRefreshManager = tokenRefreshManager,
        episodeRepository = episodeRepository,
    )

    private val validBody = """{"showIds":{"trakt":1},"season":2,"episode":3}"""

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(WatchBuddyJson) }
            routing { watchedRoutes(deps) }
        }
        block()
    }

    @Nested
    @DisplayName("POST /watched")
    inner class MarkWatched {

        @Test
        fun `returns 200 on success`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            coEvery { episodeRepository.markEpisodeWatched(any(), any(), any()) } returns Result.success(Unit)

            val response = client.post("/watched") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(validBody)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"success\":true"))
        }

        @Test
        fun `returns 401 when no access token`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns null

            val response = client.post("/watched") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(validBody)
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 400 on malformed body`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"

            val response = client.post("/watched") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("bad-json")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `returns 503 when repository fails`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            coEvery { episodeRepository.markEpisodeWatched(any(), any(), any()) } returns
                Result.failure(RuntimeException("Trakt error"))

            val response = client.post("/watched") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(validBody)
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

        @Test
        fun `passes season and episode correctly`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            val seasonSlot = slot<Int>()
            val episodeSlot = slot<Int>()
            coEvery {
                episodeRepository.markEpisodeWatched(any(), capture(seasonSlot), capture(episodeSlot))
            } returns Result.success(Unit)

            client.post("/watched") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"showIds":{"trakt":5},"season":3,"episode":7}""")
            }

            assertEquals(3, seasonSlot.captured)
            assertEquals(7, episodeSlot.captured)
        }
    }

    @Nested
    @DisplayName("DELETE /watched")
    inner class MarkUnwatched {

        @Test
        fun `returns 200 on success`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            coEvery { episodeRepository.markEpisodeUnwatched(any(), any(), any()) } returns Result.success(Unit)

            val response = client.delete("/watched") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(validBody)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"success\":true"))
        }

        @Test
        fun `returns 401 when no access token`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns null

            val response = client.delete("/watched") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(validBody)
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 400 on malformed body`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"

            val response = client.delete("/watched") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("bad")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `returns 503 when repository fails`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            coEvery { episodeRepository.markEpisodeUnwatched(any(), any(), any()) } returns
                Result.failure(RuntimeException("Trakt error"))

            val response = client.delete("/watched") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(validBody)
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

        @Test
        fun `passes season and episode correctly`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            val seasonSlot = slot<Int>()
            val episodeSlot = slot<Int>()
            coEvery {
                episodeRepository.markEpisodeUnwatched(any(), capture(seasonSlot), capture(episodeSlot))
            } returns Result.success(Unit)

            client.delete("/watched") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"showIds":{"trakt":5},"season":4,"episode":9}""")
            }

            assertEquals(4, seasonSlot.captured)
            assertEquals(9, episodeSlot.captured)
        }
    }
}
