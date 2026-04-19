package com.justb81.watchbuddy.tv.ui.recap

import android.app.Application
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.discovery.RecapResponse
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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
    private val phoneApiClientFactory: PhoneApiClientFactory = mockk()
    private val mockApiService: PhoneApiService = mockk()
    private val phonesFlow = MutableStateFlow<List<PhoneDiscoveryManager.DiscoveredPhone>>(emptyList())
    private lateinit var viewModel: RecapViewModel

    @BeforeEach
    fun setUp() {
        every { phoneDiscovery.discoveredPhones } returns phonesFlow
        every { phoneApiClientFactory.createClient(any()) } returns mockApiService
        viewModel = RecapViewModel(application, phoneDiscovery, phoneApiClientFactory)
    }

    @Test
    fun `initial state is Idle`() {
        assertTrue(viewModel.state.value is RecapUiState.Idle)
    }

    @Test
    fun `reset returns state to Idle`() = runTest {
        viewModel.requestRecap(123, "Synopsis")
        advanceUntilIdle()

        viewModel.reset()
        assertTrue(viewModel.state.value is RecapUiState.Idle)
    }

    @Nested
    @DisplayName("No phones available")
    inner class NoPhonesTest {

        @Test
        fun `returns Fallback with allPhonesFailed false`() = runTest {
            viewModel.requestRecap(123, "Fallback synopsis text")
            advanceUntilIdle()

            val state = viewModel.state.value as RecapUiState.Fallback
            assertEquals("Fallback synopsis text", state.synopsis)
            assertFalse(state.allPhonesFailed)
        }
    }

    @Nested
    @DisplayName("Phones available")
    inner class PhonesAvailableTest {

        private fun makePhone(baseUrl: String, score: Int = 100): PhoneDiscoveryManager.DiscoveredPhone =
            mockk {
                every { capability } returns mockk {
                    every { isAvailable } returns true
                    every { deviceName } returns "TestPhone"
                }
                every { this@mockk.score } returns score
                every { this@mockk.baseUrl } returns baseUrl
            }

        @Test
        fun `successful recap returns Ready state with html`() = runTest {
            phonesFlow.value = listOf(makePhone("http://192.168.1.1:8765/"))
            coEvery { mockApiService.getRecap(123) } returns RecapResponse("<p>Previously on...</p>")

            viewModel.requestRecap(123, "Fallback text")
            advanceUntilIdle()

            val state = viewModel.state.value as RecapUiState.Ready
            assertEquals("<p>Previously on...</p>", state.html)
        }

        @Test
        fun `all phones failing returns Fallback with allPhonesFailed true`() = runTest {
            phonesFlow.value = listOf(makePhone("http://192.168.1.1:8765/"))
            coEvery { mockApiService.getRecap(any()) } throws RuntimeException("Connection refused")

            viewModel.requestRecap(123, "Fallback text")
            advanceUntilIdle()

            val state = viewModel.state.value as RecapUiState.Fallback
            assertEquals("Fallback text", state.synopsis)
            assertTrue(state.allPhonesFailed)
        }

        @Test
        fun `failover to next phone when first phone fails`() = runTest {
            val workingApiService: PhoneApiService = mockk()
            every { phoneApiClientFactory.createClient("http://192.168.1.1:8765/") } returns mockApiService
            every { phoneApiClientFactory.createClient("http://192.168.1.2:8765/") } returns workingApiService

            // Both phones available; first has higher score so it's tried first
            phonesFlow.value = listOf(
                makePhone("http://192.168.1.1:8765/", score = 100),
                makePhone("http://192.168.1.2:8765/", score = 50),
            )

            coEvery { mockApiService.getRecap(any()) } throws RuntimeException("Connection refused")
            coEvery { workingApiService.getRecap(123) } returns RecapResponse("<p>Recap from backup</p>")

            viewModel.requestRecap(123, "Fallback text")
            advanceUntilIdle()

            val state = viewModel.state.value as RecapUiState.Ready
            assertEquals("<p>Recap from backup</p>", state.html)
        }

        @Test
        fun `uses PhoneApiClientFactory with phone baseUrl`() = runTest {
            val baseUrl = "http://192.168.1.1:8765/"
            phonesFlow.value = listOf(makePhone(baseUrl))
            coEvery { mockApiService.getRecap(any()) } returns RecapResponse("<p>html</p>")

            viewModel.requestRecap(42, "fallback")
            advanceUntilIdle()

            verify { phoneApiClientFactory.createClient(baseUrl) }
            coVerify { mockApiService.getRecap(42) }
        }
    }
}
