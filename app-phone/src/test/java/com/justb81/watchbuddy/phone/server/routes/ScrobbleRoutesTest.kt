package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.core.model.MediaMetadataSnapshot
import com.justb81.watchbuddy.core.model.ScrobbleAction
import com.justb81.watchbuddy.core.model.TitleExtractionResponse
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import com.justb81.watchbuddy.core.trakt.ScrobbleResponse
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.llm.LlmBusyException
import com.justb81.watchbuddy.phone.llm.LlmTitleExtractor
import com.justb81.watchbuddy.service.CompanionStateManager
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ScrobbleRoutes")
class ScrobbleRoutesTest {

    private val tokenRefreshManager: TokenRefreshManager = mockk()
    private val traktApiService: TraktApiService = mockk()
    private val stateManager = CompanionStateManager()
    private val titleExtractor: LlmTitleExtractor = mockk(relaxed = true)

    private val deps = ScrobbleRouteDeps(
        tokenRefreshManager = tokenRefreshManager,
        traktApiService = traktApiService,
        stateManager = stateManager,
        titleExtractor = titleExtractor,
    )

    private val show = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1))
    private val episode = TraktEpisode(season = 1, number = 1)
    private val scrobbleBody =
        """{"show":{"title":"Breaking Bad","year":2008,"ids":{"trakt":1}},"episode":{"season":1,"number":1,"ids":{}},"progress":0.0}"""

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(WatchBuddyJson) }
            routing { scrobbleRoutes(deps) }
        }
        block()
    }

    @Nested
    @DisplayName("POST /scrobble/start")
    inner class ScrobbleStart {

        @Test
        fun `returns 401 when no access token`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns null

            val response = client.post("/scrobble/start") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(scrobbleBody)
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `returns 400 when body is invalid`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"

            val response = client.post("/scrobble/start") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("not-valid-json")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `returns 200 and calls Trakt API`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
            coEvery { traktApiService.scrobbleStart(any(), any()) } returns
                ScrobbleResponse(id = 1L, action = "start", progress = 0f, show = show, episode = episode)

            val response = client.post("/scrobble/start") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(scrobbleBody)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify { traktApiService.scrobbleStart("Bearer test-token", any()) }
        }

        @Test
        fun `updates stateManager with START action`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            coEvery { traktApiService.scrobbleStart(any(), any()) } returns
                ScrobbleResponse(id = 1L, action = "start", progress = 0f, show = show, episode = episode)

            client.post("/scrobble/start") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(scrobbleBody)
            }

            assertEquals(ScrobbleAction.START, stateManager.lastScrobbleEvent.value?.action)
        }

        @Test
        fun `returns 503 when Trakt API throws`() = testApp {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            coEvery { traktApiService.scrobbleStart(any(), any()) } throws RuntimeException("Network error")

            val response = client.post("/scrobble/start") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(scrobbleBody)
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertFalse(response.bodyAsText().contains("Network error"))
        }
    }

    @Nested
    @DisplayName("POST /scrobble/extract")
    inner class ScrobbleExtract {

        private val extractBody = """
            {"snapshot":{"packageName":"com.netflix.ninja","text":"mediaSession.title: Pilot"},"libraryHints":[]}
        """.trimIndent()

        @Test
        fun `returns 200 with extractor output`() = testApp {
            coEvery { titleExtractor.extract(any<MediaMetadataSnapshot>(), any()) } returns
                TitleExtractionResponse(showTitle = "Breaking Bad", season = 1, episode = 1, confidence = 0.9f)

            val response = client.post("/scrobble/extract") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(extractBody)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"showTitle\":\"Breaking Bad\""))
        }

        @Test
        fun `returns 400 on malformed body`() = testApp {
            val response = client.post("/scrobble/extract") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("bad")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `returns 413 when Content-Length exceeds 64 KB`() = testApp {
            val oversizedText = "x".repeat(70_000)
            val oversizedBody =
                """{"snapshot":{"packageName":"com.pkg","text":"${'$'}oversizedText"},"libraryHints":[]}"""

            val response = client.post("/scrobble/extract") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(oversizedBody)
            }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

        @Test
        fun `returns 503 when extractor is busy`() = testApp {
            coEvery { titleExtractor.extract(any<MediaMetadataSnapshot>(), any()) } throws
                LlmBusyException("busy")

            val response = client.post("/scrobble/extract") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(extractBody)
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("LLM busy"))
        }

        @Test
        fun `returns 503 on generic extractor failure`() = testApp {
            coEvery { titleExtractor.extract(any<MediaMetadataSnapshot>(), any()) } throws
                RuntimeException("OOM")

            val response = client.post("/scrobble/extract") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(extractBody)
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }
    }

    @Nested
    @DisplayName("POST /scrobble/prompt")
    inner class ScrobblePrompt {

        private val promptBody = """
            {"sessionKey":"sess-1","packageName":"com.netflix.mediaclient","candidates":[{"show":{"title":"Breaking Bad","year":2008,"ids":{"trakt":1}},"score":0.9,"sourceLabel":"test"}],"tick":{"state":3,"positionMs":0,"durationMs":0,"capturedAtMs":0},"capturedAtMs":0}
        """.trimIndent()

        @Test
        fun `returns 204 on valid prompt`() = testApp {
            val response = client.post("/scrobble/prompt") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(promptBody)
            }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

        @Test
        fun `returns 400 on malformed body`() = testApp {
            val response = client.post("/scrobble/prompt") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("bad-json")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }
}
