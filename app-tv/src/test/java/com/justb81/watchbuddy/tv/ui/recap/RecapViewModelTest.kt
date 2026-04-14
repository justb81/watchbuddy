package com.justb81.watchbuddy.tv.ui.recap

import android.app.Application
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("RecapViewModel")
class RecapViewModelTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private val application: Application = mockk(relaxed = true)
    private val phoneDiscovery: PhoneDiscoveryManager = mockk()
    private val httpClient: OkHttpClient = mockk(relaxed = true)
    private val phonesFlow = MutableStateFlow<List<PhoneDiscoveryManager.DiscoveredPhone>>(emptyList())
    private lateinit var viewModel: RecapViewModel

    @BeforeEach
    fun setUp() {
        every { phoneDiscovery.discoveredPhones } returns phonesFlow
        viewModel = RecapViewModel(application, phoneDiscovery, httpClient)
    }

    @Test
    fun `initial state is Idle`() {
        assertTrue(viewModel.state.value is RecapUiState.Idle)
    }

    @Test
    fun `requestRecap with no phones returns Fallback with allPhonesFailed false`() = runTest {
        viewModel.requestRecap(123, "Fallback synopsis text")
        advanceUntilIdle()

        val state = viewModel.state.value as RecapUiState.Fallback
        assertEquals("Fallback synopsis text", state.synopsis)
        assertFalse(state.allPhonesFailed)
    }

    @Test
    fun `reset returns state to Idle`() = runTest {
        viewModel.requestRecap(123, "Synopsis")
        advanceUntilIdle()

        viewModel.reset()
        assertTrue(viewModel.state.value is RecapUiState.Idle)
    }

    @Test
    fun `requestRecap with failing phones returns Fallback with allPhonesFailed true`() = runTest {
        val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone>()
        every { phone.capability } returns mockk {
            every { isAvailable } returns true
            every { deviceName } returns "Pixel"
        }
        every { phone.score } returns 100
        every { phone.serviceInfo } returns mockk {
            @Suppress("DEPRECATION")
            every { host } returns mockk {
                every { hostAddress } returns "192.168.1.1"
            }
            every { port } returns 8765
        }

        phonesFlow.value = listOf(phone)

        // OkHttpClient will throw since there's no real server
        every { httpClient.newCall(any()) } throws RuntimeException("Connection refused")

        viewModel.requestRecap(123, "Fallback text")
        advanceUntilIdle()

        val state = viewModel.state.value as RecapUiState.Fallback
        assertEquals("Fallback text", state.synopsis)
        assertTrue(state.allPhonesFailed)
    }
}
