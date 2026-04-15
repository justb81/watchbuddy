package com.justb81.watchbuddy.tv.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.tv.data.TvShowCache
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.discovery.PhoneScrobbleActionResponse
import com.justb81.watchbuddy.tv.discovery.PhoneScrobbleRequest
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import android.net.nsd.NsdServiceInfo

@DisplayName("MediaSessionScrobbler")
class MediaSessionScrobblerTest {

    private val context: Context = mockk(relaxed = true)
    private val traktApi: TraktApiService = mockk()
    private val tvShowCache: TvShowCache = mockk()
    private val tvTokenCache: TvTokenCache = mockk()
    private val phoneDiscovery: PhoneDiscoveryManager = mockk()
    private val phoneApiClientFactory: PhoneApiClientFactory = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(
            context, traktApi, tvShowCache, tvTokenCache, phoneDiscovery, phoneApiClientFactory
        )
    }

    /** Helper: creates a mock DiscoveredPhone with a given base URL. */
    private fun mockPhone(baseUrl: String): PhoneDiscoveryManager.DiscoveredPhone {
        val capability = DeviceCapability(
            deviceId = baseUrl,
            userName = "user",
            deviceName = "Phone",
            llmBackend = LlmBackend.LITERT,
            modelQuality = 75,
            freeRamMb = 4096,
            isAvailable = true
        )
        return PhoneDiscoveryManager.DiscoveredPhone(
            serviceInfo = mockk<NsdServiceInfo>(relaxed = true),
            capability = capability,
            score = 75,
            baseUrl = baseUrl
        )
    }

    // ── normalize() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("normalize()")
    inner class NormalizeTest {

        @Test
        fun `lowercases input`() {
            assertEquals("breaking bad", scrobbler.normalize("Breaking Bad"))
        }

        @Test
        fun `strips special characters`() {
            assertEquals("mr robot", scrobbler.normalize("Mr. Robot!"))
        }

        @Test
        fun `removes leading article the`() {
            assertEquals("walking dead", scrobbler.normalize("The Walking Dead"))
        }

        @Test
        fun `collapses multiple spaces`() {
            assertEquals("game of thrones", scrobbler.normalize("Game  of   Thrones"))
        }

        @Test
        fun `trims whitespace`() {
            assertEquals("test", scrobbler.normalize("  test  "))
        }

        @Test
        fun `handles empty string`() {
            assertEquals("", scrobbler.normalize(""))
        }

        @Test
        fun `combined normalization`() {
            assertEquals("walking dead", scrobbler.normalize("The Walking Dead!!!"))
        }

        @Test
        fun `strips parentheses and brackets`() {
            assertEquals("show name 2024", scrobbler.normalize("Show Name (2024)"))
        }
    }

    // ── fuzzyScore() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("fuzzyScore()")
    inner class FuzzyScoreTest {

        @Test
        fun `exact match returns 1_0`() {
            assertEquals(1.0f, scrobbler.fuzzyScore("Test", "Test"))
        }

        @Test
        fun `exact match after normalization - Breaking Bad`() {
            assertEquals(1.0f, scrobbler.fuzzyScore("Breaking Bad", "Breaking Bad"))
        }

        @Test
        fun `exact match after normalization - Walking Dead with article`() {
            assertEquals(1.0f, scrobbler.fuzzyScore("the walking dead", "Walking Dead"))
        }

        @Test
        fun `exact match after normalization - Mr Robot`() {
            assertEquals(1.0f, scrobbler.fuzzyScore("MR. ROBOT", "mr robot"))
        }

        @Test
        fun `prefix match returns 0_95`() {
            assertEquals(0.95f, scrobbler.fuzzyScore("Breaking Bad", "Breaking Bad Season"))
        }

        @Test
        fun `completely different strings return low score`() {
            val score = scrobbler.fuzzyScore("Breaking Bad", "Cooking Show")
            assertTrue(score < 0.50f)
        }

        @Test
        fun `empty string returns 0_0`() {
            assertEquals(0f, scrobbler.fuzzyScore("", "test"))
        }

        @Test
        fun `both empty strings return 0_0`() {
            assertEquals(0f, scrobbler.fuzzyScore("", ""))
        }

        @Test
        fun `similar strings - Breaking Bad vs Breaking Baad`() {
            assertTrue(scrobbler.fuzzyScore("Breaking Bad", "Breaking Baad") >= 0.70f)
        }

        @Test
        fun `similar strings - Stranger Things vs Stranger Thing`() {
            assertTrue(scrobbler.fuzzyScore("Stranger Things", "Stranger Thing") >= 0.70f)
        }

        @Test
        fun `similar strings - Game of Thrones vs Game of Throne`() {
            assertTrue(scrobbler.fuzzyScore("Game of Thrones", "Game of Throne") >= 0.70f)
        }

        @Test
        fun `score is between 0 and 1`() {
            val score = scrobbler.fuzzyScore("Show A", "Show B")
            assertTrue(score in 0f..1f)
        }
    }

    // ── autoScrobble() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("autoScrobble()")
    inner class AutoScrobbleTest {

        private val testShow = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1))
        private val testEpisode = TraktEpisode(season = 1, number = 1)
        private val testCandidate = ScrobbleCandidate(
            "com.netflix", "Breaking Bad S01E01", 0.95f, testShow, testEpisode
        )

        private fun mockPhoneApiService(): PhoneApiService = mockk<PhoneApiService>().also { svc ->
            coEvery { svc.scrobbleStart(any()) } returns PhoneScrobbleActionResponse(true)
        }

        @Test
        fun `calls scrobble start on single phone`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            val mockSvc = mockPhoneApiService()
            every { phoneApiClientFactory.createClient("http://phone1:8765/") } returns mockSvc

            scrobbler.autoScrobble(testCandidate)

            coVerify {
                mockSvc.scrobbleStart(
                    match<PhoneScrobbleRequest> {
                        it.show == testShow && it.episode == testEpisode && it.progress == 0f
                    }
                )
            }
        }

        @Test
        fun `calls scrobble start on each connected phone independently`() = runTest {
            val phone1 = mockPhone("http://phone1:8765/")
            val phone2 = mockPhone("http://phone2:8765/")
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone1, phone2))
            val mockSvc1 = mockPhoneApiService()
            val mockSvc2 = mockPhoneApiService()
            every { phoneApiClientFactory.createClient("http://phone1:8765/") } returns mockSvc1
            every { phoneApiClientFactory.createClient("http://phone2:8765/") } returns mockSvc2

            scrobbler.autoScrobble(testCandidate)

            coVerify { mockSvc1.scrobbleStart(any()) }
            coVerify { mockSvc2.scrobbleStart(any()) }
        }

        @Test
        fun `skips when no phones available`() = runTest {
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(emptyList())

            scrobbler.autoScrobble(testCandidate)

            verify(exactly = 0) { phoneApiClientFactory.createClient(any()) }
        }

        @Test
        fun `skips unavailable phones`() = runTest {
            val unavailableCapability = DeviceCapability(
                deviceId = "offline",
                userName = "user",
                deviceName = "Phone",
                llmBackend = LlmBackend.NONE,
                modelQuality = 0,
                freeRamMb = 0,
                isAvailable = false
            )
            val offlinePhone = PhoneDiscoveryManager.DiscoveredPhone(
                serviceInfo = mockk(relaxed = true),
                capability = unavailableCapability,
                score = 0,
                baseUrl = "http://offline:8765/"
            )
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(offlinePhone))

            scrobbler.autoScrobble(testCandidate)

            verify(exactly = 0) { phoneApiClientFactory.createClient(any()) }
        }

        @Test
        fun `skips when no matched show`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            val mockSvc = mockPhoneApiService()
            every { phoneApiClientFactory.createClient(any()) } returns mockSvc

            val candidate = ScrobbleCandidate("pkg", "Title", 0.95f, null, testEpisode)
            scrobbler.autoScrobble(candidate)

            coVerify(exactly = 0) { mockSvc.scrobbleStart(any()) }
        }

        @Test
        fun `skips when no matched episode`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            val mockSvc = mockPhoneApiService()
            every { phoneApiClientFactory.createClient(any()) } returns mockSvc

            val candidate = ScrobbleCandidate("pkg", "Title", 0.95f, testShow, null)
            scrobbler.autoScrobble(candidate)

            coVerify(exactly = 0) { mockSvc.scrobbleStart(any()) }
        }

        @Test
        fun `phone API failure does not block scrobble for other phones`() = runTest {
            val phone1 = mockPhone("http://phone1:8765/")
            val phone2 = mockPhone("http://phone2:8765/")
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone1, phone2))
            val failingSvc = mockk<PhoneApiService>()
            val successSvc = mockk<PhoneApiService>()
            coEvery { failingSvc.scrobbleStart(any()) } throws RuntimeException("Timeout")
            coEvery { successSvc.scrobbleStart(any()) } returns PhoneScrobbleActionResponse(true)
            every { phoneApiClientFactory.createClient("http://phone1:8765/") } returns failingSvc
            every { phoneApiClientFactory.createClient("http://phone2:8765/") } returns successSvc

            // Should not throw
            scrobbler.autoScrobble(testCandidate)

            coVerify { failingSvc.scrobbleStart(any()) }
            coVerify { successSvc.scrobbleStart(any()) }
        }

        @Test
        fun `does not call Trakt API directly for scrobbling`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            val mockSvc = mockPhoneApiService()
            every { phoneApiClientFactory.createClient(any()) } returns mockSvc

            scrobbler.autoScrobble(testCandidate)

            coVerify(exactly = 0) { traktApi.scrobbleStart(any(), any()) }
            coVerify(exactly = 0) { traktApi.scrobblePause(any(), any()) }
            coVerify(exactly = 0) { traktApi.scrobbleStop(any(), any()) }
        }
    }

    // ── Levenshtein distance via fuzzyScore ──────────────────────────────────

    @Nested
    @DisplayName("Levenshtein distance via fuzzyScore")
    inner class LevenshteinTest {

        @Test
        fun `identical strings have perfect score`() {
            assertEquals(1.0f, scrobbler.fuzzyScore("hello", "hello"))
        }

        @Test
        fun `single character difference reduces score`() {
            val score = scrobbler.fuzzyScore("hello", "hallo")
            assertTrue(score > 0.7f)
            assertTrue(score < 1.0f)
        }

        @Test
        fun `completely different strings have low score`() {
            val score = scrobbler.fuzzyScore("abcdef", "zyxwvu")
            assertTrue(score < 0.3f)
        }
    }
}
