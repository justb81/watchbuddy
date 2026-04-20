package com.justb81.watchbuddy.tv.ui.showdetail

import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.model.ResolvedProvider
import com.justb81.watchbuddy.core.model.TmdbEpisode
import com.justb81.watchbuddy.core.model.TmdbEpisodeSummary
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.tv.MainDispatcherRule
import com.justb81.watchbuddy.tv.data.LastUsedProviderRepository
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.data.WatchProvidersRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
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

    private val watchProviders: WatchProvidersRepository = mockk()
    private val lastUsedRepo: LastUsedProviderRepository = mockk()
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
        ),
    ) = EnrichedShowEntry(
        entry = TraktWatchedEntry(
            show = TraktShow("Test Show", 2023, TraktIds(trakt = 1, tmdb = tmdbId)),
            seasons = watchedSeasons,
        ),
        tmdb = null,
        posterPath = "/poster.jpg",
    )

    private fun makeTmdbEpisode(
        season: Int = 1,
        episode: Int = 2,
        name: String = "Episode Title",
        stillPath: String? = "/still.jpg",
    ) = TmdbEpisode(
        id = 999,
        name = name,
        still_path = stillPath,
        season_number = season,
        episode_number = episode,
    )

    private fun makeProvider(
        providerId: Int = 8,
        name: String = "Netflix",
        deepLinkTemplate: String? = "https://www.netflix.com/title/{tmdb_id}",
        tmdbPageUrl: String? = null,
        isInstalled: Boolean = true,
        isLastUsed: Boolean = false,
    ) = ResolvedProvider(
        providerId = providerId,
        name = name,
        logoPath = null,
        packageName = "com.netflix.ninja",
        deepLinkTemplate = deepLinkTemplate,
        isInstalled = isInstalled,
        isLastUsed = isLastUsed,
        tmdbPageUrl = tmdbPageUrl,
    )

    @BeforeEach
    fun setUp() {
        coEvery { streamingPrefs.getShowNonInstalledProviders() } returns false
        coEvery { lastUsedRepo.recordUsed(any(), any()) } just runs
        viewModel = ShowDetailViewModel(watchProviders, lastUsedRepo, streamingPrefs, phoneDiscovery, tmdbApi)
    }

    @Nested
    @DisplayName("loadProviders")
    inner class LoadProvidersTest {

        @Test
        fun `emits Success with resolved providers`() = runTest {
            val providers = listOf(makeProvider())
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery { watchProviders.getResolvedProviders(100, any(), "api-key", false) } returns (providers to null)

            viewModel.loadProviders(makeEntry())

            val state = assertInstanceOf(ProviderListUiState.Success::class.java, viewModel.providers.value)
            assertEquals(providers, state.providers)
        }

        @Test
        fun `emits Empty with tmdbPageUrl when no providers returned`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(any(), any(), any(), any())
            } returns (emptyList<ResolvedProvider>() to "https://tmdb.org")

            viewModel.loadProviders(makeEntry())

            val state = assertInstanceOf(ProviderListUiState.Empty::class.java, viewModel.providers.value)
            assertEquals("https://tmdb.org", state.tmdbPageUrl)
        }

        @Test
        fun `emits Error when no phone available`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns null

            viewModel.loadProviders(makeEntry())

            assertEquals(ProviderListUiState.Error, viewModel.providers.value)
        }

        @Test
        fun `emits Error when phone has no API key`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone(null)

            viewModel.loadProviders(makeEntry())

            assertEquals(ProviderListUiState.Error, viewModel.providers.value)
        }

        @Test
        fun `emits Error on repository exception`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery { watchProviders.getResolvedProviders(any(), any(), any(), any()) } throws RuntimeException("Network error")

            viewModel.loadProviders(makeEntry())

            assertEquals(ProviderListUiState.Error, viewModel.providers.value)
        }

        @Test
        fun `emits Empty when show has no TMDB id`() = runTest {
            viewModel.loadProviders(makeEntry(tmdbId = null))

            val state = assertInstanceOf(ProviderListUiState.Empty::class.java, viewModel.providers.value)
            assertNull(state.tmdbPageUrl)
        }
    }

    @Nested
    @DisplayName("loadNextEpisode")
    inner class LoadNextEpisodeTest {

        @Test
        fun `populates stillUrl and episodeName on success`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("test-key")
            coEvery { tmdbApi.getEpisode(100, 1, 2, "test-key", "en-US") } returns makeTmdbEpisode(1, 2)

            viewModel.loadNextEpisode(makeEntry())

            val state = viewModel.nextEpisode.value
            assert(!state.isLoading)
            assert(state.stillUrl?.contains("still.jpg") == true)
            assertEquals("Episode Title", state.episodeName)
            assertEquals("S01E02", state.episodeCode)
        }

        @Test
        fun `produces null stillUrl when TMDB returns no still_path`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("key")
            coEvery { tmdbApi.getEpisode(any(), any(), any(), any(), any()) } returns makeTmdbEpisode(stillPath = null)

            viewModel.loadNextEpisode(makeEntry())

            assertNull(viewModel.nextEpisode.value.stillUrl)
        }

        @Test
        fun `stays empty when no phone available`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns null

            viewModel.loadNextEpisode(makeEntry())

            val state = viewModel.nextEpisode.value
            assert(!state.isLoading)
            assertNull(state.stillUrl)
            assertNull(state.episodeName)
        }

        @Test
        fun `stays empty when phone has no API key`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone(null)

            viewModel.loadNextEpisode(makeEntry())

            assertNull(viewModel.nextEpisode.value.stillUrl)
        }

        @Test
        fun `stays empty when show has no TMDB id`() = runTest {
            viewModel.loadNextEpisode(makeEntry(tmdbId = null))

            val state = viewModel.nextEpisode.value
            assert(!state.isLoading)
            assertNull(state.stillUrl)
        }

        @Test
        fun `clears episode data on TMDB API failure`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("key")
            coEvery { tmdbApi.getEpisode(any(), any(), any(), any(), any()) } throws RuntimeException("404")

            viewModel.loadNextEpisode(makeEntry())

            val state = viewModel.nextEpisode.value
            assert(!state.isLoading)
            assertNull(state.stillUrl)
            assertNull(state.episodeName)
        }

        @Test
        fun `uses TMDB hint nextAired for episode numbers when hint present`() = runTest {
            val enriched = makeEntry().copy(
                tmdb = TmdbProgressHint(
                    nextAired = TmdbEpisodeSummary(season_number = 2, episode_number = 1),
                )
            )
            every { phoneDiscovery.getBestPhone() } returns makePhone("key")
            coEvery { tmdbApi.getEpisode(100, 2, 1, "key", "en-US") } returns makeTmdbEpisode(2, 1, "Season Premiere")

            viewModel.loadNextEpisode(enriched)

            val state = viewModel.nextEpisode.value
            assertEquals("S02E01", state.episodeCode)
            assertEquals("Season Premiere", state.episodeName)
        }
    }

    @Nested
    @DisplayName("resolveDeepLink / onProviderSelected")
    inner class ResolveDeepLinkTest {

        @Test
        fun `substitutes tmdb_id placeholder for Netflix`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 42, slug = "test")))
            val provider = makeProvider(deepLinkTemplate = "https://netflix.com/title/{tmdb_id}")
            assertEquals("https://netflix.com/title/42", viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `substitutes id placeholder for ARD`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 77, slug = "test")))
            val provider = makeProvider(deepLinkTemplate = "https://ard.de/video/{id}")
            assertEquals("https://ard.de/video/77", viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `substitutes both tmdb_id and slug for Disney+`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 5, slug = "test-show")))
            val provider = makeProvider(deepLinkTemplate = "https://disney.com/series/{slug}/{tmdb_id}")
            assertEquals("https://disney.com/series/test-show/5", viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `Joyn generates slug link without tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Test Show", 2024, TraktIds(tmdb = null, slug = "test-show")))
            val provider = makeProvider(deepLinkTemplate = "https://joyn.de/serien/{slug}", tmdbPageUrl = "https://tmdb.org")
            assertEquals("https://joyn.de/serien/test-show", viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `Prime Video generates search URL without tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Breaking Bad", 2008, TraktIds(tmdb = null, slug = "breaking-bad")))
            val provider = makeProvider(deepLinkTemplate = "https://www.primevideo.com/search?phrase={slug}")
            assertEquals(
                "https://www.primevideo.com/search?phrase=breaking-bad",
                viewModel.onProviderSelected(provider, entry),
            )
        }

        @Test
        fun `ZDF generates slug link without tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Tatort", 2024, TraktIds(tmdb = null, slug = "tatort")))
            val provider = makeProvider(deepLinkTemplate = "https://www.zdf.de/serien/{slug}")
            assertEquals("https://www.zdf.de/serien/tatort", viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `slug-only service still works when tmdb_id is present`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 99, slug = "test")))
            val provider = makeProvider(deepLinkTemplate = "https://joyn.de/serien/{slug}")
            assertEquals("https://joyn.de/serien/test", viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `WaipuTV generates deep link without tmdb_id`() {
            val entry = TraktWatchedEntry(TraktShow("Any Show", 2024, TraktIds(tmdb = null)))
            val provider = makeProvider(deepLinkTemplate = "waipu://tv")
            assertEquals("waipu://tv", viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `WaipuTV generates deep link even with tmdb_id present`() {
            val entry = TraktWatchedEntry(TraktShow("Any Show", 2024, TraktIds(tmdb = 1)))
            val provider = makeProvider(deepLinkTemplate = "waipu://tv")
            assertEquals("waipu://tv", viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `derives slug from show title when slug field is null`() {
            val entry = TraktWatchedEntry(TraktShow("My Show", 2024, TraktIds(tmdb = 1, slug = null)))
            val provider = makeProvider(deepLinkTemplate = "https://test.com/{slug}")
            assertEquals("https://test.com/my-show", viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `derives slug from title when both slug and tmdb_id are null`() {
            val entry = TraktWatchedEntry(TraktShow("Breaking Bad", 2008, TraktIds(tmdb = null, slug = null)))
            val provider = makeProvider(deepLinkTemplate = "https://joyn.de/serien/{slug}")
            assertEquals("https://joyn.de/serien/breaking-bad", viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `falls back to tmdbPageUrl when template requires tmdb_id but none available`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = null)))
            val provider = makeProvider(
                deepLinkTemplate = "https://netflix.com/title/{tmdb_id}",
                tmdbPageUrl = "https://www.themoviedb.org/tv/100/watch",
            )
            assertEquals("https://www.themoviedb.org/tv/100/watch", viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `returns null when template requires tmdb_id but none available and no page url`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = null)))
            val provider = makeProvider(deepLinkTemplate = "https://netflix.com/title/{tmdb_id}", tmdbPageUrl = null)
            assertNull(viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `returns null when no deepLinkTemplate and no tmdbPageUrl`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 1)))
            val provider = makeProvider(deepLinkTemplate = null, tmdbPageUrl = null)
            assertNull(viewModel.onProviderSelected(provider, entry))
        }

        @Test
        fun `resolveDeepLink uses first provider in Success state`() = runTest {
            val provider = makeProvider(deepLinkTemplate = "https://netflix.com/title/{tmdb_id}")
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery { watchProviders.getResolvedProviders(100, any(), "api-key", false) } returns (listOf(provider) to null)

            viewModel.loadProviders(makeEntry())

            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 100, slug = "test")))
            assertEquals("https://netflix.com/title/100", viewModel.resolveDeepLink(entry))
        }

        @Test
        fun `resolveDeepLink returns null when providers not in Success state`() {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 1)))
            assertNull(viewModel.resolveDeepLink(entry))
        }

        @Test
        fun `onProviderSelected records last-used when tmdb id available`() = runTest {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = 100)))
            val provider = makeProvider(providerId = 8)

            viewModel.onProviderSelected(provider, entry)

            coVerify { lastUsedRepo.recordUsed(100, 8) }
        }

        @Test
        fun `onProviderSelected does not record last-used when tmdb id is null`() = runTest {
            val entry = TraktWatchedEntry(TraktShow("Test", 2024, TraktIds(tmdb = null)))
            val provider = makeProvider(deepLinkTemplate = "waipu://tv")

            viewModel.onProviderSelected(provider, entry)

            coVerify(exactly = 0) { lastUsedRepo.recordUsed(any(), any()) }
        }
    }
}
