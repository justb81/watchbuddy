package com.justb81.watchbuddy.tv.scrobbler

import android.net.nsd.NsdServiceInfo
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.tv.TestFixtures
import com.justb81.watchbuddy.tv.discovery.DiscoveryConstants
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.discovery.PhoneScrobbleRequest
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("TvScrobbleDispatcher")
class TvScrobbleDispatcherTest {

    private val phoneDiscovery: PhoneDiscoveryManager = mockk()
    private val phoneApiClientFactory: PhoneApiClientFactory = mockk()
    private val phoneApiService: PhoneApiService = mockk()
    private lateinit var dispatcher: TvScrobbleDispatcher

    private val phonesFlow = MutableStateFlow<List<PhoneDiscoveryManager.DiscoveredPhone>>(emptyList())
    private var fakeNow = System.currentTimeMillis()

    @BeforeEach
    fun setUp() {
        every { phoneDiscovery.discoveredPhones } returns phonesFlow
        fakeNow = System.currentTimeMillis()
    }

    private fun makePhone(
        baseUrl: String = "http://192.168.1.1:8765/",
        isAvailable: Boolean = true,
        lastSuccessfulCheck: Long = fakeNow,
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
        fun `dispatches to a fresh phone`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
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
        fun `skips a stale phone whose lastSuccessfulCheck exceeds PRESENCE_STALENESS_MS`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
            val staleTime = fakeNow - DiscoveryConstants.PRESENCE_STALENESS_MS - 1_000L
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
        fun `includes a phone whose lastSuccessfulCheck is exactly at the staleness boundary`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
            // A check that happened exactly at the boundary should still be considered fresh.
            val boundaryTime = fakeNow - DiscoveryConstants.PRESENCE_STALENESS_MS + 100L
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
        fun `skips a phone marked as unavailable even when fresh`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
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
        fun `skips dispatch when phone list is empty`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
            phonesFlow.value = emptyList()

            dispatcher.dispatchStart(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                50f
            )

            coVerify(exactly = 0) { phoneApiClientFactory.createClient(any()) }
        }

        @Test
        fun `dispatches to all fresh phones in parallel`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
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
        fun `IOException on one phone does not prevent dispatch to others`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
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
        fun `dispatchPause calls scrobblePause on available phones`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
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
        fun `dispatchStop calls scrobbleStop on available phones`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
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
        fun `dispatchPause skips dispatch when phone list is empty`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
            phonesFlow.value = emptyList()

            dispatcher.dispatchPause(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                50f
            )

            coVerify(exactly = 0) { phoneApiClientFactory.createClient(any()) }
        }

        @Test
        fun `dispatchStop skips dispatch when phone list is empty`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
            phonesFlow.value = emptyList()

            dispatcher.dispatchStop(
                TestFixtures.traktShow(),
                TestFixtures.traktEpisode(),
                90f
            )

            coVerify(exactly = 0) { phoneApiClientFactory.createClient(any()) }
        }

        @Test
        fun `dispatchStart calls scrobbleStart not pause or stop`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
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

    // ── retry queue ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("retry queue")
    inner class RetryQueueTest {

        @Test
        fun `dropped START is dispatched when a phone later appears on discoveredPhones`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
            coEvery { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            coEvery { phoneApiService.scrobbleStart(any()) } returns mockk()

            // No phones → event is queued, not dispatched.
            dispatcher.dispatchStart(TestFixtures.traktShow(), TestFixtures.traktEpisode(), 10f)
            coVerify(exactly = 0) { phoneApiService.scrobbleStart(any()) }

            // Phone appears → drain loop fires and replays the queued START.
            phonesFlow.value = listOf(makePhone())
            advanceUntilIdle()

            coVerify(exactly = 1) { phoneApiService.scrobbleStart(any()) }
        }

        @Test
        fun `dropped PAUSE is replayed on the next phone emission`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
            coEvery { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            coEvery { phoneApiService.scrobblePause(any()) } returns mockk()

            dispatcher.dispatchPause(TestFixtures.traktShow(), TestFixtures.traktEpisode(), 50f)
            coVerify(exactly = 0) { phoneApiService.scrobblePause(any()) }

            phonesFlow.value = listOf(makePhone())
            advanceUntilIdle()

            coVerify(exactly = 1) { phoneApiService.scrobblePause(any()) }
        }

        @Test
        fun `dropped STOP is replayed on the next phone emission`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
            coEvery { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            coEvery { phoneApiService.scrobbleStop(any()) } returns mockk()

            dispatcher.dispatchStop(TestFixtures.traktShow(), TestFixtures.traktEpisode(), 90f)
            coVerify(exactly = 0) { phoneApiService.scrobbleStop(any()) }

            phonesFlow.value = listOf(makePhone())
            advanceUntilIdle()

            coVerify(exactly = 1) { phoneApiService.scrobbleStop(any()) }
        }

        @Test
        fun `queue is drained in FIFO order`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
            coEvery { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            val callOrder = mutableListOf<String>()
            coEvery { phoneApiService.scrobbleStart(any()) } answers { callOrder += "start"; mockk() }
            coEvery { phoneApiService.scrobblePause(any()) } answers { callOrder += "pause"; mockk() }
            coEvery { phoneApiService.scrobbleStop(any()) } answers { callOrder += "stop"; mockk() }

            dispatcher.dispatchStart(TestFixtures.traktShow(), TestFixtures.traktEpisode(), 10f)
            dispatcher.dispatchPause(TestFixtures.traktShow(), TestFixtures.traktEpisode(), 50f)
            dispatcher.dispatchStop(TestFixtures.traktShow(), TestFixtures.traktEpisode(), 90f)

            phonesFlow.value = listOf(makePhone())
            advanceUntilIdle()

            assertEquals(listOf("start", "pause", "stop"), callOrder)
        }

        @Test
        fun `stale queued events older than QUEUE_TTL_MS are discarded on drain`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
            coEvery { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            coEvery { phoneApiService.scrobbleStart(any()) } returns mockk()

            // Enqueue a START at the current fake time.
            dispatcher.dispatchStart(TestFixtures.traktShow(), TestFixtures.traktEpisode(), 10f)

            // Advance the fake clock past the TTL — the entry is now expired.
            fakeNow += TvScrobbleDispatcher.QUEUE_TTL_MS + 1_000L

            phonesFlow.value = listOf(makePhone(lastSuccessfulCheck = fakeNow))
            advanceUntilIdle()

            coVerify(exactly = 0) { phoneApiService.scrobbleStart(any()) }
        }

        @Test
        fun `queue caps at QUEUE_MAX_SIZE and drops the oldest entry`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
            coEvery { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            val dispatchedProgress = mutableListOf<Float>()
            coEvery { phoneApiService.scrobbleStart(any()) } answers {
                dispatchedProgress += firstArg<PhoneScrobbleRequest>().progress
                mockk()
            }

            // Enqueue QUEUE_MAX_SIZE + 1 events; the first (progress=0f) should be dropped.
            repeat(TvScrobbleDispatcher.QUEUE_MAX_SIZE + 1) { i ->
                dispatcher.dispatchStart(TestFixtures.traktShow(), TestFixtures.traktEpisode(), i.toFloat())
            }

            phonesFlow.value = listOf(makePhone())
            advanceUntilIdle()

            assertEquals(TvScrobbleDispatcher.QUEUE_MAX_SIZE, dispatchedProgress.size)
            assertFalse(dispatchedProgress.contains(0f), "oldest entry (progress=0) should have been dropped")
            assertTrue(dispatchedProgress.contains(1f), "second entry should survive")
        }

        @Test
        fun `queue is empty after successful drain so second phone emission dispatches nothing`() = runTest(UnconfinedTestDispatcher()) {
            dispatcher = TvScrobbleDispatcher(phoneDiscovery, phoneApiClientFactory, backgroundScope) { fakeNow }
            coEvery { phoneApiClientFactory.createClient(any()) } returns phoneApiService
            coEvery { phoneApiService.scrobbleStart(any()) } returns mockk()

            dispatcher.dispatchStart(TestFixtures.traktShow(), TestFixtures.traktEpisode(), 10f)

            // First phone appearance drains the queue.
            phonesFlow.value = listOf(makePhone())
            advanceUntilIdle()
            coVerify(exactly = 1) { phoneApiService.scrobbleStart(any()) }

            // Second phone appearance → queue is already empty, nothing more dispatched.
            phonesFlow.value = emptyList()
            phonesFlow.value = listOf(makePhone())
            advanceUntilIdle()

            coVerify(exactly = 1) { phoneApiService.scrobbleStart(any()) }
        }
    }
}
