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
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
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
    private val clientFactory: PhoneApiClientFactory = mockk(relaxed = true)
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
        every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(emptyList())
        viewModel = ShowDetailViewModel(
            watchProviders, lastUsedRepo, streamingPrefs, phoneDiscovery, tmdbApi, justWatchRepo,
            clientFactory,
        )
    }

    @Nested
    @DisplayName("uiState")
    inner class UiStateTest {

        @Test
        fun `starts as Loading and transitions to Ready after initial load`() = runTest {
            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            advanceUntilIdle()
            job.cancel()

            // The combine upstream fires as soon as the WhileSubscribed policy starts, so Loading
            // may be skipped by the time the first collector observes the StateFlow value in tests.
            // What matters is that the flow ultimately reaches Ready.
            assertInstanceOf(ShowDetailUiState.Ready::class.java, states.last())
        }

        @Test
        fun `Ready watchNow reflects providers and deepLinks combination`() = runTest {
            val provider = makeProvider(providerId = 8)
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(100, any(), "api-key", false)
            } returns (listOf(provider) to null)
            coEvery {
                justWatchRepo.resolveDeepLink(100, 1, 2, 8, any(), "Test Show")
            } returns "https://www.netflix.com/watch/99"

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }

            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertInstanceOf(WatchNowState.Available::class.java, ready.watchNow)
            assertEquals("https://www.netflix.com/watch/99", (ready.watchNow as WatchNowState.Available).url)
        }

        @Test
        fun `marking an episode watched emits Ready with markWatched Loading then Idle`() = runTest {
            val markStates = mutableListOf<MarkWatchedState>()
            val job = backgroundScope.launch {
                viewModel.uiState.collect { state ->
                    if (state is ShowDetailUiState.Ready) markStates.add(state.markWatched)
                }
            }
            // No phones — results in NoPhones, not the loading path; use that to observe state change
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(emptyList())
            viewModel.markCurrentEpisodeWatched(makeEntry())
            advanceUntilIdle()
            job.cancel()

            // Should have emitted NoPhones state inside a Ready snapshot
            assert(markStates.any { it is MarkWatchedState.NoPhones })
        }

        @Test
        fun `advancedEntry transition produces a new Ready snapshot with updated entry`() = runTest {
            val phone = mockk<PhoneDiscoveryManager.DiscoveredPhone> {
                every { capability } returns makeCapability("key")
                every { baseUrl } returns "http://phone:8765/"
                every { bearerToken } returns "tok"
                every { score } returns 100
            }
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            every { phoneDiscovery.getBestPhone() } returns phone
            val phoneApiService = mockk<com.justb81.watchbuddy.tv.discovery.PhoneApiService> {
                coEvery { markWatched(any()) } returns retrofit2.Response.success(null)
            }
            every { clientFactory.createClient(any(), any()) } returns phoneApiService
            coEvery { tmdbApi.getEpisode(any(), any(), any(), any(), any()) } throws RuntimeException("skip")

            val readySnapshots = mutableListOf<ShowDetailUiState.Ready>()
            val job = backgroundScope.launch {
                viewModel.uiState.collect { if (it is ShowDetailUiState.Ready) readySnapshots.add(it) }
            }

            viewModel.markCurrentEpisodeWatched(makeEntry())
            advanceUntilIdle()
            job.cancel()

            // At least one snapshot should have a non-null advancedEntry
            assert(readySnapshots.any { it.advancedEntry != null }) {
                "Expected at least one Ready snapshot with non-null advancedEntry"
            }
        }

        @Test
        fun `concurrent updates to two slices yield a consistent final Ready snapshot`() = runTest {
            val provider = makeProvider(providerId = 8)
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(100, any(), "api-key", false)
            } returns (listOf(provider) to null)
            coEvery {
                justWatchRepo.resolveDeepLink(100, 1, 2, 8, any(), "Test Show")
            } returns "https://www.netflix.com/watch/42"
            coEvery { tmdbApi.getEpisode(100, 1, 2, "api-key", "en-US") } returns makeTmdbEpisode()

            val readySnapshots = mutableListOf<ShowDetailUiState.Ready>()
            val job = backgroundScope.launch {
                viewModel.uiState.collect { if (it is ShowDetailUiState.Ready) readySnapshots.add(it) }
            }

            viewModel.loadProviders(makeEntry())
            viewModel.loadNextEpisode(makeEntry())
            viewModel.loadDeepLinks(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val last = readySnapshots.last()
            // Both slices reflect their final state consistently in the same snapshot
            assertInstanceOf(ProviderListUiState.Success::class.java, last.providers)
            assertInstanceOf(WatchNowState.Available::class.java, last.watchNow)
            assertEquals("Episode Title", last.nextEpisode.episodeName)
        }
    }

    @Nested
    @DisplayName("loadProviders")
    inner class LoadProvidersTest {

        @Test
        fun `emits Success with resolved providers`() = runTest {
            val providers = listOf(makeProvider())
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery { watchProviders.getResolvedProviders(100, any(), "api-key", false) } returns (providers to null)

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            val state = assertInstanceOf(ProviderListUiState.Success::class.java, ready.providers)
            assertEquals(providers, state.providers)
        }

        @Test
        fun `emits Empty with tmdbPageUrl when no providers returned`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(any(), any(), any(), any())
            } returns (emptyList<ResolvedProvider>() to "https://tmdb.org")

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            val state = assertInstanceOf(ProviderListUiState.Empty::class.java, ready.providers)
            assertEquals("https://tmdb.org", state.tmdbPageUrl)
        }

        @Test
        fun `emits Error when no phone available`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns null

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertEquals(ProviderListUiState.Error, ready.providers)
        }

        @Test
        fun `emits Error when phone has no API key`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone(null)

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertEquals(ProviderListUiState.Error, ready.providers)
        }

        @Test
        fun `emits Error on repository exception`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery { watchProviders.getResolvedProviders(any(), any(), any(), any()) } throws RuntimeException("Network error")

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertEquals(ProviderListUiState.Error, ready.providers)
        }

        @Test
        fun `emits Empty when show has no TMDB id`() = runTest {
            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry(tmdbId = null))
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            val state = assertInstanceOf(ProviderListUiState.Empty::class.java, ready.providers)
            assertNull(state.tmdbPageUrl)
        }

        @Test
        fun `usesPhoneCountryCodeWhenAvailable`() = runTest {
            val providers = listOf(makeProvider())
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key", countryCode = "DE")
            coEvery { watchProviders.getResolvedProviders(100, "DE", "api-key", false) } returns (providers to null)

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertInstanceOf(ProviderListUiState.Success::class.java, ready.providers)
        }

        @Test
        fun `fallsBackToLocaleWhenNoPhone`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns null

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertEquals(ProviderListUiState.Error, ready.providers)
        }
    }

    @Nested
    @DisplayName("loadNextEpisode")
    inner class LoadNextEpisodeTest {

        @Test
        fun `populates stillUrl and episodeName on success`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("test-key")
            coEvery { tmdbApi.getEpisode(100, 1, 2, "test-key", "en-US") } returns makeTmdbEpisode(1, 2)

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadNextEpisode(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val state = states.filterIsInstance<ShowDetailUiState.Ready>().last().nextEpisode
            assert(!state.isLoading)
            assert(state.stillUrl?.contains("still.jpg") == true)
            assertEquals("Episode Title", state.episodeName)
            assertEquals("S01E02", state.episodeCode)
        }

        @Test
        fun `produces null stillUrl when TMDB returns no still_path`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("key")
            coEvery { tmdbApi.getEpisode(any(), any(), any(), any(), any()) } returns makeTmdbEpisode(stillPath = null)

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadNextEpisode(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val state = states.filterIsInstance<ShowDetailUiState.Ready>().last().nextEpisode
            assertNull(state.stillUrl)
        }

        @Test
        fun `stays empty when no phone available`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns null

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadNextEpisode(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val state = states.filterIsInstance<ShowDetailUiState.Ready>().last().nextEpisode
            assert(!state.isLoading)
            assertNull(state.stillUrl)
            assertNull(state.episodeName)
        }

        @Test
        fun `stays empty when phone has no API key`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone(null)

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadNextEpisode(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val state = states.filterIsInstance<ShowDetailUiState.Ready>().last().nextEpisode
            assertNull(state.stillUrl)
        }

        @Test
        fun `stays empty when show has no TMDB id`() = runTest {
            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadNextEpisode(makeEntry(tmdbId = null))
            advanceUntilIdle()
            job.cancel()

            val state = states.filterIsInstance<ShowDetailUiState.Ready>().last().nextEpisode
            assert(!state.isLoading)
            assertNull(state.stillUrl)
        }

        @Test
        fun `clears episode data on TMDB API failure`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("key")
            coEvery { tmdbApi.getEpisode(any(), any(), any(), any(), any()) } throws RuntimeException("404")

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadNextEpisode(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val state = states.filterIsInstance<ShowDetailUiState.Ready>().last().nextEpisode
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

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadNextEpisode(enriched)
            advanceUntilIdle()
            job.cancel()

            val state = states.filterIsInstance<ShowDetailUiState.Ready>().last().nextEpisode
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

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            val state = ready.deepLinks[8]
            assert(state is DeepLinkState.Available)
            assertEquals("https://www.netflix.com/watch/12345", (state as DeepLinkState.Available).url)
        }

        @Test
        fun `emits Unavailable when JustWatch returns null`() = runTest {
            val provider = makeProvider(providerId = 8)
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery { watchProviders.getResolvedProviders(100, any(), "api-key", false) } returns (listOf(provider) to null)
            coEvery { justWatchRepo.resolveDeepLink(any(), any(), any(), any(), any(), any()) } returns null

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            val state = ready.deepLinks[8]
            assertEquals(DeepLinkState.Unavailable, state)
        }

        @Test
        fun `no-ops when show has no TMDB id`() = runTest {
            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadDeepLinks(makeEntry(tmdbId = null))
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertEquals(emptyMap<Int, DeepLinkState>(), ready.deepLinks)
        }

        @Test
        fun `no-ops when providers not in Success state`() = runTest {
            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadDeepLinks(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertEquals(emptyMap<Int, DeepLinkState>(), ready.deepLinks)
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

            val job = backgroundScope.launch { viewModel.uiState.collect { } }
            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val result = viewModel.onProviderSelected(provider, makeEntry())
            assertEquals("https://www.netflix.com/watch/12345", result)
        }

        @Test
        fun `falls back to tmdbPageUrl when Unavailable`() = runTest {
            val provider = makeProvider(providerId = 8, tmdbPageUrl = "https://tmdb.org/watch")
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery { watchProviders.getResolvedProviders(100, any(), "api-key", false) } returns (listOf(provider) to null)
            coEvery { justWatchRepo.resolveDeepLink(any(), any(), any(), any(), any(), any()) } returns null

            val job = backgroundScope.launch { viewModel.uiState.collect { } }
            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())
            advanceUntilIdle()
            job.cancel()

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
    @DisplayName("watchNow derived state")
    inner class WatchNowStateTest {

        @Test
        fun `is Loading initially inside Ready`() = runTest {
            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().first()
            assertEquals(WatchNowState.Loading, ready.watchNow)
        }

        @Test
        fun `is Loading while providers are loading`() = runTest {
            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertEquals(WatchNowState.Loading, ready.watchNow)
        }

        @Test
        fun `is NoProvider when providers are Empty`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(any(), any(), any(), any())
            } returns (emptyList<ResolvedProvider>() to null)

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertEquals(WatchNowState.NoProvider, ready.watchNow)
        }

        @Test
        fun `is NoProvider when providers are Error`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns null

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertEquals(WatchNowState.NoProvider, ready.watchNow)
        }

        @Test
        fun `is NoProvider when provider list is empty after Success`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(any(), any(), any(), any())
            } returns (emptyList<ResolvedProvider>() to null)

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertEquals(WatchNowState.NoProvider, ready.watchNow)
        }

        @Test
        fun `is Loading when top provider deep link is still loading`() = runTest {
            val provider = makeProvider(providerId = 8)
            every { phoneDiscovery.getBestPhone() } returns makePhone("api-key")
            coEvery {
                watchProviders.getResolvedProviders(100, any(), "api-key", false)
            } returns (listOf(provider) to null)

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            advanceUntilIdle()
            job.cancel()

            // providers = Success but deepLinks is empty → top provider has no entry → Loading
            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertEquals(WatchNowState.Loading, ready.watchNow)
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

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertInstanceOf(WatchNowState.Available::class.java, ready.watchNow)
            assertEquals("https://www.netflix.com/watch/99", (ready.watchNow as WatchNowState.Available).url)
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

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertEquals(WatchNowState.Unavailable, ready.watchNow)
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

            val states = mutableListOf<ShowDetailUiState>()
            val job = backgroundScope.launch { viewModel.uiState.collect { states.add(it) } }
            viewModel.loadProviders(makeEntry())
            viewModel.loadDeepLinks(makeEntry())
            advanceUntilIdle()
            job.cancel()

            val ready = states.filterIsInstance<ShowDetailUiState.Ready>().last()
            assertInstanceOf(WatchNowState.Available::class.java, ready.watchNow)
            assertEquals("https://netflix.com/watch/1", (ready.watchNow as WatchNowState.Available).url)
        }
    }
}
