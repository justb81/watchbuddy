package com.justb81.watchbuddy.phone.ui.onboarding

import android.app.Application
import androidx.lifecycle.SavedStateHandle
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

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
        proxy: TokenProxyService? = tokenProxy,
        savedStateHandle: SavedStateHandle = SavedStateHandle()
    ): OnboardingViewModel = OnboardingViewModel(
        application = application,
        savedStateHandle = savedStateHandle,
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
        fun `shows NotConfigured with MANAGED_MISSING_CLIENT_ID when client ID is blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            val vm = createViewModel(buildClientId = "")
            vm.requestDeviceCode()
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state is OnboardingState.NotConfigured)
            assertEquals(
                NotConfiguredReason.MANAGED_MISSING_CLIENT_ID,
                (state as OnboardingState.NotConfigured).reason
            )
        }

        @Test
        fun `shows NotConfigured with MANAGED_MISSING_BACKEND when token proxy is null`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            val vm = createViewModel(proxy = null)
            vm.requestDeviceCode()
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state is OnboardingState.NotConfigured)
            assertEquals(
                NotConfiguredReason.MANAGED_MISSING_BACKEND,
                (state as OnboardingState.NotConfigured).reason
            )
        }

        @Test
        fun `requests device code when both client ID and proxy are present`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse

            val vm = createViewModel()
            vm.requestDeviceCode()
            // Don't advanceUntilIdle — the countdown coroutine would expire the code.
            // With UnconfinedTestDispatcher, state is already set eagerly.

            val state = vm.state.value
            assertTrue(state is OnboardingState.WaitingForPin)
            assertEquals("ABC123", (state as OnboardingState.WaitingForPin).userCode)
        }
    }

    @Nested
    @DisplayName("SELF_HOSTED mode")
    inner class SelfHostedMode {

        @Test
        fun `shows NotConfigured with SELF_HOSTED_MISSING_URL when backend URL is blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.SELF_HOSTED, backendUrl = "")
            )
            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state is OnboardingState.NotConfigured)
            assertEquals(
                NotConfiguredReason.SELF_HOSTED_MISSING_URL,
                (state as OnboardingState.NotConfigured).reason
            )
        }

        @Test
        fun `shows NotConfigured with SELF_HOSTED_MISSING_CLIENT_ID when client ID is blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.SELF_HOSTED, backendUrl = CUSTOM_BACKEND_URL)
            )
            val vm = createViewModel(buildClientId = "")
            vm.requestDeviceCode()
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state is OnboardingState.NotConfigured)
            assertEquals(
                NotConfiguredReason.SELF_HOSTED_MISSING_CLIENT_ID,
                (state as OnboardingState.NotConfigured).reason
            )
        }

        @Test
        fun `requests device code when client ID and backend URL are present`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.SELF_HOSTED, backendUrl = CUSTOM_BACKEND_URL)
            )
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse

            val vm = createViewModel()
            vm.requestDeviceCode()

            val state = vm.state.value
            assertTrue(state is OnboardingState.WaitingForPin)
        }
    }

    @Nested
    @DisplayName("DIRECT mode")
    inner class DirectMode {

        @Test
        fun `shows NotConfigured with DIRECT_MISSING_CREDENTIALS when direct client ID is blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.DIRECT, directClientId = "")
            )
            every { settingsRepository.getClientSecret() } returns DIRECT_CLIENT_SECRET

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state is OnboardingState.NotConfigured)
            assertEquals(
                NotConfiguredReason.DIRECT_MISSING_CREDENTIALS,
                (state as OnboardingState.NotConfigured).reason
            )
        }

        @Test
        fun `shows NotConfigured with DIRECT_MISSING_CREDENTIALS when client secret is blank`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.DIRECT, directClientId = DIRECT_CLIENT_ID)
            )
            every { settingsRepository.getClientSecret() } returns ""

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state is OnboardingState.NotConfigured)
            assertEquals(
                NotConfiguredReason.DIRECT_MISSING_CREDENTIALS,
                (state as OnboardingState.NotConfigured).reason
            )
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

    @Nested
    @DisplayName("Polling error handling")
    inner class PollingErrorHandling {

        private val proxyTokenResponse = ProxyTokenResponse(
            access_token = "acc",
            refresh_token = "ref",
            expires_in = 7776000,
            token_type = "Bearer",
            scope = "public"
        )

        @Test
        fun `shows Error after 3 consecutive network failures during polling`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse
            // First call succeeds to get to polling phase; subsequent calls throw network errors
            coEvery { tokenProxy.exchangeDeviceCode(any()) } throws RuntimeException("Connection refused")
            every { application.getString(any<Int>()) } returns "Network error"

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()

            assertTrue(vm.state.value is OnboardingState.Error)
        }

        @Test
        fun `continues polling when HTTP 400 is returned (PIN pending)`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse

            var callCount = 0
            coEvery { tokenProxy.exchangeDeviceCode(any()) } answers {
                callCount++
                if (callCount < 3) {
                    // HTTP 400 = pending
                    throw HttpException(Response.error<Any>(400, "".toResponseBody()))
                } else {
                    proxyTokenResponse
                }
            }
            coEvery { traktApi.getProfile(any()) } returns TraktUserProfile(username = "user1")

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()

            // After the third attempt succeeds, state should be Success
            assertTrue(vm.state.value is OnboardingState.Success)
        }

        @Test
        fun `resets consecutive failure count after a successful HTTP 400 response`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse

            var callCount = 0
            coEvery { tokenProxy.exchangeDeviceCode(any()) } answers {
                callCount++
                when (callCount) {
                    1 -> throw RuntimeException("network blip")  // counts as 1
                    2 -> throw HttpException(Response.error<Any>(400, "".toResponseBody())) // resets to 0
                    3 -> throw RuntimeException("network blip")  // counts as 1 again
                    4 -> throw RuntimeException("network blip")  // counts as 2
                    5 -> proxyTokenResponse                       // succeeds before reaching 3
                    else -> proxyTokenResponse
                }
            }
            coEvery { traktApi.getProfile(any()) } returns TraktUserProfile(username = "user1")

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()

            // Should succeed because the HTTP 400 reset the failure counter before we hit 3
            assertTrue(vm.state.value is OnboardingState.Success)
        }
    }

    @Nested
    @DisplayName("Process death survival")
    inner class ProcessDeathSurvival {

        private val json = Json { ignoreUnknownKeys = true }

        private fun savedStateWithCode(
            response: DeviceCodeResponse = deviceCodeResponse,
            timestamp: Long = System.currentTimeMillis()
        ): SavedStateHandle = SavedStateHandle(
            mapOf(
                "device_code_json" to json.encodeToString(response),
                "device_code_timestamp" to timestamp
            )
        )

        @Test
        fun `restores saved device code instead of requesting a new one`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse

            val handle = savedStateWithCode()
            val vm = createViewModel(savedStateHandle = handle)
            vm.requestDeviceCode()

            val state = vm.state.value
            assertTrue(state is OnboardingState.WaitingForPin)
            assertEquals("ABC123", (state as OnboardingState.WaitingForPin).userCode)
            coVerify(exactly = 0) { traktApi.requestDeviceCode(any()) }
        }

        @Test
        fun `requests new code when saved code has expired`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse

            val expiredTimestamp = System.currentTimeMillis() - 700_000L // 700s ago, code was 600s
            val handle = savedStateWithCode(timestamp = expiredTimestamp)
            val vm = createViewModel(savedStateHandle = handle)
            vm.requestDeviceCode()

            val state = vm.state.value
            assertTrue(state is OnboardingState.WaitingForPin)
            coVerify(exactly = 1) { traktApi.requestDeviceCode(any()) }
        }

        @Test
        fun `clears saved state on successful authentication`() = runTest {
            every { settingsRepository.settings } returns flowOf(
                AppSettings(authMode = AuthMode.MANAGED)
            )
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse
            coEvery { tokenProxy.exchangeDeviceCode(any()) } returns ProxyTokenResponse(
                access_token = "acc",
                refresh_token = "ref",
                expires_in = 7776000,
                token_type = "Bearer",
                scope = "public"
            )
            coEvery { traktApi.getProfile(any()) } returns TraktUserProfile(username = "user1")

            val handle = SavedStateHandle()
            val vm = createViewModel(savedStateHandle = handle)
            vm.requestDeviceCode()
            advanceUntilIdle()

            assertTrue(vm.state.value is OnboardingState.Success)
            assertNull(handle.get<String>("device_code_json"))
            assertNull(handle.get<Long>("device_code_timestamp"))
        }
    }
}
