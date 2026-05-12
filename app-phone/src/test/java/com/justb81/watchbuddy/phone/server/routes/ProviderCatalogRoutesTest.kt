package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.core.network.WatchBuddyJson
import com.justb81.watchbuddy.phone.data.ProviderCatalogRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.contentType
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ProviderCatalogRoutes — GET /provider-catalog")
class ProviderCatalogRoutesTest {

    private val providerCatalogRepository: ProviderCatalogRepository = mockk(relaxed = true)

    private val catalogJson = """{"version":8,"lastUpdated":"2026-05-10T00:00:00Z","providers":[]}"""

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(WatchBuddyJson) }
            routing {
                providerCatalogRoutes(ProviderCatalogRouteDeps(providerCatalogRepository))
            }
        }
        block()
    }

    @Test
    fun `returns 404 when no catalog has been fetched`() = testApp {
        coEvery { providerCatalogRepository.currentJson() } returns null

        val response = client.get("/provider-catalog")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `returns 200 with JSON when catalog is available`() = testApp {
        coEvery { providerCatalogRepository.currentJson() } returns catalogJson

        val response = client.get("/provider-catalog")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
    }

    @Test
    fun `returns ETag header when catalog is available`() = testApp {
        coEvery { providerCatalogRepository.currentJson() } returns catalogJson

        val response = client.get("/provider-catalog")

        assertNotNull(response.headers["ETag"])
    }

    @Test
    fun `returns 304 when If-None-Match matches ETag`() = testApp {
        coEvery { providerCatalogRepository.currentJson() } returns catalogJson

        val first = client.get("/provider-catalog")
        val etag = first.headers["ETag"]!!

        val second = client.get("/provider-catalog") {
            header(HttpHeaders.IfNoneMatch, etag)
        }

        assertEquals(HttpStatusCode.NotModified, second.status)
    }

    @Test
    fun `returns Cache-Control public header`() = testApp {
        coEvery { providerCatalogRepository.currentJson() } returns catalogJson

        val response = client.get("/provider-catalog")

        val cacheControl = response.headers[HttpHeaders.CacheControl]
        assertNotNull(cacheControl)
        assertTrue(cacheControl!!.contains("public"))
    }

    @Test
    fun `ETag changes when catalog content changes`() = testApp {
        coEvery { providerCatalogRepository.currentJson() } returns catalogJson
        val first = client.get("/provider-catalog")
        val etag1 = first.headers["ETag"]!!

        coEvery { providerCatalogRepository.currentJson() } returns """{"version":9,"providers":[]}"""
        val second = client.get("/provider-catalog")
        val etag2 = second.headers["ETag"]!!

        assertNotEquals(etag1, etag2)
    }
}
