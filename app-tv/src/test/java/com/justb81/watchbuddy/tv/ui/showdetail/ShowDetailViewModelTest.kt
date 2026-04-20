package com.justb81.watchbuddy.tv.ui.showdetail

import app.cash.turbine.test
import com.justb81.watchbuddy.core.model.*
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ShowDetailViewModel")
class ShowDetailViewModelTest {

    companion object {
        @JvmField
        @RegisterExtension
        val mainDispatcherRule = MainDispatcherRule()
    }

    private val streamingPrefs: StreamingPreferencesRepository = mockk()
    private val phoneDiscovery: PhoneDiscoveryManager = mockk()
    private val tmdbApi: TmdbApiService = mockk()
    private lateinit var viewModel: ShowDetailViewModel

    private fun makeCapability(apiKey: String?) = DeviceCapability(
        deviceId = "dev1",
        userName = "user",
        deviceName = "Pixel",
        llmBackend = LlmBackend.NONE,
        modelQuality = 0,
        freeRamMb = 0,
        tmdbApiKey = apiKey,
    )

    private fun makePhone(apiKey: String?) = mockk<PhoneDiscoveryManager.DiscoveredPhone> {
        every { capability } returns makeCapability(apiKey)
    }

    private fun makeEntry(
        tmdbId: Int? = 100,
        watchedSeasons: List<TraktWatchedSeason> = listOf(
            TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1, last_watched_at = "2024-01-01T10:00:00Z")))
        )
    ) = EnrichedShowEntry(
        entry = TraktWatchedEntry(
            show = TraktShow("Test Show", 2023, TraktIds(trakt = 1, tmdb = tmdbId)),
            seasons = watchedSeasons
        ),
        tmdb = null,
        posterPath = "/poster.jpg"
    )

    private fun makeTmdbEpisode(season: Int = 1, episode: Int = 2, name: String = "Episode Title", stillPath: String? = "/still.jpg") =
        TmdbEpisode(id = 999, name = name, still_path = stillPath, season_number = season, episode_number = episode)

    @BeforeEach
    fun setUp() {
        every { streamingPrefs.subscribedServiceIds } returns flowOf(emptyList())
        viewModel = ShowDetailViewModel(streamingPrefs, phoneDiscovery, tmdbApi)
    }

    @Nested
    @DisplayName("availableServices")
    inner class AvailableServicesTest {

        @Test
        fun `returns all known services when prefs empty`() = runTest {
            viewModel.availableServices.test {
                val services = awaitItem()
                assertEquals(KNOWN_STREAMING_SERVICES.size, services.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `returns only subscribed services in order`() = runTest {
            every { streamingPrefs.subscribedServiceIds } returns flowOf(listOf("disney", "netflix"))
            viewModel = ShowDetailViewModel(streamingPrefs, phoneDiscovery, tmdbApi)

            viewModel.availableServices.test {
                val services = awaitItem()
                assertEquals(2, services.size)
                assertEquals("disney", services[0].id)
                assertEquals("netflix", services[1].id)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Nested
    @DisplayName("loadNextEpisode")
    inner class LoadNextEpisodeTest {

        @Test
        fun `populates stillUrl and episodeName on success`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("test-key")
            coEvery { tmdbApi.getEpisode(100, 1, 2, "test-key") } returns makeTmdbEpisode(1, 2)

            viewModel.nextEpisode.test {
                // Initial empty state
                awaitItem()
                viewModel.loadNextEpisode(makeEntry())
                // Loading state
                val loading = awaitItem()
                assertTrue(loading.isLoading)
                // Resolved state
                val resolved = awaitItem()
                assertFalse(resolved.isLoading)
                assertTrue(resolved.stillUrl?.contains("still.jpg") == true)
                assertEquals("Episode Title", resolved.episodeName)
                assertEquals("S01E02", resolved.episodeCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `produces null stillUrl when TMDB returns no still_path`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("key")
            coEvery { tmdbApi.getEpisode(any(), any(), any(), any()) } returns makeTmdbEpisode(stillPath = null)

            viewModel.loadNextEpisode(makeEntry())

            val state = viewModel.nextEpisode.value
            assertNull(state.stillUrl)
        }

        @Test
        fun `stays empty when no phone available`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns null

            viewModel.nextEpisode.test {
                awaitItem()
                viewModel.loadNextEpisode(makeEntry())
                // Loading
                awaitItem()
                // Resolved to empty (no API key)
                val state = awaitItem()
                assertFalse(state.isLoading)
                assertNull(state.stillUrl)
                assertNull(state.episodeName)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `stays empty when phone has no API key`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone(null)

            viewModel.loadNextEpisode(makeEntry())

            val state = viewModel.nextEpisode.value
            assertNull(state.stillUrl)
        }

        @Test
        fun `stays empty when show has no TMDB id`() = runTest {
            viewModel.loadNextEpisode(makeEntry(tmdbId = null))

            // loadNextEpisode returns early — state stays at initial default
            val state = viewModel.nextEpisode.value
            assertFalse(state.isLoading)
            assertNull(state.stillUrl)
        }

        @Test
        fun `clears episode data on TMDB API failure`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("key")
            coEvery { tmdbApi.getEpisode(any(), any(), any(), any()) } throws RuntimeException("404")

            viewModel.nextEpisode.test {
                awaitItem()
                viewModel.loadNextEpisode(makeEntry())
                awaitItem() // loading
                val failed = awaitItem()
                assertFalse(failed.isLoading)
                assertNull(failed.stillUrl)
                assertNull(failed.episodeName)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `uses TMDB hint nextAired for episode numbers when hint present`() = runTest {
            val enriched = makeEntry().copy(
                tmdb = TmdbProgressHint(
                    nextAired = TmdbEpisodeSummary(season_number = 2, episode_number = 1)
                )
            )
            every { phoneDiscovery.getBestPhone() } returns makePhone("key")
            coEvery { tmdbApi.getEpisode(100, 2, 1, "key") } returns makeTmdbEpisode(2, 1, "Season Premiere")

            viewModel.loadNextEpisode(enriched)

            val state = viewModel.nextEpisode.value
            assertEquals("S02E01", state.episodeCode)
            assertEquals("Season Premiere", state.episodeName)
        }
    }

    @Nested
    @DisplayName("resolveDeepLink")
    inner class ResolveDeepLinkTest {

        @Test
        fun `substitutes tmdb_id placeholder for Netflix`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 42, slug = "test")))
            val services = listOf(
                StreamingService("netflix", "Netflix", "pkg", "https://netflix.com/title/{tmdb_id}")
            )
            assertEquals("https://netflix.com/title/42", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `substitutes id placeholder for ARD`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 77, slug = "test")))
            val services = listOf(
                StreamingService("ard", "ARD", "pkg", "https://ard.de/video/{id}")
            )
            assertEquals("https://ard.de/video/77", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `substitutes both tmdb_id and slug for Disney+`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 5, slug = "test-show")))
            val services = listOf(
                StreamingService("disney", "Disney+", "pkg", "https://disney.com/series/{slug}/{tmdb_id}")
            )
            assertEquals("https://disney.com/series/test-show/5", viewModel.resolveDeepLink(entry, services))
        }

        // ── Services that only need a slug ────────────────────────────────────

        @Test
        fun `Joyn generates slug link without tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Test Show", 2024, TraktIds(tmdb = null, slug = "test-show")))
            val services = listOf(
                StreamingService("joyn", "Joyn", "pkg", "https://joyn.de/serien/{slug}")
            )
            assertEquals("https://joyn.de/serien/test-show", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `Prime Video generates search URL without tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Breaking Bad", 2008, TraktIds(tmdb = null, slug = "breaking-bad")))
            val services = listOf(
                StreamingService("prime", "Prime Video", "pkg", "https://www.primevideo.com/search?phrase={slug}")
            )
            assertEquals(
                "https://www.primevideo.com/search?phrase=breaking-bad",
                viewModel.resolveDeepLink(entry, services)
            )
        }

        @Test
        fun `ZDF generates slug link without tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Tatort", 2024, TraktIds(tmdb = null, slug = "tatort")))
            val services = listOf(
                StreamingService("zdf", "ZDF", "pkg", "https://www.zdf.de/serien/{slug}")
            )
            assertEquals("https://www.zdf.de/serien/tatort", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `slug-only service still works when tmdb_id is present`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 99, slug = "test")))
            val services = listOf(
                StreamingService("joyn", "Joyn", "pkg", "https://joyn.de/serien/{slug}")
            )
            assertEquals("https://joyn.de/serien/test", viewModel.resolveDeepLink(entry, services))
        }

        // ── Services with no template variables ───────────────────────────────

        @Test
        fun `WaipuTV generates deep link without tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Any Show", 2024, TraktIds(tmdb = null)))
            val services = listOf(
                StreamingService("waipu", "WaipuTV", "tv.waipu.app", "waipu://tv")
            )
            assertEquals("waipu://tv", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `WaipuTV generates deep link even with tmdb_id present`() {
            val entry = TraktWatchedEntry(TraktShow("Any Show", 2024, TraktIds(tmdb = 1)))
            val services = listOf(
                StreamingService("waipu", "WaipuTV", "tv.waipu.app", "waipu://tv")
            )
            assertEquals("waipu://tv", viewModel.resolveDeepLink(entry, services))
        }

        // ── Slug derivation from title ─────────────────────────────────────────

        @Test
        fun `derives slug from show title when slug field is null`() {
            val entry = TraktWatchedEntry(TraktShow("My Show", 2024, TraktIds(tmdb = 1, slug = null)))
            val services = listOf(
                StreamingService("test", "Test", "pkg", "https://test.com/{slug}")
            )
            assertEquals("https://test.com/my-show", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `derives slug from title for slug-only service when both slug and tmdb_id are null`() {
            val entry = TraktWatchedEntry(TraktShow("Breaking Bad", 2008, TraktIds(tmdb = null, slug = null)))
            val services = listOf(
                StreamingService("joyn", "Joyn", "pkg", "https://joyn.de/serien/{slug}")
            )
            assertEquals("https://joyn.de/serien/breaking-bad", viewModel.resolveDeepLink(entry, services))
        }

        // ── Service priority and fallback ─────────────────────────────────────

        @Test
        fun `returns first subscribed service link when all ids available`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 1, slug = "test")))
            val services = listOf(
                StreamingService("disney", "Disney+", "pkg", "https://disney.com/{tmdb_id}"),
                StreamingService("netflix", "Netflix", "pkg", "https://netflix.com/{tmdb_id}")
            )
            assertEquals("https://disney.com/1", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `skips Netflix and falls back to WaipuTV when tmdb_id is null`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = null, slug = "test")))
            val services = listOf(
                StreamingService("netflix", "Netflix", "pkg", "https://netflix.com/title/{tmdb_id}"),
                StreamingService("waipu",   "WaipuTV", "tv.waipu.app", "waipu://tv")
            )
            assertEquals("waipu://tv", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `skips all id-requiring services and uses first slug-only service`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = null, slug = "test-show")))
            val services = listOf(
                StreamingService("netflix", "Netflix",  "pkg", "https://netflix.com/title/{tmdb_id}"),
                StreamingService("disney",  "Disney+",  "pkg", "https://disney.com/series/{slug}/{tmdb_id}"),
                StreamingService("prime",   "Prime",    "pkg", "https://www.primevideo.com/search?phrase={slug}")
            )
            assertEquals("https://www.primevideo.com/search?phrase=test-show", viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `returns null only when all subscribed services need an unavailable tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = null)))
            val services = listOf(
                StreamingService("netflix", "Netflix", "pkg", "https://netflix.com/title/{tmdb_id}"),
                StreamingService("ard",     "ARD",     "pkg", "https://ard.de/video/{id}")
            )
            assertNull(viewModel.resolveDeepLink(entry, services))
        }

        @Test
        fun `falls back to KNOWN_STREAMING_SERVICES when subscribed list is empty and tmdb_id present`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 1, slug = "test")))
            val result = viewModel.resolveDeepLink(entry, emptyList())
            assertNotNull(result)
            assertTrue(result!!.contains("1"))
        }

        @Test
        fun `falls back to slug-only service in KNOWN_STREAMING_SERVICES when tmdb_id is null`() {
            val entry = TraktWatchedEntry(TraktShow("Test Show", 2024, TraktIds(tmdb = null, slug = "test-show")))
            val result = viewModel.resolveDeepLink(entry, emptyList())
            assertNotNull(result)
            assertTrue(result!!.contains("test-show"))
        }
    }
}
