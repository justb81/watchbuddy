package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbCache
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.LlmBusyException
import com.justb81.watchbuddy.phone.llm.RecapGenerator
import com.justb81.watchbuddy.phone.server.ShowRepository
import com.justb81.watchbuddy.phone.settings.SettingsRepository
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
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RecapRoutes — POST /recap/{traktShowId}")
class RecapRoutesTest {

    private val recapGenerator: RecapGenerator = mockk()
    private val showRepository: ShowRepository = mockk()
    private val tokenRepository: TokenRepository = mockk()
    private val tmdbApiService: TmdbApiService = mockk()
    private val tmdbCache = TmdbCache()
    private val settingsRepository: SettingsRepository = mockk()

    private val deps = RecapRouteDeps(
        recapGenerator = recapGenerator,
        showRepository = showRepository,
        tokenRepository = tokenRepository,
        tmdbApiService = tmdbApiService,
        tmdbCache = tmdbCache,
        settingsRepository = settingsRepository,
    )

    private val tmdbShow = TmdbShow(100, "Breaking Bad", "A chemistry teacher turns to crime.")
    private val watchedEntry = TraktWatchedEntry(
        show = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1, tmdb = 100)),
        seasons = listOf(
            TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1), TraktWatchedEpisode(2))),
        )
    )
    private val enrichedEntry = EnrichedShowEntry(entry = watchedEntry)
    private val ep1 = TmdbEpisode(1, "Pilot", "First episode.", "/s1e1.jpg", 1, 1)
    private val ep2 = TmdbEpisode(2, "Cat's in the Bag", null, null, 1, 2)

    @BeforeEach
    fun setUp() {
        clearAllMocks()
        tmdbCache.clear()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(WatchBuddyJson) }
            routing { recapRoutes(deps) }
        }
        block()
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
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"tmdbApiKey":""}""")        }

        assertEquals(HttpStatusCode.PreconditionFailed, response.status)
    }

    @Test
    fun `returns 404 when show not in watched list`() = testApp {
        every { tokenRepository.getAccessToken() } returns "token"
        coEvery { showRepository.getShows() } returns emptyList()

        val response = client.post("/recap/1") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"tmdbApiKey":"api-key"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `returns 200 on successful recap generation`() = testApp {
        every { tokenRepository.getAccessToken() } returns "token"
        coEvery { showRepository.getShows() } returns listOf(enrichedEntry)
        coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
        coEvery { tmdbApiService.getEpisode(100, 1, 1, "api-key", any()) } returns ep1
        coEvery { tmdbApiService.getEpisode(100, 1, 2, "api-key", any()) } returns ep2
        coEvery { recapGenerator.generateRecap(any(), any(), any()) } returns "<div>Recap</div>"

        val response = client.post("/recap/1") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"tmdbApiKey":"api-key"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Recap"))
    }

    @Test
    fun `returns 503 when recap generator is busy`() = testApp {
        every { tokenRepository.getAccessToken() } returns "token"
        coEvery { showRepository.getShows() } returns listOf(enrichedEntry)
        coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
        coEvery { tmdbApiService.getEpisode(any(), any(), any(), any(), any()) } returns ep1
        coEvery { recapGenerator.generateRecap(any(), any(), any()) } throws LlmBusyException("busy")

        val response = client.post("/recap/1") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"tmdbApiKey":"api-key"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("LLM busy"))
    }

    @Test
    fun `returns 503 on generic recap failure without leaking exception message`() = testApp {
        every { tokenRepository.getAccessToken() } returns "token"
        coEvery { showRepository.getShows() } returns listOf(enrichedEntry)
        coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns tmdbShow
        coEvery { tmdbApiService.getEpisode(any(), any(), any(), any(), any()) } returns ep1
        coEvery { recapGenerator.generateRecap(any(), any(), any()) } throws RuntimeException("internal crash")

        val response = client.post("/recap/1") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"tmdbApiKey":"api-key"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertFalse(response.bodyAsText().contains("internal crash"))
    }

    @Test
    fun `returns 503 on SecurityException`() = testApp {
        every { tokenRepository.getAccessToken() } throws SecurityException("Keystore locked")

        val response = client.post("/recap/1") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"tmdbApiKey":"api-key"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }
}
