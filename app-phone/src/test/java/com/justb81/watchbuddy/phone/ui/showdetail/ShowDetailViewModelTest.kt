package com.justb81.watchbuddy.phone.ui.showdetail

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.MainDispatcherRule
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
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

        private const val TRAKT_SHOW_ID = 42
        private const val TMDB_SHOW_ID = 100
    }

    private val application: Application = mockk(relaxed = true)
    private val traktApi: TraktApiService = mockk(relaxed = true)
    private val tokenRepository: TokenRepository = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)

    private val savedStateHandle = SavedStateHandle(mapOf("traktShowId" to TRAKT_SHOW_ID))

    private fun makeShow(
        traktId: Int = TRAKT_SHOW_ID,
        tmdbId: Int? = TMDB_SHOW_ID,
        title: String = "Test Show"
    ) = TraktShow(
        title = title,
        year = 2020,
        ids = TraktIds(trakt = traktId, tmdb = tmdbId)
    )

    private fun makeEntry(
        show: TraktShow = makeShow(),
        seasons: List<TraktWatchedSeason> = emptyList()
    ) = TraktWatchedEntry(show = show, seasons = seasons)

    @BeforeEach
    fun setUp() {
        every { tokenRepository.getAccessToken() } returns "valid-token"
        every { settingsRepository.getTmdbApiKey() } returns flowOf("tmdb-key")
        coEvery { traktApi.getWatchedShows(any()) } returns listOf(makeEntry())
        coEvery { tmdbApiService.getShow(any(), any()) } returns
            com.justb81.watchbuddy.core.model.TmdbShow(
                id = TMDB_SHOW_ID,
                name = "Test Show",
                overview = "A great show.",
                poster_path = "/poster.jpg"
            )
    }

    private fun createViewModel(): ShowDetailViewModel = ShowDetailViewModel(
        savedStateHandle = savedStateHandle,
        application = application,
        traktApi = traktApi,
        tokenRepository = tokenRepository,
        tmdbApiService = tmdbApiService,
        settingsRepository = settingsRepository
    )

    @Nested
    @DisplayName("loadShowDetail")
    inner class LoadShowDetailTest {

        @Test
        fun `sets loading false and populates show on success`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertEquals("Test Show", vm.uiState.value.show?.title)
            assertNull(vm.uiState.value.error)
        }

        @Test
        fun `sets error when no access token`() = runTest {
            every { tokenRepository.getAccessToken() } returns null

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertNotNull(vm.uiState.value.error)
            assertNull(vm.uiState.value.show)
        }

        @Test
        fun `sets error when show not found in watched list`() = runTest {
            coEvery { traktApi.getWatchedShows(any()) } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertNotNull(vm.uiState.value.error)
        }

        @Test
        fun `sets error when API throws`() = runTest {
            coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("Network error")

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertNotNull(vm.uiState.value.error)
        }

        @Test
        fun `loads watched seasons sorted by number`() = runTest {
            val entry = makeEntry(
                seasons = listOf(
                    TraktWatchedSeason(number = 3, episodes = listOf(TraktWatchedEpisode(number = 1))),
                    TraktWatchedSeason(number = 1, episodes = listOf(TraktWatchedEpisode(number = 2))),
                    TraktWatchedSeason(number = 2, episodes = listOf(TraktWatchedEpisode(number = 3)))
                )
            )
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(entry)

            val vm = createViewModel()
            advanceUntilIdle()

            val seasons = vm.uiState.value.watchedSeasons
            assertEquals(listOf(1, 2, 3), seasons.map { it.number })
        }

        @Test
        fun `loads TMDB poster URL when tmdbId and key are available`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.posterUrl)
            assertTrue(vm.uiState.value.posterUrl!!.contains("poster.jpg"))
        }

        @Test
        fun `does not load TMDB poster when tmdb key is blank`() = runTest {
            every { settingsRepository.getTmdbApiKey() } returns flowOf("")

            val vm = createViewModel()
            advanceUntilIdle()

            assertNull(vm.uiState.value.posterUrl)
        }

        @Test
        fun `does not load TMDB poster when show has no tmdb id`() = runTest {
            coEvery { traktApi.getWatchedShows(any()) } returns
                listOf(makeEntry(show = makeShow(tmdbId = null)))

            val vm = createViewModel()
            advanceUntilIdle()

            coVerify(exactly = 0) { tmdbApiService.getShow(any(), any()) }
            assertNull(vm.uiState.value.posterUrl)
        }

        @Test
        fun `TMDB failure does not set error state`() = runTest {
            coEvery { tmdbApiService.getShow(any(), any()) } throws RuntimeException("TMDB down")

            val vm = createViewModel()
            advanceUntilIdle()

            assertNull(vm.uiState.value.error)
            assertNotNull(vm.uiState.value.show)
        }

        @Test
        fun `loads TMDB overview`() = runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            assertEquals("A great show.", vm.uiState.value.overview)
        }

        @Test
        fun `reload clears previous error`() = runTest {
            coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("fail")

            val vm = createViewModel()
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.error)

            coEvery { traktApi.getWatchedShows(any()) } returns listOf(makeEntry())
            vm.loadShowDetail()
            advanceUntilIdle()

            assertNull(vm.uiState.value.error)
            assertNotNull(vm.uiState.value.show)
        }
    }

    @Nested
    @DisplayName("toggleEpisodeWatched")
    inner class ToggleEpisodeTest {

        @Test
        fun `removes episode from watched seasons when marking unwatched`() = runTest {
            val entry = makeEntry(
                seasons = listOf(
                    TraktWatchedSeason(
                        number = 1,
                        episodes = listOf(
                            TraktWatchedEpisode(number = 1),
                            TraktWatchedEpisode(number = 2)
                        )
                    )
                )
            )
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(entry)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleEpisodeWatched(season = 1, episode = 1, currentlyWatched = true)
            advanceUntilIdle()

            val season1 = vm.uiState.value.watchedSeasons.find { it.number == 1 }
            assertNotNull(season1)
            assertEquals(1, season1!!.episodes.size)
            assertEquals(2, season1.episodes[0].number)
        }

        @Test
        fun `removes season when last episode is unmarked`() = runTest {
            val entry = makeEntry(
                seasons = listOf(
                    TraktWatchedSeason(number = 1, episodes = listOf(TraktWatchedEpisode(number = 1)))
                )
            )
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(entry)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleEpisodeWatched(season = 1, episode = 1, currentlyWatched = true)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.watchedSeasons.isEmpty())
        }

        @Test
        fun `adds episode to existing season when marking watched`() = runTest {
            val entry = makeEntry(
                seasons = listOf(
                    TraktWatchedSeason(number = 1, episodes = listOf(TraktWatchedEpisode(number = 1)))
                )
            )
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(entry)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleEpisodeWatched(season = 1, episode = 3, currentlyWatched = false)
            advanceUntilIdle()

            val season1 = vm.uiState.value.watchedSeasons.find { it.number == 1 }
            assertNotNull(season1)
            assertEquals(2, season1!!.episodes.size)
            assertTrue(season1.episodes.any { it.number == 3 })
        }

        @Test
        fun `adds new season when marking episode watched in new season`() = runTest {
            val entry = makeEntry(
                seasons = listOf(
                    TraktWatchedSeason(number = 1, episodes = listOf(TraktWatchedEpisode(number = 1)))
                )
            )
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(entry)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleEpisodeWatched(season = 2, episode = 1, currentlyWatched = false)
            advanceUntilIdle()

            assertEquals(2, vm.uiState.value.watchedSeasons.size)
            assertNotNull(vm.uiState.value.watchedSeasons.find { it.number == 2 })
        }

        @Test
        fun `calls removeFromHistory when unmarking episode`() = runTest {
            val entry = makeEntry(
                seasons = listOf(
                    TraktWatchedSeason(number = 1, episodes = listOf(TraktWatchedEpisode(number = 1)))
                )
            )
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(entry)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleEpisodeWatched(season = 1, episode = 1, currentlyWatched = true)
            advanceUntilIdle()

            coVerify(exactly = 1) { traktApi.removeFromHistory(any(), any()) }
            coVerify(exactly = 0) { traktApi.addToHistory(any(), any()) }
        }

        @Test
        fun `calls addToHistory when marking episode watched`() = runTest {
            val entry = makeEntry(
                seasons = listOf(
                    TraktWatchedSeason(number = 1, episodes = listOf(TraktWatchedEpisode(number = 1)))
                )
            )
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(entry)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleEpisodeWatched(season = 1, episode = 2, currentlyWatched = false)
            advanceUntilIdle()

            coVerify(exactly = 1) { traktApi.addToHistory(any(), any()) }
            coVerify(exactly = 0) { traktApi.removeFromHistory(any(), any()) }
        }

        @Test
        fun `sets toggleError when API call fails`() = runTest {
            val entry = makeEntry(
                seasons = listOf(
                    TraktWatchedSeason(number = 1, episodes = listOf(TraktWatchedEpisode(number = 1)))
                )
            )
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(entry)
            coEvery { traktApi.removeFromHistory(any(), any()) } throws RuntimeException("Network error")

            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleEpisodeWatched(season = 1, episode = 1, currentlyWatched = true)
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.toggleError)
            assertNull(vm.uiState.value.togglingEpisode)
        }

        @Test
        fun `clearToggleError removes the error message`() = runTest {
            val entry = makeEntry(
                seasons = listOf(
                    TraktWatchedSeason(number = 1, episodes = listOf(TraktWatchedEpisode(number = 1)))
                )
            )
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(entry)
            coEvery { traktApi.removeFromHistory(any(), any()) } throws RuntimeException("fail")

            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleEpisodeWatched(season = 1, episode = 1, currentlyWatched = true)
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.toggleError)

            vm.clearToggleError()

            assertNull(vm.uiState.value.toggleError)
        }

        @Test
        fun `togglingEpisode is null after successful toggle`() = runTest {
            val entry = makeEntry(
                seasons = listOf(
                    TraktWatchedSeason(number = 1, episodes = listOf(TraktWatchedEpisode(number = 1)))
                )
            )
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(entry)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.toggleEpisodeWatched(season = 1, episode = 1, currentlyWatched = true)
            advanceUntilIdle()

            assertNull(vm.uiState.value.togglingEpisode)
        }
    }
}
