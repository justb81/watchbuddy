package com.justb81.watchbuddy.tv.scrobbler

import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.model.TmdbTvSearchResponse
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
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
    private val tmdbApiService: TmdbApiService = mockk()
    private val tvShowCache: TvShowCache = mockk()
    private val phoneDiscovery: PhoneDiscoveryManager = mockk()
    private val phoneApiClientFactory: PhoneApiClientFactory = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(
            context, tmdbApiService, tvShowCache, phoneDiscovery, phoneApiClientFactory
        )
    }

    /** Helper: creates a mock PlaybackState with a given position (in milliseconds). */
    private fun mockPlaybackState(positionMs: Long): PlaybackState =
        mockk<PlaybackState>().also { every { it.position } returns positionMs }

    /** Helper: creates a mock MediaMetadata with a given duration (in milliseconds). */
    private fun mockMetadata(durationMs: Long): MediaMetadata =
        mockk<MediaMetadata>().also {
            every { it.getLong(MediaMetadata.METADATA_KEY_DURATION) } returns durationMs
        }

    /** Helper: creates a mock DiscoveredPhone with a given base URL and optional TMDB API key. */
    private fun mockPhone(
        baseUrl: String,
        tmdbApiKey: String? = "test-tmdb-key"
    ): PhoneDiscoveryManager.DiscoveredPhone {
        val capability = DeviceCapability(
            deviceId = baseUrl,
            userName = "user",
            deviceName = "Phone",
            llmBackend = LlmBackend.LITERT,
            modelQuality = 75,
            freeRamMb = 4096,
            isAvailable = true,
            tmdbApiKey = tmdbApiKey
        )
        return PhoneDiscoveryManager.DiscoveredPhone(
            serviceInfo = mockk<NsdServiceInfo>(relaxed = true),
            txtRecord = null,
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

    // ── computeProgress() ────────────────────────────────────────────────────

    @Nested
    @DisplayName("computeProgress()")
    inner class ComputeProgressTest {

        @Test
        fun `returns null when duration is zero`() {
            val result = scrobbler.computeProgress(mockPlaybackState(60_000), mockMetadata(0))
            assertNull(result)
        }

        @Test
        fun `returns null when duration is negative`() {
            val result = scrobbler.computeProgress(mockPlaybackState(60_000), mockMetadata(-1))
            assertNull(result)
        }

        @Test
        fun `returns null when position is negative`() {
            val result = scrobbler.computeProgress(mockPlaybackState(-1), mockMetadata(1_200_000))
            assertNull(result)
        }

        @Test
        fun `returns 50 percent when position is half of duration`() {
            val result = scrobbler.computeProgress(mockPlaybackState(600_000), mockMetadata(1_200_000))
            assertEquals(50f, result)
        }

        @Test
        fun `returns 0 when position is zero`() {
            val result = scrobbler.computeProgress(mockPlaybackState(0), mockMetadata(1_200_000))
            assertEquals(0f, result)
        }

        @Test
        fun `clamps to 100 when position exceeds duration`() {
            val result = scrobbler.computeProgress(mockPlaybackState(2_000_000), mockMetadata(1_200_000))
            assertEquals(100f, result)
        }

        @Test
        fun `returns approximately 82 percent for 20 min of 24 min episode`() {
            val result = scrobbler.computeProgress(
                mockPlaybackState(20 * 60 * 1000L),
                mockMetadata(24 * 60 * 1000L)
            )
            assertNotNull(result)
            assertTrue(result!! in 83.3f..83.4f)
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
                txtRecord = null,
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
        fun `does not call TMDB API for scrobbling`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            val mockSvc = mockPhoneApiService()
            every { phoneApiClientFactory.createClient(any()) } returns mockSvc

            scrobbler.autoScrobble(testCandidate)

            coVerify(exactly = 0) { tmdbApiService.searchTv(any(), any()) }
            coVerify(exactly = 0) { tmdbApiService.getShow(any(), any()) }
        }

        @Test
        fun `forwards explicit progress value`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            val mockSvc = mockPhoneApiService()
            every { phoneApiClientFactory.createClient("http://phone1:8765/") } returns mockSvc

            scrobbler.autoScrobble(testCandidate, progress = 42.5f)

            coVerify {
                mockSvc.scrobbleStart(match<PhoneScrobbleRequest> { it.progress == 42.5f })
            }
        }

        @Test
        fun `falls back to 0 when progress is null`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            val mockSvc = mockPhoneApiService()
            every { phoneApiClientFactory.createClient("http://phone1:8765/") } returns mockSvc

            scrobbler.autoScrobble(testCandidate, progress = null)

            coVerify {
                mockSvc.scrobbleStart(match<PhoneScrobbleRequest> { it.progress == 0f })
            }
        }
    }

    // ── handleScrobblePause() ────────────────────────────────────────────────

    @Nested
    @DisplayName("handleScrobblePause()")
    inner class HandleScrobblePauseTest {

        private val testShow = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1))
        private val testEpisode = TraktEpisode(season = 1, number = 1)
        private val testCandidate = ScrobbleCandidate(
            "com.netflix", "Breaking Bad S01E01", 0.95f, testShow, testEpisode
        )

        private fun mockPauseSvc(): PhoneApiService = mockk<PhoneApiService>().also { svc ->
            coEvery { svc.scrobbleStart(any()) } returns PhoneScrobbleActionResponse(true)
            coEvery { svc.scrobblePause(any()) } returns PhoneScrobbleActionResponse(true)
        }

        /** Primes `currentlyScrobbling` by running an autoScrobble first. */
        private suspend fun primeCurrentlyScrobbling(
            phone: PhoneDiscoveryManager.DiscoveredPhone,
            svc: PhoneApiService
        ) {
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            every { phoneApiClientFactory.createClient(phone.baseUrl) } returns svc
            scrobbler.autoScrobble(testCandidate)
            every { tvShowCache.getCachedShows() } returns listOf(TraktWatchedEntry(show = testShow))
        }

        @Test
        fun `forwards explicit progress value on pause`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            val svc = mockPauseSvc()
            primeCurrentlyScrobbling(phone, svc)

            scrobbler.handleScrobblePause("Breaking Bad S01E01", progress = 35f)

            coVerify {
                svc.scrobblePause(match<PhoneScrobbleRequest> { it.progress == 35f })
            }
        }

        @Test
        fun `falls back to 50 when progress is null on pause`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            val svc = mockPauseSvc()
            primeCurrentlyScrobbling(phone, svc)

            scrobbler.handleScrobblePause("Breaking Bad S01E01", progress = null)

            coVerify {
                svc.scrobblePause(match<PhoneScrobbleRequest> { it.progress == 50f })
            }
        }

        @Test
        fun `skips pause when rawTitle does not match currentlyScrobbling`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            val svc = mockPauseSvc()
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            every { phoneApiClientFactory.createClient(any()) } returns svc

            scrobbler.handleScrobblePause("Some Other Title", progress = 50f)

            coVerify(exactly = 0) { svc.scrobblePause(any()) }
        }
    }

    // ── handleScrobbleStop() ─────────────────────────────────────────────────

    @Nested
    @DisplayName("handleScrobbleStop()")
    inner class HandleScrobbleStopTest {

        private val testShow = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1))
        private val testEpisode = TraktEpisode(season = 1, number = 1)
        private val testCandidate = ScrobbleCandidate(
            "com.netflix", "Breaking Bad S01E01", 0.95f, testShow, testEpisode
        )

        private fun mockStopSvc(): PhoneApiService = mockk<PhoneApiService>().also { svc ->
            coEvery { svc.scrobbleStart(any()) } returns PhoneScrobbleActionResponse(true)
            coEvery { svc.scrobbleStop(any()) } returns PhoneScrobbleActionResponse(true)
        }

        private suspend fun primeCurrentlyScrobbling(
            phone: PhoneDiscoveryManager.DiscoveredPhone,
            svc: PhoneApiService
        ) {
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            every { phoneApiClientFactory.createClient(phone.baseUrl) } returns svc
            scrobbler.autoScrobble(testCandidate)
            every { tvShowCache.getCachedShows() } returns listOf(TraktWatchedEntry(show = testShow))
        }

        @Test
        fun `forwards real progress value below watched threshold`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            val svc = mockStopSvc()
            primeCurrentlyScrobbling(phone, svc)

            scrobbler.handleScrobbleStop("Breaking Bad S01E01", progress = 25f)

            coVerify {
                svc.scrobbleStop(match<PhoneScrobbleRequest> { it.progress == 25f })
            }
        }

        @Test
        fun `forwards real progress value above watched threshold`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            val svc = mockStopSvc()
            primeCurrentlyScrobbling(phone, svc)

            scrobbler.handleScrobbleStop("Breaking Bad S01E01", progress = 82f)

            coVerify {
                svc.scrobbleStop(match<PhoneScrobbleRequest> { it.progress == 82f })
            }
        }

        @Test
        fun `skips stop call when progress is null`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            val svc = mockStopSvc()
            primeCurrentlyScrobbling(phone, svc)

            scrobbler.handleScrobbleStop("Breaking Bad S01E01", progress = null)

            coVerify(exactly = 0) { svc.scrobbleStop(any()) }
        }

        @Test
        fun `skips stop when rawTitle does not match currentlyScrobbling`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            val svc = mockStopSvc()
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            every { phoneApiClientFactory.createClient(any()) } returns svc

            scrobbler.handleScrobbleStop("Some Other Title", progress = 100f)

            coVerify(exactly = 0) { svc.scrobbleStop(any()) }
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

    // ── TMDB search fallback ─────────────────────────────────────────────────

    @Nested
    @DisplayName("TMDB search fallback")
    inner class TmdbSearchFallbackTest {

        private val breakingBadTmdb = TmdbShow(
            id = 1396,
            name = "Breaking Bad",
            first_air_date = "2008-01-20"
        )

        @BeforeEach
        fun setUpCache() {
            every { tvShowCache.getCachedShows() } returns emptyList()
        }

        @Test
        fun `uses TMDB search when cache is empty`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.getBestPhone() } returns phone
            coEvery {
                tmdbApiService.searchTv("Breaking Bad", "test-tmdb-key")
            } returns TmdbTvSearchResponse(listOf(breakingBadTmdb))

            val candidate = scrobbler.matchTitle("com.netflix", "Breaking Bad S01E01")

            assertNotNull(candidate)
            assertEquals("Breaking Bad", candidate!!.matchedShow?.title)
            assertEquals(TraktIds(tmdb = 1396), candidate.matchedShow?.ids)
            assertEquals(2008, candidate.matchedShow?.year)
            assertEquals(1, candidate.matchedEpisode?.season)
            assertEquals(1, candidate.matchedEpisode?.number)
        }

        @Test
        fun `returns null when no phone has TMDB API key`() = runTest {
            val phone = mockPhone("http://phone1:8765/", tmdbApiKey = null)
            every { phoneDiscovery.getBestPhone() } returns phone

            val candidate = scrobbler.matchTitle("com.netflix", "Breaking Bad S01E01")

            assertNull(candidate)
            coVerify(exactly = 0) { tmdbApiService.searchTv(any(), any()) }
        }

        @Test
        fun `returns null when no phone connected`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns null

            val candidate = scrobbler.matchTitle("com.netflix", "Breaking Bad S01E01")

            assertNull(candidate)
            coVerify(exactly = 0) { tmdbApiService.searchTv(any(), any()) }
        }

        @Test
        fun `returns null when TMDB search score too low`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.getBestPhone() } returns phone
            val unrelatedShow = TmdbShow(id = 999, name = "Completely Different Show")
            coEvery {
                tmdbApiService.searchTv(any(), any())
            } returns TmdbTvSearchResponse(listOf(unrelatedShow))

            val candidate = scrobbler.matchTitle("com.netflix", "Breaking Bad S01E01")

            assertNull(candidate)
        }

        @Test
        fun `returns null when TMDB search returns empty results`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.getBestPhone() } returns phone
            coEvery {
                tmdbApiService.searchTv(any(), any())
            } returns TmdbTvSearchResponse(emptyList())

            val candidate = scrobbler.matchTitle("com.netflix", "Unknown Show")

            assertNull(candidate)
        }

        @Test
        fun `returns null when TMDB search throws`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.getBestPhone() } returns phone
            coEvery {
                tmdbApiService.searchTv(any(), any())
            } throws RuntimeException("Network error")

            val candidate = scrobbler.matchTitle("com.netflix", "Breaking Bad S01E01")

            assertNull(candidate)
        }

        @Test
        fun `cache hit bypasses TMDB search`() = runTest {
            val cachedShow = TraktShow(
                title = "Breaking Bad",
                ids = TraktIds(trakt = 1, tmdb = 1396)
            )
            every { tvShowCache.getCachedShows() } returns listOf(
                TraktWatchedEntry(show = cachedShow)
            )
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.getBestPhone() } returns phone

            val candidate = scrobbler.matchTitle("com.netflix", "Breaking Bad S01E01")

            assertNotNull(candidate)
            assertEquals(cachedShow, candidate!!.matchedShow)
            coVerify(exactly = 0) { tmdbApiService.searchTv(any(), any()) }
        }

        @Test
        fun `TMDB result sets tmdb ID in TraktShow ids`() = runTest {
            val phone = mockPhone("http://phone1:8765/")
            every { phoneDiscovery.getBestPhone() } returns phone
            coEvery {
                tmdbApiService.searchTv(any(), any())
            } returns TmdbTvSearchResponse(listOf(breakingBadTmdb))

            val candidate = scrobbler.matchTitle("com.netflix", "Breaking Bad")

            assertNotNull(candidate?.matchedShow?.ids?.tmdb)
            assertEquals(1396, candidate!!.matchedShow!!.ids.tmdb)
            assertNull(candidate.matchedShow!!.ids.trakt)
        }
    }
}
