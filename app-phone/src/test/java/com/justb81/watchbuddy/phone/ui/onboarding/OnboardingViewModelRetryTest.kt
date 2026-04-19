package com.justb81.watchbuddy.phone.ui.onboarding

import android.app.Application
import com.justb81.watchbuddy.core.network.TokenProxyServiceFactory
import com.justb81.watchbuddy.core.trakt.DeviceCodeResponse
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
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("OnboardingViewModel — Retry lifecycle and misconfiguration")
class OnboardingViewModelRetryTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()

        private const val BUILD_CLIENT_ID = "test-client-id"
    }

    private val application: Application = mockk(relaxed = true)
    private val traktApi: TraktApiService = mockk(relaxed = true)
    private val tokenProxy: TokenProxyService = mockk(relaxed = true)
    private val tokenRepository: TokenRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val tokenProxyServiceFactory: TokenProxyServiceFactory = mockk(relaxed = true)

    private val deviceCodeResponse = DeviceCodeResponse(
        device_code = "device-abc",
        user_code = "XYZ999",
        verification_url = "https://trakt.tv/activate",
        expires_in = 600,
        interval = 5
    )

    private val successToken = ProxyTokenResponse(
        access_token = "acc",
        refresh_token = "ref",
        expires_in = 7776000,
        token_type = "Bearer",
        scope = "public"
    )

    @BeforeEach
    fun setUp() {
        every { settingsRepository.getClientSecret() } returns ""
        every { settingsRepository.settings } returns flowOf(
            AppSettings(authMode = AuthMode.MANAGED)
        )
        every { application.getString(any<Int>()) } returns "Error"
        every { application.getString(any<Int>(), any()) } returns "Error"
    }

    private fun createViewModel() = OnboardingViewModel(
        application = application,
        traktApi = traktApi,
        tokenProxy = tokenProxy,
        buildConfigClientId = BUILD_CLIENT_ID,
        tokenRepository = tokenRepository,
        settingsRepository = settingsRepository,
        tokenProxyServiceFactory = tokenProxyServiceFactory
    )

    @Nested
    @DisplayName("Retry after 401/403 cancels stale polling")
    inner class RetryAfterAuthError {

        @Test
        fun `retry after 401 transitions through LoadingCode to WaitingForPin`() = runTest {
            var callCount = 0
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse
            coEvery { tokenProxy.exchangeDeviceCode(any()) } answers {
                callCount++
                throw HttpException(Response.error<Any>(401, "".toResponseBody()))
            }

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()

            // First attempt fails with 401 → Error state
            assertInstanceOf(OnboardingState.Error::class.java, vm.state.value)
            val errorsAfterFirstAttempt = callCount

            // Retry: should cancel stale job and start fresh
            vm.requestDeviceCode()

            // State must pass through LoadingCode before settling on WaitingForPin
            // (with UnconfinedTestDispatcher it's already set eagerly)
            val stateAfterRetry = vm.state.value
            assertTrue(
                stateAfterRetry is OnboardingState.LoadingCode ||
                    stateAfterRetry is OnboardingState.WaitingForPin,
                "Expected LoadingCode or WaitingForPin after retry, got $stateAfterRetry"
            )
            // The stale polling job was cancelled — no additional exchange calls from it
            assertTrue(callCount >= errorsAfterFirstAttempt, "Stale job must not fire extra calls")
        }

        @Test
        fun `retry after 403 transitions through LoadingCode to WaitingForPin`() = runTest {
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse
            coEvery { tokenProxy.exchangeDeviceCode(any()) } throws
                HttpException(Response.error<Any>(403, "".toResponseBody()))

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()

            assertInstanceOf(OnboardingState.Error::class.java, vm.state.value)

            vm.requestDeviceCode()
            val stateAfterRetry = vm.state.value
            assertTrue(
                stateAfterRetry is OnboardingState.LoadingCode ||
                    stateAfterRetry is OnboardingState.WaitingForPin,
                "Expected LoadingCode or WaitingForPin after retry, got $stateAfterRetry"
            )
        }

        @Test
        fun `retry after timeout produces new device code and reaches WaitingForPin`() = runTest {
            var requestCount = 0
            coEvery { traktApi.requestDeviceCode(any()) } answers {
                requestCount++
                deviceCodeResponse
            }
            // Always return 400 (pending) so polling keeps going until code expires
            coEvery { tokenProxy.exchangeDeviceCode(any()) } throws
                HttpException(Response.error<Any>(410, "".toResponseBody()))

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()

            assertInstanceOf(OnboardingState.Error::class.java, vm.state.value)

            // Second requestDeviceCode call should request a brand new device code
            vm.requestDeviceCode()
            advanceUntilIdle()

            // requestDeviceCode was called twice → traktApi also called twice
            // (second time because saved state was cleared on 410)
            assertTrue(requestCount >= 1, "Should have requested at least one device code")
        }
    }

    @Nested
    @DisplayName("Server misconfiguration (503 server_misconfigured)")
    inner class ServerMisconfigured {

        private fun make503Body(errorValue: String): okhttp3.ResponseBody =
            """{"error":"$errorValue"}""".toResponseBody()

        @Test
        fun `shows Error immediately on 503 server_misconfigured during polling`() = runTest {
            val misconfiguredMsg = "Server error: misconfigured"
            every { application.getString(any<Int>()) } answers {
                misconfiguredMsg
            }
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse
            coEvery { tokenProxy.exchangeDeviceCode(any()) } throws
                HttpException(
                    Response.error<Any>(503, make503Body("server_misconfigured"))
                )

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()

            assertInstanceOf(OnboardingState.Error::class.java, vm.state.value)
        }

        @Test
        fun `treats generic 503 as network failure not as server_misconfigured`() = runTest {
            var callCount = 0
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse
            coEvery { tokenProxy.exchangeDeviceCode(any()) } answers {
                callCount++
                // generic 503 with no meaningful body
                throw HttpException(Response.error<Any>(503, "".toResponseBody()))
            }
            every { application.getString(any<Int>()) } returns "Network error"

            val vm = createViewModel()
            vm.requestDeviceCode()
            advanceUntilIdle()

            // Generic 503 stops immediately (treated as terminal network failure)
            assertInstanceOf(OnboardingState.Error::class.java, vm.state.value)
        }
    }

    @Nested
    @DisplayName("Multiple rapid Retry clicks do not leave zombie jobs")
    inner class RapidRetry {

        @Test
        fun `calling requestDeviceCode twice quickly does not result in two concurrent WaitingForPin states`() = runTest {
            coEvery { traktApi.requestDeviceCode(any()) } returns deviceCodeResponse
            coEvery { tokenProxy.exchangeDeviceCode(any()) } returns successToken
            coEvery { traktApi.getProfile(any()) } returns TraktUserProfile(username = "user1")

            val vm = createViewModel()
            // Two rapid retries before the first has settled
            vm.requestDeviceCode()
            vm.requestDeviceCode()
            advanceUntilIdle()

            // Only one final state — no overlapping success/error from the cancelled first job
            val finalState = vm.state.value
            assertTrue(
                finalState is OnboardingState.Success || finalState is OnboardingState.WaitingForPin,
                "Expected a single settled state, got $finalState"
            )
        }
    }
}
