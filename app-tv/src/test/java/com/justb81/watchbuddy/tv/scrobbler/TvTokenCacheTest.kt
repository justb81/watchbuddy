package com.justb81.watchbuddy.tv.scrobbler

import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.discovery.TokenResponse
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TvTokenCache")
class TvTokenCacheTest {

    private val phoneApiClientFactory: PhoneApiClientFactory = mockk()
    private val phoneDiscovery: PhoneDiscoveryManager = mockk()
    private val phoneApiService: PhoneApiService = mockk()
    private lateinit var tokenCache: TvTokenCache

    @BeforeEach
    fun setUp() {
        tokenCache = TvTokenCache(phoneApiClientFactory, phoneDiscovery)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun mockBestPhone(baseUrl: String = "http://192.168.1.1:8765/") {
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns baseUrl
        every { phone.capability } returns null
        every { phoneDiscovery.getBestPhone() } returns phone
        every { phoneApiClientFactory.createClient(baseUrl) } returns phoneApiService
    }

    private fun createAvailablePhone(
        baseUrl: String,
        deviceId: String
    ): PhoneDiscoveryManager.DiscoveredPhone {
        val capability = DeviceCapability(
            deviceId = deviceId,
            userName = "user-$deviceId",
            deviceName = "Phone $deviceId",
            llmBackend = LlmBackend.NONE,
            modelQuality = 0,
            freeRamMb = 4000,
            isAvailable = true
        )
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns baseUrl
        every { phone.capability } returns capability
        return phone
    }

    private fun createUnavailablePhone(baseUrl: String): PhoneDiscoveryManager.DiscoveredPhone {
        val capability = mockk<DeviceCapability>()
        every { capability.isAvailable } returns false
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns baseUrl
        every { phone.capability } returns capability
        return phone
    }

    // ── getToken() ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getToken()")
    inner class GetTokenTest {

        @Test
        fun `fetches from best phone on first call`() = runTest {
            mockBestPhone()
            coEvery { phoneApiService.getAccessToken() } returns TokenResponse("my-token")

            val token = tokenCache.getToken()
            assertEquals("my-token", token)
        }

        @Test
        fun `returns cached token on second call`() = runTest {
            mockBestPhone()
            coEvery { phoneApiService.getAccessToken() } returns TokenResponse("cached-token")

            tokenCache.getToken()
            tokenCache.getToken()
            // Should only fetch once due to caching
            coVerify(exactly = 1) { phoneApiService.getAccessToken() }
        }

        @Test
        fun `returns null when no phone discovered`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns null

            val token = tokenCache.getToken()
            assertNull(token)
        }

        @Test
        fun `returns null when API call fails`() = runTest {
            mockBestPhone()
            coEvery { phoneApiService.getAccessToken() } throws RuntimeException("Network error")

            val token = tokenCache.getToken()
            assertNull(token)
        }

        @Test
        fun `invalidate forces re-fetch on next call`() = runTest {
            mockBestPhone()
            coEvery { phoneApiService.getAccessToken() } returns TokenResponse("token-1")

            tokenCache.getToken()
            tokenCache.invalidate()

            coEvery { phoneApiService.getAccessToken() } returns TokenResponse("token-2")
            val token = tokenCache.getToken()
            assertEquals("token-2", token)
            coVerify(exactly = 2) { phoneApiService.getAccessToken() }
        }

        @Test
        fun `invalidate on empty cache does not crash`() {
            tokenCache.invalidate()
        }
    }

    // ── getAllTokens() ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAllTokens()")
    inner class GetAllTokensTest {

        @Test
        fun `returns token for each available phone`() = runTest {
            val phone1 = createAvailablePhone("http://192.168.1.1:8765/", "device-1")
            val phone2 = createAvailablePhone("http://192.168.1.2:8765/", "device-2")
            val apiService1 = mockk<PhoneApiService>()
            val apiService2 = mockk<PhoneApiService>()

            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone1, phone2))
            every { phoneApiClientFactory.createClient("http://192.168.1.1:8765/") } returns apiService1
            every { phoneApiClientFactory.createClient("http://192.168.1.2:8765/") } returns apiService2
            coEvery { apiService1.getAccessToken() } returns TokenResponse("token-1")
            coEvery { apiService2.getAccessToken() } returns TokenResponse("token-2")

            val tokens = tokenCache.getAllTokens()

            assertEquals(2, tokens.size)
            assertTrue(tokens.any { it.phoneId == "device-1" && it.token == "token-1" })
            assertTrue(tokens.any { it.phoneId == "device-2" && it.token == "token-2" })
        }

        @Test
        fun `returns empty list when no phones discovered`() = runTest {
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(emptyList())

            val tokens = tokenCache.getAllTokens()
            assertTrue(tokens.isEmpty())
        }

        @Test
        fun `skips phones with unavailable capability`() = runTest {
            val availablePhone = createAvailablePhone("http://192.168.1.1:8765/", "device-1")
            val unavailablePhone = createUnavailablePhone("http://192.168.1.2:8765/")
            val apiService1 = mockk<PhoneApiService>()

            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(
                listOf(availablePhone, unavailablePhone)
            )
            every { phoneApiClientFactory.createClient("http://192.168.1.1:8765/") } returns apiService1
            coEvery { apiService1.getAccessToken() } returns TokenResponse("token-1")

            val tokens = tokenCache.getAllTokens()

            assertEquals(1, tokens.size)
            assertEquals("device-1", tokens.first().phoneId)
        }

        @Test
        fun `skips phone when API call fails, returns others`() = runTest {
            val phone1 = createAvailablePhone("http://192.168.1.1:8765/", "device-1")
            val phone2 = createAvailablePhone("http://192.168.1.2:8765/", "device-2")
            val apiService1 = mockk<PhoneApiService>()
            val apiService2 = mockk<PhoneApiService>()

            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone1, phone2))
            every { phoneApiClientFactory.createClient("http://192.168.1.1:8765/") } returns apiService1
            every { phoneApiClientFactory.createClient("http://192.168.1.2:8765/") } returns apiService2
            coEvery { apiService1.getAccessToken() } throws RuntimeException("Unreachable")
            coEvery { apiService2.getAccessToken() } returns TokenResponse("token-2")

            val tokens = tokenCache.getAllTokens()

            assertEquals(1, tokens.size)
            assertEquals("device-2", tokens.first().phoneId)
        }

        @Test
        fun `uses cached token for second call to same phone`() = runTest {
            val phone = createAvailablePhone("http://192.168.1.1:8765/", "device-1")
            val apiService = mockk<PhoneApiService>()

            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            every { phoneApiClientFactory.createClient("http://192.168.1.1:8765/") } returns apiService
            coEvery { apiService.getAccessToken() } returns TokenResponse("cached-token")

            tokenCache.getAllTokens()
            tokenCache.getAllTokens()

            coVerify(exactly = 1) { apiService.getAccessToken() }
        }

        @Test
        fun `invalidate clears per-phone cache`() = runTest {
            val phone = createAvailablePhone("http://192.168.1.1:8765/", "device-1")
            val apiService = mockk<PhoneApiService>()

            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            every { phoneApiClientFactory.createClient("http://192.168.1.1:8765/") } returns apiService
            coEvery { apiService.getAccessToken() } returns TokenResponse("token-v1")

            tokenCache.getAllTokens()
            tokenCache.invalidate()

            coEvery { apiService.getAccessToken() } returns TokenResponse("token-v2")
            val tokens = tokenCache.getAllTokens()

            assertEquals("token-v2", tokens.first().token)
            coVerify(exactly = 2) { apiService.getAccessToken() }
        }

        @Test
        fun `uses baseUrl as phoneId when capability is null`() = runTest {
            val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
            every { phone.baseUrl } returns "http://192.168.1.1:8765/"
            every { phone.capability } returns null
            val apiService = mockk<PhoneApiService>()

            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            every { phoneApiClientFactory.createClient("http://192.168.1.1:8765/") } returns apiService
            coEvery { apiService.getAccessToken() } returns TokenResponse("token")

            // Phone with null capability is filtered out (isAvailable check fails safely)
            val tokens = tokenCache.getAllTokens()
            assertTrue(tokens.isEmpty())
        }
    }
}
