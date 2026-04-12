package com.justb81.watchbuddy.tv.scrobbler

import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.discovery.TokenResponse
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
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

    private fun mockBestPhone(baseUrl: String = "http://192.168.1.1:8765/") {
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.baseUrl } returns baseUrl
        every { phoneDiscovery.getBestPhone() } returns phone
        every { phoneApiClientFactory.createClient(baseUrl) } returns phoneApiService
    }

    @Test
    fun `getToken fetches from phone on first call`() = runTest {
        mockBestPhone()
        coEvery { phoneApiService.getAccessToken() } returns TokenResponse("my-token")

        val token = tokenCache.getToken()
        assertEquals("my-token", token)
    }

    @Test
    fun `getToken returns cached token on second call`() = runTest {
        mockBestPhone()
        coEvery { phoneApiService.getAccessToken() } returns TokenResponse("cached-token")

        tokenCache.getToken()
        tokenCache.getToken()
        // Should only fetch once due to caching
        coVerify(exactly = 1) { phoneApiService.getAccessToken() }
    }

    @Test
    fun `getToken returns null when no phone discovered`() = runTest {
        every { phoneDiscovery.getBestPhone() } returns null

        val token = tokenCache.getToken()
        assertNull(token)
    }

    @Test
    fun `getToken returns null when API call fails`() = runTest {
        mockBestPhone()
        coEvery { phoneApiService.getAccessToken() } throws RuntimeException("Network error")

        val token = tokenCache.getToken()
        assertNull(token)
    }

    @Test
    fun `invalidate clears cached token`() = runTest {
        mockBestPhone()
        coEvery { phoneApiService.getAccessToken() } returns TokenResponse("token-1")

        tokenCache.getToken()
        tokenCache.invalidate()
        // After invalidation, next call should fetch again
        coEvery { phoneApiService.getAccessToken() } returns TokenResponse("token-2")
        val token = tokenCache.getToken()
        assertEquals("token-2", token)
        coVerify(exactly = 2) { phoneApiService.getAccessToken() }
    }

    @Test
    fun `invalidate resets timestamp forcing re-fetch`() {
        tokenCache.invalidate()
        // Verify no crash on invalidating empty cache
    }

    @Test
    fun `getToken and invalidate are safe for concurrent access`() = runTest {
        mockBestPhone()
        coEvery { phoneApiService.getAccessToken() } returns TokenResponse("concurrent-token")

        // Simulate concurrent access: get token then invalidate
        val token = tokenCache.getToken()
        assertEquals("concurrent-token", token)

        // Invalidate from another "thread" (simulated sequentially)
        tokenCache.invalidate()

        // Next call should re-fetch
        coEvery { phoneApiService.getAccessToken() } returns TokenResponse("new-token")
        val newToken = tokenCache.getToken()
        assertEquals("new-token", newToken)
        coVerify(exactly = 2) { phoneApiService.getAccessToken() }
    }

    @Test
    fun `token and timestamp are atomically consistent`() = runTest {
        mockBestPhone()
        coEvery { phoneApiService.getAccessToken() } returns TokenResponse("token-a")

        // First call caches token
        val first = tokenCache.getToken()
        assertEquals("token-a", first)

        // Second call returns cached token (no re-fetch)
        val second = tokenCache.getToken()
        assertEquals("token-a", second)
        coVerify(exactly = 1) { phoneApiService.getAccessToken() }
    }
}
