package com.justb81.watchbuddy.phone.ui.showdetail

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.phone.MainDispatcherRule
import com.justb81.watchbuddy.phone.server.EpisodeRepository
import com.justb81.watchbuddy.phone.server.ShowRepository
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val showRepository: ShowRepository = mockk(relaxed = true)
    private val episodeRepository: EpisodeRepository = mockk()
    private val tmdbApiService: TmdbApiService = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)

    private val savedStateHandle = SavedStateHandle(mapOf("traktShowId" to TRAKT_SHOW_ID))

    private val showsFlow = MutableStateFlow<List<EnrichedShowEntry>>(emptyList())

    private val show = TraktShow(
        title = "Test Show",
        year = 2020,
        ids = TraktIds(trakt = TRAKT_SHOW_ID, tmdb = TMDB_SHOW_ID, imdb = "tt1234567")
    )

    @BeforeEach
    fun setUp() {
        every { showRepository.shows } returns showsFlow
        every { showRepository.updateLocalWatched(any(), any(), any(), any()) } just Runs
        every { settingsRepository.getTmdbApiKey() } returns flowOf("")
    }

    private fun seedLibrary(
        watchedSeasons: List<TraktWatchedSeason> = emptyList()
    ) {
        showsFlow.value = listOf(
            EnrichedShowEntry(
                entry = TraktWatchedEntry(show = show, seasons = watchedSeasons)
            )
        )
    }

    private fun seasonsPayload(
        seasonToEpisodeCount: Map<Int, Int>
    ): List<TraktSeasonWithEpisodes> =
        seasonToEpisodeCount.map { (s, n) ->
            TraktSeasonWithEpisodes(
                number = s,
                episodes = (1..n).map { e ->
                    TraktEpisode(season = s, number = e, title = "S${s}E${e}")
                }
            )
        }

    private fun createViewModel(): ShowDetailViewModel = ShowDetailViewModel(
        savedStateHandle = savedStateHandle,
        application = application,
        showRepository = showRepository,
        episodeRepository = episodeRepository,
        tmdbApiService = tmdbApiService,
        settingsRepository = settingsRepository
    )

    @Test
    fun `uiState exposes ids imdb and tmdb from show repository`() = runTest {
        seedLibrary()
        coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
            mapOf(1 to 1)
        )

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("tt1234567", vm.uiState.value.show?.ids?.imdb)
        assertEquals(TMDB_SHOW_ID, vm.uiState.value.show?.ids?.tmdb)
    }

    @Nested
    @DisplayName("default expansion")
    inner class DefaultExpansionTest {

        @Test
        fun `fresh show expands first non-special season`() = runTest {
            seedLibrary(watchedSeasons = emptyList())
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(0 to 1, 1 to 3, 2 to 3)
            )

            val vm = createViewModel()
            advanceUntilIdle()

            val seasons = vm.uiState.value.seasons
            assertEquals(1, seasons.first().number)
            assertTrue(seasons.first().expanded)
            assertTrue(seasons.drop(1).none { it.expanded })
        }

        @Test
        fun `partially watched show expands current season`() = runTest {
            // S1 fully watched (2/2), S2 halfway (1/3), S3 unwatched.
            seedLibrary(
                watchedSeasons = listOf(
                    TraktWatchedSeason(1, listOf(
                        TraktWatchedEpisode(1), TraktWatchedEpisode(2)
                    )),
                    TraktWatchedSeason(2, listOf(TraktWatchedEpisode(1)))
                )
            )
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 2, 2 to 3, 3 to 4)
            )

            val vm = createViewModel()
            advanceUntilIdle()

            val seasons = vm.uiState.value.seasons
            assertEquals(2, seasons.first().number, "current watched season should be first")
            assertTrue(seasons.first().expanded)
            // Remaining seasons appear in ascending order with 2 pulled out.
            assertEquals(listOf(2, 1, 3), seasons.map { it.number })
        }

        @Test
        fun `fully caught up show expands latest watched season`() = runTest {
            seedLibrary(
                watchedSeasons = listOf(
                    TraktWatchedSeason(1, listOf(
                        TraktWatchedEpisode(1), TraktWatchedEpisode(2)
                    )),
                    TraktWatchedSeason(2, listOf(
                        TraktWatchedEpisode(1), TraktWatchedEpisode(2), TraktWatchedEpisode(3)
                    ))
                )
            )
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 2, 2 to 3)
            )

            val vm = createViewModel()
            advanceUntilIdle()

            val seasons = vm.uiState.value.seasons
            assertEquals(2, seasons.first().number)
            assertTrue(seasons.first().expanded)
        }

        @Test
        fun `season after latest watched is preferred when current has no unwatched`() = runTest {
            // S1 fully watched (2/2), S2 fully watched (3/3), S3 has unwatched episodes.
            seedLibrary(
                watchedSeasons = listOf(
                    TraktWatchedSeason(1, listOf(
                        TraktWatchedEpisode(1), TraktWatchedEpisode(2)
                    )),
                    TraktWatchedSeason(2, listOf(
                        TraktWatchedEpisode(1), TraktWatchedEpisode(2), TraktWatchedEpisode(3)
                    ))
                )
            )
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 2, 2 to 3, 3 to 4)
            )

            val vm = createViewModel()
            advanceUntilIdle()

            val seasons = vm.uiState.value.seasons
            assertEquals(3, seasons.first().number)
            assertTrue(seasons.first().expanded)
        }
    }

    @Nested
    @DisplayName("episode ordering")
    inner class EpisodeOrderingTest {

        @Test
        fun `episodes within a season sort DESC by number`() = runTest {
            seedLibrary()
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 5)
            )

            val vm = createViewModel()
            advanceUntilIdle()

            val episodes = vm.uiState.value.seasons.first { it.number == 1 }.episodes
            assertEquals(listOf(5, 4, 3, 2, 1), episodes.map { it.number })
        }

        @Test
        fun `DESC episode order preserved after pinned current season is chosen`() = runTest {
            // S1 fully watched, S2 mid-watched. Current-season rule pins S2 on top and
            // keeps S1 below; both seasons' episode lists must still be DESC by number.
            seedLibrary(
                watchedSeasons = listOf(
                    TraktWatchedSeason(1, listOf(
                        TraktWatchedEpisode(1), TraktWatchedEpisode(2), TraktWatchedEpisode(3)
                    )),
                    TraktWatchedSeason(2, listOf(TraktWatchedEpisode(1)))
                )
            )
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 3, 2 to 4)
            )

            val vm = createViewModel()
            advanceUntilIdle()

            val seasons = vm.uiState.value.seasons
            assertEquals(2, seasons.first().number, "current-season rule should pin S2 on top")
            assertEquals(listOf(4, 3, 2, 1), seasons.first { it.number == 2 }.episodes.map { it.number })
            assertEquals(listOf(3, 2, 1), seasons.first { it.number == 1 }.episodes.map { it.number })
        }
    }

    @Nested
    @DisplayName("toggleEpisodeWatched")
    inner class ToggleTest {

        @Test
        fun `optimistic flip + updateLocalWatched on success`() = runTest {
            seedLibrary()
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 2)
            )
            coEvery { episodeRepository.markEpisodeWatched(any(), 1, 1) } returns Result.success(Unit)

            val vm = createViewModel()
            advanceUntilIdle()

            val target = vm.uiState.value.seasons.first().episodes.first { it.number == 1 }
            vm.toggleEpisodeWatched(target)
            advanceUntilIdle()

            val updated = vm.uiState.value.seasons.first().episodes.first { it.number == 1 }
            assertTrue(updated.watched)
            assertNull(vm.uiState.value.togglingEpisode)
            assertNull(vm.uiState.value.toggleError)
            coVerify(exactly = 1) {
                showRepository.updateLocalWatched(show.ids, 1, 1, true)
            }
        }

        @Test
        fun `rollback + toggleError on failure`() = runTest {
            seedLibrary()
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 2)
            )
            coEvery { episodeRepository.markEpisodeWatched(any(), 1, 1) } returns
                Result.failure(RuntimeException("boom"))

            val vm = createViewModel()
            advanceUntilIdle()

            val target = vm.uiState.value.seasons.first().episodes.first { it.number == 1 }
            vm.toggleEpisodeWatched(target)
            advanceUntilIdle()

            val reverted = vm.uiState.value.seasons.first().episodes.first { it.number == 1 }
            assertFalse(reverted.watched)
            assertNull(vm.uiState.value.togglingEpisode)
            assertNotNull(vm.uiState.value.toggleError)
            coVerify(exactly = 0) { showRepository.updateLocalWatched(any(), any(), any(), any()) }
        }

        @Test
        fun `unmark watched calls markEpisodeUnwatched`() = runTest {
            seedLibrary(
                watchedSeasons = listOf(
                    TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1)))
                )
            )
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 2)
            )
            coEvery { episodeRepository.markEpisodeUnwatched(any(), 1, 1) } returns Result.success(Unit)

            val vm = createViewModel()
            advanceUntilIdle()

            val target = vm.uiState.value.seasons.first().episodes.first { it.number == 1 }
            assertTrue(target.watched)

            vm.toggleEpisodeWatched(target)
            advanceUntilIdle()

            coVerify(exactly = 1) { episodeRepository.markEpisodeUnwatched(any(), 1, 1) }
            coVerify(exactly = 1) {
                showRepository.updateLocalWatched(show.ids, 1, 1, false)
            }
        }
    }

    @Nested
    @DisplayName("toggleSeasonExpanded")
    inner class ExpandToggleTest {

        @Test
        fun `flips expanded state without touching other seasons`() = runTest {
            seedLibrary()
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 1, 2 to 1)
            )

            val vm = createViewModel()
            advanceUntilIdle()

            val seasonTwo = vm.uiState.value.seasons.first { it.number == 2 }
            val wasExpanded = seasonTwo.expanded

            vm.toggleSeasonExpanded(2)

            val after = vm.uiState.value.seasons.first { it.number == 2 }
            assertEquals(!wasExpanded, after.expanded)
        }
    }

    @Nested
    @DisplayName("error handling")
    inner class ErrorHandlingTest {

        @Test
        fun `sets error when show is not in the library`() = runTest {
            // Empty library; cold-prime fetch returns nothing either.
            showsFlow.value = emptyList()
            coEvery { showRepository.getShows() } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertNotNull(vm.uiState.value.error)
            assertNull(vm.uiState.value.show)
        }

        @Test
        fun `sets error when episode API throws`() = runTest {
            seedLibrary()
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } throws
                RuntimeException("Trakt down")

            val vm = createViewModel()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertNotNull(vm.uiState.value.error)
        }

        @Test
        fun `clearToggleError resets the error`() = runTest {
            seedLibrary()
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 1)
            )
            coEvery { episodeRepository.markEpisodeWatched(any(), any(), any()) } returns
                Result.failure(RuntimeException("fail"))

            val vm = createViewModel()
            advanceUntilIdle()

            val target = vm.uiState.value.seasons.first().episodes.first()
            vm.toggleEpisodeWatched(target)
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.toggleError)

            vm.clearToggleError()
            assertNull(vm.uiState.value.toggleError)
        }
    }

    @Nested
    @DisplayName("markEarlierWatched")
    inner class BulkMarkTest {

        @Test
        fun `filters out already-watched episodes and specials`() = runTest {
            // S0 (special), S1 E1 watched, S1 E2 unwatched, S2 E1 unwatched.
            seedLibrary(
                watchedSeasons = listOf(
                    TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1)))
                )
            )
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(0 to 1, 1 to 2, 2 to 1)
            )
            coEvery {
                episodeRepository.markEpisodesWatchedUpTo(any(), any(), any(), any())
            } returns Result.success(Unit)

            val vm = createViewModel()
            advanceUntilIdle()

            // Target: S2E1. Candidates should be S1E2 + S2E1 (S0 excluded, S1E1 already watched).
            val target = vm.uiState.value.seasons.first { it.number == 2 }.episodes.first { it.number == 1 }
            vm.markEarlierWatched(target)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                episodeRepository.markEpisodesWatchedUpTo(
                    ids = any(),
                    targetSeason = 2,
                    targetEpisode = 1,
                    candidates = match { list ->
                        list.toSet() == setOf(1 to 2, 2 to 1)
                    }
                )
            }
        }

        @Test
        fun `optimistic flip then updateLocalWatched called per candidate on success`() = runTest {
            seedLibrary()
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 3)
            )
            coEvery {
                episodeRepository.markEpisodesWatchedUpTo(any(), any(), any(), any())
            } returns Result.success(Unit)

            val vm = createViewModel()
            advanceUntilIdle()

            // All 3 episodes are unwatched; target S1E2 → candidates S1E1, S1E2.
            val target = vm.uiState.value.seasons.first().episodes.first { it.number == 2 }
            vm.markEarlierWatched(target)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.bulkInProgress)
            assertNull(vm.uiState.value.toggleError)
            assertEquals(2, vm.uiState.value.bulkSuccessCount)

            // Both candidates flipped to watched in the UI.
            val eps = vm.uiState.value.seasons.first().episodes
            assertTrue(eps.first { it.number == 1 }.watched)
            assertTrue(eps.first { it.number == 2 }.watched)
            assertFalse(eps.first { it.number == 3 }.watched) // not a candidate

            coVerify(exactly = 1) { showRepository.updateLocalWatched(show.ids, 1, 1, true) }
            coVerify(exactly = 1) { showRepository.updateLocalWatched(show.ids, 1, 2, true) }
            coVerify(exactly = 0) { showRepository.updateLocalWatched(show.ids, 1, 3, any()) }
        }

        @Test
        fun `failure path reverts only candidate episodes, not pre-existing watched episodes`() = runTest {
            // S1E1 already watched before the operation.
            seedLibrary(
                watchedSeasons = listOf(
                    TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1)))
                )
            )
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 3)
            )
            every { application.getString(any()) } returns "bulk-error"
            coEvery {
                episodeRepository.markEpisodesWatchedUpTo(any(), any(), any(), any())
            } returns Result.failure(RuntimeException("network"))

            val vm = createViewModel()
            advanceUntilIdle()

            // Target S1E3 — candidates are S1E2, S1E3 (S1E1 already watched).
            val target = vm.uiState.value.seasons.first().episodes.first { it.number == 3 }
            vm.markEarlierWatched(target)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.bulkInProgress)
            assertNotNull(vm.uiState.value.toggleError)

            val eps = vm.uiState.value.seasons.first().episodes
            // Pre-existing watched episode must remain watched after revert.
            assertTrue(eps.first { it.number == 1 }.watched)
            // Candidate episodes reverted to unwatched.
            assertFalse(eps.first { it.number == 2 }.watched)
            assertFalse(eps.first { it.number == 3 }.watched)
            coVerify(exactly = 0) { showRepository.updateLocalWatched(any(), any(), any(), any()) }
        }

        @Test
        fun `re-entry while bulkInProgress is a no-op`() = runTest {
            seedLibrary()
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 2)
            )
            // Suspend the operation so bulkInProgress stays true.
            coEvery {
                episodeRepository.markEpisodesWatchedUpTo(any(), any(), any(), any())
            } coAnswers {
                delay(10_000)
                Result.success(Unit)
            }

            val vm = createViewModel()
            advanceUntilIdle()

            val target = vm.uiState.value.seasons.first().episodes.first { it.number == 2 }
            vm.markEarlierWatched(target)

            // bulkInProgress should be true now (operation not finished).
            assertTrue(vm.uiState.value.bulkInProgress)

            // Second call is a no-op — only one HTTP call ever issued.
            vm.markEarlierWatched(target)

            coVerify(exactly = 1) {
                episodeRepository.markEpisodesWatchedUpTo(any(), any(), any(), any())
            }
        }

        @Test
        fun `no-op when all episodes at or before target are already watched`() = runTest {
            // Both S1E1 and S1E2 already watched.
            seedLibrary(
                watchedSeasons = listOf(
                    TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1), TraktWatchedEpisode(2)))
                )
            )
            coEvery { episodeRepository.getSeasonsWithEpisodes(any()) } returns seasonsPayload(
                mapOf(1 to 2)
            )

            val vm = createViewModel()
            advanceUntilIdle()

            val target = vm.uiState.value.seasons.first().episodes.first { it.number == 2 }
            vm.markEarlierWatched(target)
            advanceUntilIdle()

            // No bulk call made, no progress flag set.
            assertFalse(vm.uiState.value.bulkInProgress)
            coVerify(exactly = 0) {
                episodeRepository.markEpisodesWatchedUpTo(any(), any(), any(), any())
            }
        }
    }
}
