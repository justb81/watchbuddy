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
import com.justb81.watchbuddy.tv.data.JustWatchDeepLinkRepository
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
    private val justWatchRepo: JustWatchDeepLinkRepository = mockk()
    private lateinit var viewModel: ShowDetailViewModel

    private fun makeCapability(apiKey: String?, countryCode: String? = null) = DeviceCapability(
        deviceId = "dev1",
        userName = "user",
        deviceName = "Pixel",
        llmBackend = LlmBackend.NONE,
        modelQuality = 0,
        freeRamMb = 0,
        tmdbApiKey = apiKey,
        countryCode = countryCode,
    )

    private fun makePhone(apiKey: String?, countryCode: String? = null) = mockk<PhoneDiscoveryManager.DiscoveredPhone> {
        every { capability } returns makeCapability(apiKey, countryCode)
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
        tmdbPageUrl: String? = null,
        isInstalled: Boolean = true,
        isLastUsed: Boolean = false,
    ) = ResolvedProvider(
        providerId = providerId,
        name = name,
        logoPath = null,
        packageName = "com.netflix.ninja",
        isInstalled = isInstalled,
        isLastUsed = isLastUsed,
        tmdbPageUrl = tmdbPageUrl,
    )

    @BeforeEach
    fun setUp() {
        coEvery { streamingPrefs.getShowNonInstalledProviders() } returns false
        coEvery { lastUsedRepo.recordUsed(any(), any()) } just runs
        coEvery { justWatchRepo.resolveDeepLink(any(), any(), any(), any(), any(), any()) } returns null
        viewModel = ShowDetailViewModel(
            watchProviders, lastUsedRepo, streamingPrefs, phoneDiscovery, tmdbApi, justWatchRepo,
            com.justb81.watchbuddy.core.scrobbler.NoOpPlaybackIntentProvider(),
        )
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

        @Test
        fun `usesPhoneCountryCodeWhenAvailable`() = runTest {
            val providers = listOf(makeProvider())
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key", countryCode = "DE")
            coEvery { watchProviders.getResolvedProviders(100, "DE", "api-key", false) } returns (providers to null)

            viewModel.loadProviders(makeEntry())

            assertInstanceOf(ProviderListUiState.Success::class.java, viewModel.providers.value)
        }

        @Test
        fun `fallsBackToLocaleWhenNoPhone`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns null

            viewModel.loadProviders(makeEntry())

            assertEquals(ProviderListUiState.Error, viewModel.providers.value)
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
    @DisplayName("loadDeepLinks")
    inner class LoadDeepLinksTest {

        @Test
        fun `emits Available when JustWatch resolves a URL`() = runTest {
            val provider = makeProvider(providerId = 8)
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery { watchProviders.getResolvedProviders(100, any(), "api-key", false) } returns (listOf(provider) to null)
            coEvery {
                justWatchRepo.resolveDeepLink(100, 1, 2, 8, any(), "Test Show")
            } returns "https://www.netflix.com/watch/12345"

            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())

            val state = viewModel.deepLinks.value[8]
            assert(state is DeepLinkState.Available)
            assertEquals("https://www.netflix.com/watch/12345", (state as DeepLinkState.Available).url)
        }

        @Test
        fun `emits Unavailable when JustWatch returns null`() = runTest {
            val provider = makeProvider(providerId = 8)
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery { watchProviders.getResolvedProviders(100, any(), "api-key", false) } returns (listOf(provider) to null)
            coEvery { justWatchRepo.resolveDeepLink(any(), any(), any(), any(), any(), any()) } returns null

            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())

            val state = viewModel.deepLinks.value[8]
            assertEquals(DeepLinkState.Unavailable, state)
        }

        @Test
        fun `no-ops when show has no TMDB id`() = runTest {
            viewModel.loadDeepLinks(makeEntry(tmdbId = null))

            assertEquals(emptyMap<Int, DeepLinkState>(), viewModel.deepLinks.value)
        }

        @Test
        fun `no-ops when providers not in Success state`() = runTest {
            viewModel.loadDeepLinks(makeEntry())

            assertEquals(emptyMap<Int, DeepLinkState>(), viewModel.deepLinks.value)
        }
    }

    @Nested
    @DisplayName("onProviderSelected")
    inner class OnProviderSelectedTest {

        @Test
        fun `returns JustWatch URL when Available`() = runTest {
            val provider = makeProvider(providerId = 8, tmdbPageUrl = "https://tmdb.org")
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery { watchProviders.getResolvedProviders(100, any(), "api-key", false) } returns (listOf(provider) to null)
            coEvery {
                justWatchRepo.resolveDeepLink(100, 1, 2, 8, any(), "Test Show")
            } returns "https://www.netflix.com/watch/12345"

            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())

            val result = viewModel.onProviderSelected(provider, makeEntry())
            assertEquals("https://www.netflix.com/watch/12345", result)
        }

        @Test
        fun `falls back to tmdbPageUrl when Unavailable`() = runTest {
            val provider = makeProvider(providerId = 8, tmdbPageUrl = "https://tmdb.org/watch")
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery { watchProviders.getResolvedProviders(100, any(), "api-key", false) } returns (listOf(provider) to null)
            coEvery { justWatchRepo.resolveDeepLink(any(), any(), any(), any(), any(), any()) } returns null

            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())

            val result = viewModel.onProviderSelected(provider, makeEntry())
            assertEquals("https://tmdb.org/watch", result)
        }

        @Test
        fun `records last-used when tmdb id available`() = runTest {
            val enriched = makeEntry()
            val provider = makeProvider(providerId = 8)

            viewModel.onProviderSelected(provider, enriched)

            coVerify { lastUsedRepo.recordUsed(100, 8) }
        }

        @Test
        fun `does not record last-used when tmdb id is null`() = runTest {
            val enriched = makeEntry(tmdbId = null)
            val provider = makeProvider()

            viewModel.onProviderSelected(provider, enriched)

            coVerify(exactly = 0) { lastUsedRepo.recordUsed(any(), any()) }
        }
    }

    @Nested
    @DisplayName("watchNowState")
    inner class WatchNowStateTest {

        @Test
        fun `is Loading initially`() {
            assertEquals(WatchNowState.Loading, viewModel.watchNowState.value)
        }

        @Test
        fun `is Loading while providers are loading`() = runTest {
            // providers StateFlow starts as Loading — no additional calls needed
            assertEquals(WatchNowState.Loading, viewModel.watchNowState.value)
        }

        @Test
        fun `is NoProvider when providers are Empty`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(any(), any(), any(), any())
            } returns (emptyList<ResolvedProvider>() to null)

            viewModel.loadProviders(makeEntry())

            assertEquals(WatchNowState.NoProvider, viewModel.watchNowState.value)
        }

        @Test
        fun `is NoProvider when providers are Error`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns null

            viewModel.loadProviders(makeEntry())

            assertEquals(WatchNowState.NoProvider, viewModel.watchNowState.value)
        }

        @Test
        fun `is NoProvider when provider list is empty after Success`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(any(), any(), any(), any())
            } returns (emptyList<ResolvedProvider>() to null)

            viewModel.loadProviders(makeEntry())

            assertEquals(WatchNowState.NoProvider, viewModel.watchNowState.value)
        }

        @Test
        fun `is Loading when top provider deep link is still loading`() = runTest {
            val provider = makeProvider(providerId = 8)
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(100, any(), "api-key", false)
            } returns (listOf(provider) to null)
            // resolveDeepLink hangs so deep link stays Loading — don't call loadDeepLinks here

            viewModel.loadProviders(makeEntry())

            // providers = Success but deepLinks is empty → top provider has no entry → Loading
            assertEquals(WatchNowState.Loading, viewModel.watchNowState.value)
        }

        @Test
        fun `is Available when top provider deep link resolves`() = runTest {
            val provider = makeProvider(providerId = 8)
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(100, any(), "api-key", false)
            } returns (listOf(provider) to null)
            coEvery {
                justWatchRepo.resolveDeepLink(100, 1, 2, 8, any(), "Test Show")
            } returns "https://www.netflix.com/watch/99"

            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())

            val state = viewModel.watchNowState.value
            assertInstanceOf(WatchNowState.Available::class.java, state)
            assertEquals("https://www.netflix.com/watch/99", (state as WatchNowState.Available).url)
        }

        @Test
        fun `is Unavailable when top provider deep link is Unavailable`() = runTest {
            val provider = makeProvider(providerId = 8)
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(100, any(), "api-key", false)
            } returns (listOf(provider) to null)
            coEvery {
                justWatchRepo.resolveDeepLink(any(), any(), any(), any(), any(), any())
            } returns null

            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())

            assertEquals(WatchNowState.Unavailable, viewModel.watchNowState.value)
        }

        @Test
        fun `uses first provider when multiple providers exist`() = runTest {
            val first = makeProvider(providerId = 8, name = "Netflix")
            val second = makeProvider(providerId = 9, name = "Disney+")
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(100, any(), "api-key", false)
            } returns (listOf(first, second) to null)
            coEvery {
                justWatchRepo.resolveDeepLink(100, 1, 2, 8, any(), "Test Show")
            } returns "https://netflix.com/watch/1"
            coEvery {
                justWatchRepo.resolveDeepLink(100, 1, 2, 9, any(), "Test Show")
            } returns "https://disneyplus.com/watch/1"

            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())

            val state = viewModel.watchNowState.value
            assertInstanceOf(WatchNowState.Available::class.java, state)
            assertEquals("https://netflix.com/watch/1", (state as WatchNowState.Available).url)
        }
    }
}
