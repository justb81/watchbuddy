package com.justb81.watchbuddy.phone.auth

import com.justb81.watchbuddy.core.network.TokenProxyServiceFactory
import com.justb81.watchbuddy.core.trakt.DeviceTokenResponse
import com.justb81.watchbuddy.core.trakt.ProxyRefreshRequest
import com.justb81.watchbuddy.core.trakt.ProxyTokenResponse
import com.justb81.watchbuddy.core.trakt.RefreshTokenRequest
import com.justb81.watchbuddy.core.trakt.TokenProxyService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.phone.ui.settings.AuthMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TokenRefreshManager")
class TokenRefreshManagerTest {

    private val tokenRepository: TokenRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val traktApi: TraktApiService = mockk(relaxed = true)
    private val tokenProxy: TokenProxyService = mockk(relaxed = true)
    private val tokenProxyServiceFactory: TokenProxyServiceFactory = mockk(relaxed = true)
    private val buildClientId = "build-client-id"

    private lateinit var manager: TokenRefreshManager

    private val proxyTokenResponse = ProxyTokenResponse(
        access_token = "new-access-token",
        refresh_token = "new-refresh-token",
        expires_in = 7776000,
        token_type = "Bearer",
        scope = "public"
    )

    private val traktTokenResponse = DeviceTokenResponse(
        access_token = "new-access-token",
        token_type = "Bearer",
        expires_in = 7776000,
        refresh_token = "new-refresh-token",
        scope = "public"
    )

    @BeforeEach
    fun setUp() {
        manager = TokenRefreshManager(
            tokenRepository = tokenRepository,
            settingsRepository = settingsRepository,
            traktApi = traktApi,
            tokenProxy = tokenProxy,
            tokenProxyServiceFactory = tokenProxyServiceFactory,
            clientId = buildClientId
        )
    }

    // ── Fast path ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("when token is valid and not expiring soon")
    inner class ValidToken {

        @Test
        fun `returns stored token without refreshing`() = runTest {
            every { tokenRepository.isTokenExpiredOrExpiringSoon(any()) } returns false
            every { tokenRepository.getAccessToken() } returns "valid-token"

            val result = manager.getValidAccessToken()

            assertEquals("valid-token", result)
            coVerify(exactly = 0) { tokenProxy.refreshToken(any()) }
            coVerify(exactly = 0) { traktApi.refreshToken(any()) }
        }
    }

    // ── MANAGED mode ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MANAGED auth mode")
    inner class ManagedMode {

        @BeforeEach
        fun setUpManagedMode() {
            every { tokenRepository.isTokenExpiredOrExpiringSoon(any()) } returns true
            every { tokenRepository.getRefreshToken() } returns "old-refresh-token"
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
        }

        @Test
        fun `refreshes via managed proxy and returns new token`() = runTest {
            coEvery { tokenProxy.refreshToken(ProxyRefreshRequest("old-refresh-token")) } returns proxyTokenResponse

            val result = manager.getValidAccessToken()

            assertEquals("new-access-token", result)
            verify {
                tokenRepository.saveTokens("new-access-token", "new-refresh-token", 7776000)
            }
        }

        @Test
        fun `returns null when managed proxy is not configured`() = runTest {
            val managerWithoutProxy = TokenRefreshManager(
                tokenRepository = tokenRepository,
                settingsRepository = settingsRepository,
                traktApi = traktApi,
                tokenProxy = null,
                tokenProxyServiceFactory = tokenProxyServiceFactory,
                clientId = buildClientId
            )

            val result = managerWithoutProxy.getValidAccessToken()

            assertNull(result)
        }

        @Test
        fun `returns null when proxy refresh call throws`() = runTest {
            coEvery { tokenProxy.refreshToken(any()) } throws RuntimeException("Network error")

            val result = manager.getValidAccessToken()

            assertNull(result)
        }
    }

    // ── SELF_HOSTED mode ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("SELF_HOSTED auth mode")
    inner class SelfHostedMode {

        private val selfHostedProxy: TokenProxyService = mockk()

        @BeforeEach
        fun setUpSelfHostedMode() {
            every { tokenRepository.isTokenExpiredOrExpiringSoon(any()) } returns true
            every { tokenRepository.getRefreshToken() } returns "old-refresh-token"
            every { settingsRepository.settings } returns flowOf(
                AppSettings(
                    authMode = AuthMode.SELF_HOSTED,
                    backendUrl = "https://my-proxy.example.com"
                )
            )
            every { tokenProxyServiceFactory.create("https://my-proxy.example.com") } returns selfHostedProxy
        }

        @Test
        fun `refreshes via self-hosted proxy and returns new token`() = runTest {
            coEvery {
                selfHostedProxy.refreshToken(ProxyRefreshRequest("old-refresh-token"))
            } returns proxyTokenResponse

            val result = manager.getValidAccessToken()

            assertEquals("new-access-token", result)
            verify {
                tokenRepository.saveTokens("new-access-token", "new-refresh-token", 7776000)
            }
        }

        @Test
        fun `returns null when backend URL is blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.SELF_HOSTED, backendUrl = "")
            )

            val result = manager.getValidAccessToken()

            assertNull(result)
            coVerify(exactly = 0) { selfHostedProxy.refreshToken(any()) }
        }

        @Test
        fun `returns null when self-hosted proxy refresh call throws`() = runTest {
            coEvery { selfHostedProxy.refreshToken(any()) } throws RuntimeException("Proxy error")

            val result = manager.getValidAccessToken()

            assertNull(result)
        }
    }

    // ── DIRECT mode ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DIRECT auth mode")
    inner class DirectMode {

        @BeforeEach
        fun setUpDirectMode() {
            every { tokenRepository.isTokenExpiredOrExpiringSoon(any()) } returns true
            every { tokenRepository.getRefreshToken() } returns "old-refresh-token"
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.DIRECT, directClientId = "user-client-id")
            )
            every { settingsRepository.getClientSecret() } returns "user-client-secret"
        }

        @Test
        fun `refreshes directly via Trakt API and returns new token`() = runTest {
            coEvery {
                traktApi.refreshToken(
                    RefreshTokenRequest(
                        refresh_token = "old-refresh-token",
                        client_id = "user-client-id",
                        client_secret = "user-client-secret"
                    )
                )
            } returns traktTokenResponse

            val result = manager.getValidAccessToken()

            assertEquals("new-access-token", result)
            verify {
                tokenRepository.saveTokens("new-access-token", "new-refresh-token", 7776000)
            }
        }

        @Test
        fun `uses build-time clientId when directClientId is blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.DIRECT, directClientId = "")
            )
            coEvery {
                traktApi.refreshToken(
                    RefreshTokenRequest(
                        refresh_token = "old-refresh-token",
                        client_id = buildClientId,
                        client_secret = "user-client-secret"
                    )
                )
            } returns traktTokenResponse

            val result = manager.getValidAccessToken()

            assertEquals("new-access-token", result)
        }

        @Test
        fun `returns null when client secret is blank`() = runTest {
            every { settingsRepository.getClientSecret() } returns ""

            val result = manager.getValidAccessToken()

            assertNull(result)
            coVerify(exactly = 0) { traktApi.refreshToken(any()) }
        }

        @Test
        fun `returns null when both client IDs are blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.DIRECT, directClientId = "")
            )
            val managerWithoutBuildClientId = TokenRefreshManager(
                tokenRepository = tokenRepository,
                settingsRepository = settingsRepository,
                traktApi = traktApi,
                tokenProxy = tokenProxy,
                tokenProxyServiceFactory = tokenProxyServiceFactory,
                clientId = ""
            )

            val result = managerWithoutBuildClientId.getValidAccessToken()

            assertNull(result)
            coVerify(exactly = 0) { traktApi.refreshToken(any()) }
        }

        @Test
        fun `returns null when Trakt refresh call throws`() = runTest {
            coEvery { traktApi.refreshToken(any()) } throws RuntimeException("Trakt API error")

            val result = manager.getValidAccessToken()

            assertNull(result)
        }
    }

    // ── No refresh token ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("when no refresh token is stored")
    inner class NoRefreshToken {

        @Test
        fun `returns null without calling any refresh endpoint`() = runTest {
            every { tokenRepository.isTokenExpiredOrExpiringSoon(any()) } returns true
            every { tokenRepository.getRefreshToken() } returns null

            val result = manager.getValidAccessToken()

            assertNull(result)
            coVerify(exactly = 0) { tokenProxy.refreshToken(any()) }
            coVerify(exactly = 0) { traktApi.refreshToken(any()) }
        }

        @Test
        fun `returns null when refresh token is blank`() = runTest {
            every { tokenRepository.isTokenExpiredOrExpiringSoon(any()) } returns true
            every { tokenRepository.getRefreshToken() } returns ""

            val result = manager.getValidAccessToken()

            assertNull(result)
        }
    }

    // ── Post-refresh re-use ───────────────────────────────────────────────────

    @Nested
    @DisplayName("after a successful refresh")
    inner class PostRefresh {

        @Test
        fun `second call skips refresh when token becomes valid`() = runTest {
            // First call: token is expired — triggers refresh
            every { tokenRepository.isTokenExpiredOrExpiringSoon(any()) } returns true
            every { tokenRepository.getRefreshToken() } returns "old-refresh-token"
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            coEvery { tokenProxy.refreshToken(any()) } returns proxyTokenResponse
            every { tokenRepository.getAccessToken() } returns "new-access-token"

            val first = manager.getValidAccessToken()
            assertEquals("new-access-token", first)

            // Simulate that saveTokens made the token valid
            every { tokenRepository.isTokenExpiredOrExpiringSoon(any()) } returns false

            val second = manager.getValidAccessToken()
            assertEquals("new-access-token", second)

            // Proxy only called once across both calls
            coVerify(exactly = 1) { tokenProxy.refreshToken(any()) }
        }

        @Test
        fun `concurrent callers obtain a token and proxy is called at most once`() = runTest {
            var tokenSaved = false
            // Before saveTokens: token appears expired; after: it appears valid
            every { tokenRepository.isTokenExpiredOrExpiringSoon(any()) } answers { !tokenSaved }
            every { tokenRepository.saveTokens(any(), any(), any()) } answers { tokenSaved = true }
            every { tokenRepository.getRefreshToken() } returns "old-refresh-token"
            every { tokenRepository.getAccessToken() } returns "new-access-token"
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            coEvery { tokenProxy.refreshToken(any()) } returns proxyTokenResponse

            val results = (1..5).map {
                async { manager.getValidAccessToken() }
            }.awaitAll()

            results.forEach { assertNotNull(it) }
            // Mutex serialises concurrent requests — at most one refresh call is made
            coVerify(atMost = 1) { tokenProxy.refreshToken(any()) }
        }
    }
}
