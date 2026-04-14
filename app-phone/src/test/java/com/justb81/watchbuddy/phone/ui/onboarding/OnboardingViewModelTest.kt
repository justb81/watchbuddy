package com.justb81.watchbuddy.phone.ui.onboarding

import android.app.Application
import com.justb81.watchbuddy.core.network.TokenProxyServiceFactory
import com.justb81.watchbuddy.core.trakt.DeviceCodeResponse
import com.justb81.watchbuddy.core.trakt.DeviceTokenResponse
import com.justb81.watchbuddy.core.trakt.ProxyTokenResponse
import com.justb81.watchbuddy.core.trakt.TokenProxyService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.core.trakt.TraktUserProfile
import com.justb81.watchbuddy.phone.MainDispatcherRule
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.phone.ui.settings.AuthMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("OnboardingViewModel")
class OnboardingViewModelTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()

        private const val BUILD_CLIENT_ID = "test-client-id"
        private const val CUSTOM_BACKEND_URL = "https://my-proxy.example.com"
        private const val DIRECT_CLIENT_ID = "direct-id"
        private const val DIRECT_CLIENT_SECRET = "direct-secret"
    }

    private val application: Application = mockk(relaxed = true)
    private val traktApi: TraktApiService = mockk(relaxed = true)
    private val tokenProxy: TokenProxyService = mockk(relaxed = true)
    private val tokenRepository: TokenRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val tokenProxyServiceFactory: TokenProxyServiceFactory = mockk(relaxed = true)

    private val deviceCodeResponse = DeviceCodeResponse(
        device_code = "device-123",
        user_code = "ABC123",
        verification_url = "https://trakt.tv/activate",
        expires_in = 600,
        interval = 5
    )

    @BeforeEach
    fun setUp() {
        every { settingsRepository.getClientSecret() } returns ""
    }

    private fun createViewModel(
        buildClientId: String = BUILD_CLIENT_ID,
        proxy: TokenProxyService? = tokenProxy
    ): OnboardingViewModel = OnboardingViewModel(
        application = application,
        traktApi = traktApi,
        tokenProxy = proxy,
        buildConfigClientId = buildClientId,
        tokenRepository = tokenRepository,
        settingsRepository = settingsRepository,
        tokenProxyServiceFactory = tokenProxyServiceFactory
    )

    @Nested
    @DisplayName("MANAGED mode")
    inner class ManagedMode {

        @Test
        fun `shows NotConfigured when client ID is blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            val vm = createViewModel(buildClientId = "")
            vm.requestDeviceCode()
            advanceUntilIdle()
            assertEquals(OnboardingState.NotConfigured, vm.state.value)
        }

        @Test
        fun `shows NotConfigured when token proxy is null`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            val vm = createViewModel(proxy = null)
            vm.requestDeviceCode()
            advanceUntilIdle()
            assertEquals(OnboardingState.NotConfigured, vm.state.value)
        }

        @Test
        fun `requests device code when both client ID and proxy are present`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is OnboardingState.WaitingForPin)
            assertEquals("ABC123", (state as OnboardingState.WaitingForPin).userCode)
        }
    }

    @Nested
    @DisplayName("SELF_HOSTED mode")
    inner class SelfHostedMode {

        @Test
        fun `shows NotConfigured when backend URL is blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.SELF_HOSTED, backendUrl = "")
            )
            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()
            assertEquals(OnboardingState.NotConfigured, vm.state.value)
        }

        @Test
        fun `shows NotConfigured when client ID is blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.SELF_HOSTED, backendUrl = CUSTOM_BACKEND_URL)
            )
            val vm = createViewModel(buildClientId = "")
            vm.requestDeviceCode()
            advanceUntilIdle()
            assertEquals(OnboardingState.NotConfigured, vm.state.value)
        }

        @Test
        fun `requests device code when client ID and backend URL are present`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.SELF_HOSTED, backendUrl = CUSTOM_BACKEND_URL)
            )
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is OnboardingState.WaitingForPin)
        }
    }

    @Nested
    @DisplayName("DIRECT mode")
    inner class DirectMode {

        @Test
        fun `shows NotConfigured when direct client ID is blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.DIRECT, directClientId = "")
            )
            every { settingsRepository.getClientSecret() } returns DIRECT_CLIENT_SECRET

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()
            assertEquals(OnboardingState.NotConfigured, vm.state.value)
        }

        @Test
        fun `shows NotConfigured when client secret is blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.DIRECT, directClientId = DIRECT_CLIENT_ID)
            )
            every { settingsRepository.getClientSecret() } returns ""

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()
            assertEquals(OnboardingState.NotConfigured, vm.state.value)
        }

        @Test
        fun `requests device code when client ID and secret are present`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.DIRECT, directClientId = DIRECT_CLIENT_ID)
            )
            every { settingsRepository.getClientSecret() } returns DIRECT_CLIENT_SECRET
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is OnboardingState.WaitingForPin)
        }
    }

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `starts in Idle state`() {
            val vm = createViewModel()
            assertEquals(OnboardingState.Idle, vm.state.value)
        }
    }

    @Nested
    @DisplayName("Error handling")
    inner class ErrorHandling {

        @Test
        fun `shows Error when device code request fails`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            coEvery { traktApi.requestDeviceCode(any()) } throws RuntimeException("Network error")
            every { application.getString(any(), any()) } returns "Error: Network error"

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()

            assertTrue(vm.state.value is OnboardingState.Error)
        }
    }
}
