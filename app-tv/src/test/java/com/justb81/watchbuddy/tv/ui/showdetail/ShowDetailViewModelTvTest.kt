package com.justb81.watchbuddy.tv.ui.showdetail

import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
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
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.discovery.WatchedToggleRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ShowDetailViewModel — episode list and watched toggle (#217)")
class ShowDetailViewModelTvTest {

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
    private val clientFactory: PhoneApiClientFactory = mockk()
    private val phoneApiService: PhoneApiService = mockk()
    private lateinit var viewModel: ShowDetailViewModel

    private fun makeCapability(userName: String = "Alice") = DeviceCapability(
        deviceId = "dev1",
        userName = userName,
        deviceName = "Pixel",
        llmBackend = LlmBackend.NONE,
        modelQuality = 50,
        freeRamMb = 2048,
    )

    private fun makePhone(
        baseUrl: String = "http://192.168.1.10:8765/",
        bearerToken: String? = "tok",
        userName: String = "Alice",
    ): PhoneDiscoveryManager.DiscoveredPhone = mockk {
        every { this@mockk.baseUrl } returns baseUrl
        every { this@mockk.bearerToken } returns bearerToken
        every { this@mockk.capability } returns makeCapability(userName)
        every { this@mockk.score } returns 100
    }

    private fun makeEntry(
        traktId: Int = 1,
        watchedSeasons: List<TraktWatchedSeason> = listOf(
            TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1), TraktWatchedEpisode(2)))
        ),
    ) = EnrichedShowEntry(
        entry = TraktWatchedEntry(
            show = TraktShow("Test Show", 2023, TraktIds(trakt = traktId, tmdb = 100)),
            seasons = watchedSeasons,
        )
    )

    private val seasonFixture = listOf(
        TraktSeasonWithEpisodes(
            number = 1,
            episodes = listOf(
                TraktEpisode(season = 1, number = 1, title = "Pilot"),
                TraktEpisode(season = 1, number = 2, title = "Ep 2"),
                TraktEpisode(season = 1, number = 3, title = "Ep 3"),
            )
        )
    )

    @BeforeEach
    fun setUp() {
        coEvery { streamingPrefs.getShowNonInstalledProviders() } returns false
        coEvery { lastUsedRepo.recordUsed(any(), any()) } just runs
        coEvery { justWatchRepo.resolveDeepLink(any(), any(), any(), any(), any(), any()) } returns null
        every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(emptyList())
        viewModel = ShowDetailViewModel(
            watchProviders, lastUsedRepo, streamingPrefs, phoneDiscovery, tmdbApi, justWatchRepo,
            com.justb81.watchbuddy.core.scrobbler.NoOpPlaybackIntentProvider(),
            clientFactory,
        )
    }

    // ── loadEpisodeList ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("loadEpisodeList")
    inner class LoadEpisodeListTest {

        @Test
        fun `emits Success with seasons and empty watchedSet when no episodes watched`() = runTest {
            val phone = makePhone()
            every { phoneDiscovery.getBestPhone() } returns phone
            every { clientFactory.createClient(any(), any()) } returns phoneApiService
            coEvery { phoneApiService.getSeasons("1") } returns seasonFixture

            viewModel.loadEpisodeList(makeEntry(watchedSeasons = emptyList()))

            val state = assertInstanceOf(EpisodeListUiState.Success::class.java, viewModel.episodeList.value)
            assertEquals(seasonFixture, state.seasons)
            assertTrue(state.watchedSet.isEmpty())
        }

        @Test
        fun `builds watchedSet from entry history`() = runTest {
            val phone = makePhone()
            every { phoneDiscovery.getBestPhone() } returns phone
            every { clientFactory.createClient(any(), any()) } returns phoneApiService
            coEvery { phoneApiService.getSeasons("1") } returns seasonFixture

            viewModel.loadEpisodeList(makeEntry(watchedSeasons = listOf(
                TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1), TraktWatchedEpisode(2)))
            )))

            val state = assertInstanceOf(EpisodeListUiState.Success::class.java, viewModel.episodeList.value)
            assertTrue(state.watchedSet.contains(1 to 1))
            assertTrue(state.watchedSet.contains(1 to 2))
            assertFalse(state.watchedSet.contains(1 to 3))
        }

        @Test
        fun `emits Error when no phone available`() = runTest {
            every { phoneDiscovery.getBestPhone() } returns null

            viewModel.loadEpisodeList(makeEntry())

            assertInstanceOf(EpisodeListUiState.Error::class.java, viewModel.episodeList.value)
        }

        @Test
        fun `emits Error on API failure`() = runTest {
            val phone = makePhone()
            every { phoneDiscovery.getBestPhone() } returns phone
            every { clientFactory.createClient(any(), any()) } returns phoneApiService
            coEvery { phoneApiService.getSeasons(any()) } throws RuntimeException("Network error")

            viewModel.loadEpisodeList(makeEntry())

            assertInstanceOf(EpisodeListUiState.Error::class.java, viewModel.episodeList.value)
        }
    }

    // ── toggleEpisodeWatched — success paths ──────────────────────────────────────────────

    @Nested
    @DisplayName("toggleEpisodeWatched — success")
    inner class ToggleWatchedSuccessTest {

        private fun setupWithPhones(phones: List<PhoneDiscoveryManager.DiscoveredPhone>) {
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(phones)
            every { phoneDiscovery.getBestPhone() } returns phones.firstOrNull()
            phones.forEach { phone ->
                every { clientFactory.createClient(phone.baseUrl, phone.bearerToken) } returns phoneApiService
            }
        }

        @Test
        fun `marks episode watched — updates watchedSet`() = runTest {
            val phone = makePhone()
            setupWithPhones(listOf(phone))
            coEvery { phoneApiService.getSeasons("1") } returns seasonFixture
            coEvery { phoneApiService.markWatched(any()) } returns Response.success(null)
            // Pre-load so there's a Success state
            viewModel.loadEpisodeList(makeEntry(watchedSeasons = emptyList()))

            viewModel.toggleEpisodeWatched(
                showIds = TraktIds(trakt = 1),
                season = 1,
                episode = 3,
                markAsWatched = true,
                selectedUserIds = setOf(phone.baseUrl),
            )

            val state = viewModel.episodeList.value as EpisodeListUiState.Success
            assertTrue(state.watchedSet.contains(1 to 3))
        }

        @Test
        fun `unmarks episode — removes from watchedSet`() = runTest {
            val phone = makePhone()
            setupWithPhones(listOf(phone))
            coEvery { phoneApiService.getSeasons("1") } returns seasonFixture
            coEvery { phoneApiService.markUnwatched(any()) } returns Response.success(null)
            viewModel.loadEpisodeList(makeEntry(watchedSeasons = listOf(
                TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1)))
            )))

            viewModel.toggleEpisodeWatched(
                showIds = TraktIds(trakt = 1),
                season = 1,
                episode = 1,
                markAsWatched = false,
                selectedUserIds = setOf(phone.baseUrl),
            )

            val state = viewModel.episodeList.value as EpisodeListUiState.Success
            assertFalse(state.watchedSet.contains(1 to 1))
        }

        @Test
        fun `fans out to each selected phone`() = runTest {
            val phone1 = makePhone("http://p1:8765/", "tok1", "Alice")
            val phone2 = makePhone("http://p2:8765/", "tok2", "Bob")
            val api2: PhoneApiService = mockk()
            setupWithPhones(listOf(phone1, phone2))
            every { clientFactory.createClient("http://p2:8765/", "tok2") } returns api2
            coEvery { phoneApiService.getSeasons("1") } returns seasonFixture
            coEvery { phoneApiService.markWatched(any()) } returns Response.success(null)
            coEvery { api2.markWatched(any()) } returns Response.success(null)
            viewModel.loadEpisodeList(makeEntry(watchedSeasons = emptyList()))

            viewModel.toggleEpisodeWatched(
                showIds = TraktIds(trakt = 1),
                season = 1,
                episode = 2,
                markAsWatched = true,
                selectedUserIds = setOf(phone1.baseUrl, phone2.baseUrl),
            )

            io.mockk.coVerify(exactly = 1) { phoneApiService.markWatched(any()) }
            io.mockk.coVerify(exactly = 1) { api2.markWatched(any()) }
        }

        @Test
        fun `only fans out to selected subset`() = runTest {
            val phone1 = makePhone("http://p1:8765/", "tok1", "Alice")
            val phone2 = makePhone("http://p2:8765/", "tok2", "Bob")
            setupWithPhones(listOf(phone1, phone2))
            coEvery { phoneApiService.getSeasons("1") } returns seasonFixture
            coEvery { phoneApiService.markWatched(any()) } returns Response.success(null)
            viewModel.loadEpisodeList(makeEntry(watchedSeasons = emptyList()))

            // Only select phone1
            viewModel.toggleEpisodeWatched(
                showIds = TraktIds(trakt = 1),
                season = 1,
                episode = 1,
                markAsWatched = true,
                selectedUserIds = setOf(phone1.baseUrl),
            )

            io.mockk.coVerify(exactly = 1) { phoneApiService.markWatched(any()) }
            // phone2's client was never created for this call
            io.mockk.verify(exactly = 0) { clientFactory.createClient("http://p2:8765/", "tok2") }
        }
    }

    // ── toggleEpisodeWatched — failure paths ──────────────────────────────────────────────

    @Nested
    @DisplayName("toggleEpisodeWatched — failures")
    inner class ToggleWatchedFailureTest {

        @Test
        fun `emits AllFailed and reverts when no selected phones match`() = runTest {
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(emptyList())
            every { phoneDiscovery.getBestPhone() } returns null

            val events = mutableListOf<EpisodeToggleEvent>()
            val job = backgroundScope.launch {
                viewModel.episodeToggleEvents.collect { events.add(it) }
            }

            viewModel.toggleEpisodeWatched(
                showIds = TraktIds(trakt = 1),
                season = 1,
                episode = 1,
                markAsWatched = true,
                selectedUserIds = setOf("http://notfound:8765/"),
            )

            job.cancel()
            assertTrue(events.any { it is EpisodeToggleEvent.AllFailed })
        }

        @Test
        fun `emits PartialFailed when one phone succeeds and one fails`() = runTest {
            val phone1 = makePhone("http://p1:8765/", "tok1", "Alice")
            val phone2 = makePhone("http://p2:8765/", "tok2", "Bob")
            val api2: PhoneApiService = mockk()
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone1, phone2))
            every { phoneDiscovery.getBestPhone() } returns phone1
            every { clientFactory.createClient("http://p1:8765/", "tok1") } returns phoneApiService
            every { clientFactory.createClient("http://p2:8765/", "tok2") } returns api2
            coEvery { phoneApiService.getSeasons("1") } returns seasonFixture
            coEvery { phoneApiService.markWatched(any()) } returns Response.success(null)
            coEvery { api2.markWatched(any()) } throws RuntimeException("timeout")
            viewModel.loadEpisodeList(makeEntry(watchedSeasons = emptyList()))

            val events = mutableListOf<EpisodeToggleEvent>()
            val job = backgroundScope.launch {
                viewModel.episodeToggleEvents.collect { events.add(it) }
            }

            viewModel.toggleEpisodeWatched(
                showIds = TraktIds(trakt = 1),
                season = 1,
                episode = 1,
                markAsWatched = true,
                selectedUserIds = setOf(phone1.baseUrl, phone2.baseUrl),
            )

            job.cancel()
            val partialFailed = events.filterIsInstance<EpisodeToggleEvent.PartialFailed>()
            assertTrue(partialFailed.isNotEmpty())
            assertTrue(partialFailed.first().failedUserNames.contains("Bob"))
            // Optimistic state kept (episode is still in watchedSet)
            val state = viewModel.episodeList.value as? EpisodeListUiState.Success
            if (state != null) assertTrue(state.watchedSet.contains(1 to 1))
        }

        @Test
        fun `reverts watchedSet on all-failure`() = runTest {
            val phone = makePhone()
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone))
            every { phoneDiscovery.getBestPhone() } returns phone
            every { clientFactory.createClient(any(), any()) } returns phoneApiService
            coEvery { phoneApiService.getSeasons("1") } returns seasonFixture
            coEvery { phoneApiService.markWatched(any()) } returns Response.error(500, "".toResponseBody())
            viewModel.loadEpisodeList(makeEntry(watchedSeasons = emptyList()))

            val events = mutableListOf<EpisodeToggleEvent>()
            val job = backgroundScope.launch {
                viewModel.episodeToggleEvents.collect { events.add(it) }
            }

            viewModel.toggleEpisodeWatched(
                showIds = TraktIds(trakt = 1),
                season = 1,
                episode = 3,
                markAsWatched = true,
                selectedUserIds = setOf(phone.baseUrl),
            )

            job.cancel()
            assertTrue(events.any { it is EpisodeToggleEvent.AllFailed })
            val state = viewModel.episodeList.value as? EpisodeListUiState.Success
            if (state != null) assertFalse(state.watchedSet.contains(1 to 3))
        }
    }

    // ── skipScopePickerThisSession ────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("skipScopePickerThisSession")
    inner class SkipScopePickerTest {

        @Test
        fun `starts as false`() {
            assertFalse(viewModel.skipScopePickerThisSession)
        }

        @Test
        fun `becomes true after onDontAskAgainSet`() {
            viewModel.onDontAskAgainSet()
            assertTrue(viewModel.skipScopePickerThisSession)
        }
    }

    // ── connectedUsers ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("connectedUsers")
    inner class ConnectedUsersTest {

        @Test
        fun `returns ConnectedUser list from discoveredPhones`() {
            val phone1 = makePhone("http://p1:8765/", "tok1", "Alice")
            val phone2 = makePhone("http://p2:8765/", "tok2", "Bob")
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(listOf(phone1, phone2))
            // Re-create viewModel so it picks up the new discoveredPhones stub
            viewModel = ShowDetailViewModel(
                watchProviders, lastUsedRepo, streamingPrefs, phoneDiscovery, tmdbApi, justWatchRepo,
                com.justb81.watchbuddy.core.scrobbler.NoOpPlaybackIntentProvider(),
                clientFactory,
            )

            val users = viewModel.connectedUsers()

            assertEquals(2, users.size)
            assertEquals("Alice", users.find { it.id == "http://p1:8765/" }?.displayName)
            assertEquals("Bob", users.find { it.id == "http://p2:8765/" }?.displayName)
        }

        @Test
        fun `returns empty list when no phones`() {
            every { phoneDiscovery.discoveredPhones } returns MutableStateFlow(emptyList())
            val users = viewModel.connectedUsers()
            assertTrue(users.isEmpty())
        }
    }
}
