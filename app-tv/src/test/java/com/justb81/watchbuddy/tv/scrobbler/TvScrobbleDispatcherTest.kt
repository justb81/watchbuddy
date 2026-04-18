package com.justb81.watchbuddy.tv.scrobbler

import android.net.nsd.NsdServiceInfo
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.tv.TestFixtures
import com.justb81.watchbuddy.tv.discovery.DiscoveryConstants
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

@DisplayName("TvScrobbleDispatcher")
class TvScrobbleDispatcherTest {

    private val phoneDiscovery: PhoneDiscoveryManager = mockk()
    private val phoneApiClientFactory: PhoneApiClientFactory = mockk()
    private val phoneApiService: PhoneApiService = mockk()
    private lateinit var dispatcher: TvScrobbleDispatcher

    private val phonesFlow = MutableStateFlow<List<PhoneDiscoveryManager.DiscoveredPhone>>(emptyList())

    @BeforeEach
    fun setUp() {
        every { phoneDiscovery.discoveredPhones } returns phonesFlow
        dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory)
    }

    private fun makePhone(
        baseUrl: String = "http://192.168.1.1:8765/",
        isAvailable: Boolean = true,
        lastSuccessfulCheck: Long = System.currentTimeMillis(),
        name: String = "test-phone"
    ): PhoneDiscoveryManager.DiscoveredPhone {
        val serviceInfo = mockk<NsdServiceInfo>()
        every { serviceInfo.serviceName } returns name
        val capability = TestFixtures.deviceCapability(isAvailable = isAvailable)
        val txt = PhoneDiscoveryManager.PhoneTxtRecord(
            version = "1.0.0",
            modelQuality = 75,
            llmBackend = LlmBackend.LITERT
        )
        return PhoneDiscoveryManager.DiscoveredPhone(
            serviceInfo = serviceInfo,
            txtRecord = txt,
            capability = capability,
            score = 75,
            baseUrl = baseUrl,
            lastSuccessfulCheck = lastSuccessfulCheck
        )
    }

    // ── staleness filtering ────────────────────────────────────────────────────

    @Nested
    @DisplayName("staleness filtering")
    inner class StalenessFilteringTest {

        @Test
        fun `dispatches to a fresh phone`() = runTest {
            val phone = makePhone()
            phonesFlow.value = listOf(phone)
            coEvery { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            coEvery { phoneApiService.scrobbleStart(any()) } returns mockk()

            dispatcher.dispatchStart(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                50f
            )

            coVerify { phoneApiService.scrobbleStart(any()) }
        }

        @Test
        fun `skips a stale phone whose lastSuccessfulCheck exceeds PRESENCE_STALENESS_MS`() = runTest {
            val staleTime = System.currentTimeMillis() - DiscoveryConstants.PRESENCE_STALENESS_MS - 1_000L
            val phone = makePhone(lastSuccessfulCheck = staleTime)
            phonesFlow.value = listOf(phone)

            dispatcher.dispatchStart(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                50f
            )

            coVerify(exactly = 0) { phoneApiClientFactory.createClient(any()) }
        }

        @Test
        fun `includes a phone whose lastSuccessfulCheck is exactly at the staleness boundary`() = runTest {
            // A check that happened exactly at the boundary should still be considered fresh.
            val boundaryTime = System.currentTimeMillis() - DiscoveryConstants.PRESENCE_STALENESS_MS + 100L
            val phone = makePhone(lastSuccessfulCheck = boundaryTime)
            phonesFlow.value = listOf(phone)
            coEvery { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            coEvery { phoneApiService.scrobbleStart(any()) } returns mockk()

            dispatcher.dispatchStart(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                50f
            )

            coVerify { phoneApiService.scrobbleStart(any()) }
        }

        @Test
        fun `skips a phone marked as unavailable even when fresh`() = runTest {
            val phone = makePhone(isAvailable = false)
            phonesFlow.value = listOf(phone)

            dispatcher.dispatchStart(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                50f
            )

            coVerify(exactly = 0) { phoneApiClientFactory.createClient(any()) }
        }

        @Test
        fun `skips dispatch when phone list is empty`() = runTest {
            phonesFlow.value = emptyList()

            dispatcher.dispatchStart(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                50f
            )

            coVerify(exactly = 0) { phoneApiClientFactory.createClient(any()) }
        }

        @Test
        fun `dispatches to all fresh phones in parallel`() = runTest {
            val apiService1: PhoneApiService = mockk()
            val apiService2: PhoneApiService = mockk()
            val phone1 = makePhone(baseUrl = "http://phone1:8765/", name = "phone1")
            val phone2 = makePhone(baseUrl = "http://phone2:8765/", name = "phone2")
            phonesFlow.value = listOf(phone1, phone2)
            coEvery { phoneApiClientFactory.createClient("http://phone1:8765/") } returns apiService1
            coEvery { phoneApiClientFactory.createClient("http://phone2:8765/") } returns apiService2
            coEvery { apiService1.scrobbleStart(any()) } returns mockk()
            coEvery { apiService2.scrobbleStart(any()) } returns mockk()

            dispatcher.dispatchStart(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                50f
            )

            coVerify { apiService1.scrobbleStart(any()) }
            coVerify { apiService2.scrobbleStart(any()) }
        }

        @Test
        fun `IOException on one phone does not prevent dispatch to others`() = runTest {
            val apiService1: PhoneApiService = mockk()
            val apiService2: PhoneApiService = mockk()
            val phone1 = makePhone(baseUrl = "http://phone1:8765/", name = "phone1")
            val phone2 = makePhone(baseUrl = "http://phone2:8765/", name = "phone2")
            phonesFlow.value = listOf(phone1, phone2)
            coEvery { phoneApiClientFactory.createClient("http://phone1:8765/") } returns apiService1
            coEvery { phoneApiClientFactory.createClient("http://phone2:8765/") } returns apiService2
            coEvery { apiService1.scrobbleStart(any()) } throws IOException("connection refused")
            coEvery { apiService2.scrobbleStart(any()) } returns mockk()

            // Should not throw even when one phone fails with IOException.
            dispatcher.dispatchStart(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                50f
            )

            coVerify { apiService2.scrobbleStart(any()) }
        }
    }

    // ── action routing ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("action routing")
    inner class ActionRoutingTest {

        @Test
        fun `dispatchPause calls scrobblePause on available phones`() = runTest {
            val phone = makePhone()
            phonesFlow.value = listOf(phone)
            coEvery { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            coEvery { phoneApiService.scrobblePause(any()) } returns mockk()

            dispatcher.dispatchPause(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                50f
            )

            coVerify { phoneApiService.scrobblePause(any()) }
            coVerify(exactly = 0) { phoneApiService.scrobbleStart(any()) }
            coVerify(exactly = 0) { phoneApiService.scrobbleStop(any()) }
        }

        @Test
        fun `dispatchStop calls scrobbleStop on available phones`() = runTest {
            val phone = makePhone()
            phonesFlow.value = listOf(phone)
            coEvery { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            coEvery { phoneApiService.scrobbleStop(any()) } returns mockk()

            dispatcher.dispatchStop(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                90f
            )

            coVerify { phoneApiService.scrobbleStop(any()) }
            coVerify(exactly = 0) { phoneApiService.scrobbleStart(any()) }
            coVerify(exactly = 0) { phoneApiService.scrobblePause(any()) }
        }

        @Test
        fun `dispatchPause skips dispatch when phone list is empty`() = runTest {
            phonesFlow.value = emptyList()

            dispatcher.dispatchPause(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                50f
            )

            coVerify(exactly = 0) { phoneApiClientFactory.createClient(any()) }
        }

        @Test
        fun `dispatchStop skips dispatch when phone list is empty`() = runTest {
            phonesFlow.value = emptyList()

            dispatcher.dispatchStop(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                90f
            )

            coVerify(exactly = 0) { phoneApiClientFactory.createClient(any()) }
        }

        @Test
        fun `dispatchStart calls scrobbleStart not pause or stop`() = runTest {
            val phone = makePhone()
            phonesFlow.value = listOf(phone)
            coEvery { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            coEvery { phoneApiService.scrobbleStart(any()) } returns mockk()

            dispatcher.dispatchStart(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                10f
            )

            coVerify { phoneApiService.scrobbleStart(any()) }
            coVerify(exactly = 0) { phoneApiService.scrobblePause(any()) }
            coVerify(exactly = 0) { phoneApiService.scrobbleStop(any()) }
        }
    }
}
