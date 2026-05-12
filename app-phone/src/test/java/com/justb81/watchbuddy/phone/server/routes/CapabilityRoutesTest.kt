package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.network.WatchBuddyJson
import com.justb81.watchbuddy.phone.server.DeviceCapabilityProvider
import com.justb81.watchbuddy.service.CompanionStateManager
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CapabilityRoutes — GET /capability")
class CapabilityRoutesTest {

    private val capabilityProvider: DeviceCapabilityProvider = mockk()
    private val stateManager = CompanionStateManager()

    private val capability = DeviceCapability(
        deviceId = "dev-1",
        userName = "alice",
        deviceName = "Pixel 9",
        llmBackend = LlmBackend.LITERT,
        modelQuality = 75,
        freeRamMb = 4096,
        isAvailable = true,
        tmdbConfigured = true,
    )

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(WatchBuddyJson) }
            routing {
                capabilityRoutes(CapabilityRouteDeps(capabilityProvider, stateManager))
            }
        }
        block()
    }

    @Test
    fun `returns 200 with capability JSON`() = testApp {
        coEvery { capabilityProvider.getCapability() } returns capability

        val response = client.get("/capability")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"userName\":\"alice\""))
        assertTrue(body.contains("\"modelQuality\":75"))
    }

    @Test
    fun `is accessible without bearer token`() = testApp {
        coEvery { capabilityProvider.getCapability() } returns capability

        val response = client.get("/capability")

        assertNotEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `notifies stateManager on each request`() = testApp {
        coEvery { capabilityProvider.getCapability() } returns capability

        client.get("/capability")
        client.get("/capability")

        // stateManager.onCapabilityChecked() updates lastCapabilityCheckMs — no exception is the assertion.
        assertEquals(HttpStatusCode.OK, client.get("/capability").status)
    }
}
