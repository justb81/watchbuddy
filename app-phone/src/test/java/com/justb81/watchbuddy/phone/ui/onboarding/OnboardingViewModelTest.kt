package com.justb81.watchbuddy.phone.ui.onboarding

import android.app.Application
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.trakt.DeviceCodeResponse
import com.justb81.watchbuddy.core.trakt.ProxyTokenResponse
import com.justb81.watchbuddy.core.trakt.TokenProxyService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.core.trakt.TraktUserProfile
import com.justb81.watchbuddy.phone.auth.TokenRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val application: Application = mockk(relaxed = true)
    private val traktApi: TraktApiService = mockk()
    private val tokenProxy: TokenProxyService = mockk()
    private val tokenRepository: TokenRepository = mockk(relaxed = true)

    private val fakeDeviceCode = DeviceCodeResponse(
        device_code = "dev-code",
        user_code = "ABCD1234",
        verification_url = "https://trakt.tv/activate",
        expires_in = 600,
        interval = 5
    )

    private val fakeTokenResponse = ProxyTokenResponse(
        access_token = "access-token",
        refresh_token = "refresh-token",
        expires_in = 7776000,
        token_type = "Bearer",
        scope = "public"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { application.getString(R.string.onboarding_code_expired) } returns "Code expired"
        every { application.getString(R.string.onboarding_error_denied) } returns "Denied"
        every { application.getString(R.string.onboarding_error_polling_network) } returns "Network error"
        every { application.getString(R.string.onboarding_error_auth_failed) } returns "Auth failed"
        every { application.getString(R.string.onboarding_error_loading_code, any()) } returns "Load error"
        coEvery { traktApi.requestDeviceCode(any()) } returns fakeDeviceCode
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = OnboardingViewModel(
        application = application,
        traktApi = traktApi,
        tokenProxy = tokenProxy,
        clientId = "test-client-id",
        tokenRepository = tokenRepository
    )

    @Test
    fun `initial state is Idle when configured`() {
        val vm = buildViewModel()
        assertTrue(vm.state.value is OnboardingState.Idle)
    }

    @Test
    fun `initial state is NotConfigured when clientId is blank`() {
        val vm = OnboardingViewModel(application, traktApi, tokenProxy, "", tokenRepository)
        assertTrue(vm.state.value is OnboardingState.NotConfigured)
    }

    @Test
    fun `initial state is NotConfigured when tokenProxy is null`() {
        val vm = OnboardingViewModel(application, traktApi, null, "client-id", tokenRepository)
        assertTrue(vm.state.value is OnboardingState.NotConfigured)
    }

    @Test
    fun `polling HTTP 403 from token exchange shows auth error and stops`() = runTest {
        val httpEx = mockk<HttpException>()
        every { httpEx.code() } returns 403
        coEvery { tokenProxy.exchangeDeviceCode(any()) } throws httpEx

        val vm = buildViewModel()
        vm.requestDeviceCode()

        advanceTimeBy(fakeDeviceCode.interval * 1_000L + 100L)

        val state = vm.state.value
        assertTrue("Expected Error but got $state", state is OnboardingState.Error)
        assertEquals("Auth failed", (state as OnboardingState.Error).message)
    }

    @Test
    fun `polling HTTP 401 from token exchange shows auth error and stops`() = runTest {
        val httpEx = mockk<HttpException>()
        every { httpEx.code() } returns 401
        coEvery { tokenProxy.exchangeDeviceCode(any()) } throws httpEx

        val vm = buildViewModel()
        vm.requestDeviceCode()

        advanceTimeBy(fakeDeviceCode.interval * 1_000L + 100L)

        val state = vm.state.value
        assertTrue("Expected Error but got $state", state is OnboardingState.Error)
        assertEquals("Auth failed", (state as OnboardingState.Error).message)
    }

    @Test
    fun `polling HTTP 410 shows code expired and stops`() = runTest {
        val httpEx = mockk<HttpException>()
        every { httpEx.code() } returns 410
        coEvery { tokenProxy.exchangeDeviceCode(any()) } throws httpEx

        val vm = buildViewModel()
        vm.requestDeviceCode()

        advanceTimeBy(fakeDeviceCode.interval * 1_000L + 100L)

        val state = vm.state.value
        assertTrue("Expected Error but got $state", state is OnboardingState.Error)
        assertEquals("Code expired", (state as OnboardingState.Error).message)
    }

    @Test
    fun `polling HTTP 418 shows denied error and stops`() = runTest {
        val httpEx = mockk<HttpException>()
        every { httpEx.code() } returns 418
        coEvery { tokenProxy.exchangeDeviceCode(any()) } throws httpEx

        val vm = buildViewModel()
        vm.requestDeviceCode()

        advanceTimeBy(fakeDeviceCode.interval * 1_000L + 100L)

        val state = vm.state.value
        assertTrue("Expected Error but got $state", state is OnboardingState.Error)
        assertEquals("Denied", (state as OnboardingState.Error).message)
    }

    @Test
    fun `profile fetch HTTP 403 shows auth error`() = runTest {
        coEvery { tokenProxy.exchangeDeviceCode(any()) } returns fakeTokenResponse
        val httpEx = mockk<HttpException>()
        every { httpEx.code() } returns 403
        coEvery { traktApi.getProfile(any()) } throws httpEx

        val vm = buildViewModel()
        vm.requestDeviceCode()

        advanceTimeBy(fakeDeviceCode.interval * 1_000L + 100L)

        val state = vm.state.value
        assertTrue("Expected Error but got $state", state is OnboardingState.Error)
        assertEquals("Auth failed", (state as OnboardingState.Error).message)
    }

    @Test
    fun `successful auth shows Success state with username`() = runTest {
        coEvery { tokenProxy.exchangeDeviceCode(any()) } returns fakeTokenResponse
        coEvery { traktApi.getProfile(any()) } returns TraktUserProfile(username = "testuser")

        val vm = buildViewModel()
        vm.requestDeviceCode()

        advanceTimeBy(fakeDeviceCode.interval * 1_000L + 100L)

        val state = vm.state.value
        assertTrue("Expected Success but got $state", state is OnboardingState.Success)
        assertEquals("testuser", (state as OnboardingState.Success).username)
    }

    @Test
    fun `three consecutive network failures shows network error`() = runTest {
        coEvery { tokenProxy.exchangeDeviceCode(any()) } throws RuntimeException("Network error")

        val vm = buildViewModel()
        vm.requestDeviceCode()

        // Advance past 3 polling intervals
        advanceTimeBy(fakeDeviceCode.interval * 3 * 1_000L + 500L)

        val state = vm.state.value
        assertTrue("Expected Error but got $state", state is OnboardingState.Error)
        assertEquals("Network error", (state as OnboardingState.Error).message)
    }
}
