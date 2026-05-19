package com.justb81.watchbuddy.phone.server

import app.cash.turbine.test
import com.justb81.watchbuddy.core.model.TmdbShow
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.trakt.SyncWatchlistResult
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ShowRepository")
class ShowRepositoryTest {

    private val traktApi: TraktApiService = mockk()
    private val tokenRefreshManager: TokenRefreshManager = mockk()
    private val tmdbApiService: TmdbApiService = mockk()
    private val settingsRepository: SettingsRepository = mockk()
    private lateinit var repository: ShowRepository

    private val testShows = listOf(
        TraktWatchedEntry(TraktShow("Show 1", 2024, TraktIds(trakt = 1, tmdb = 100))),
        TraktWatchedEntry(TraktShow("Show 2", 2023, TraktIds(trakt = 2, tmdb = 200)))
    )

    @BeforeEach
    fun setUp() {
        every { settingsRepository.getTmdbApiKey() } returns flowOf("")
        coEvery { traktApi.getWatchlistShows(any()) } returns emptyList()
        repository = ShowRepository(traktApi, tokenRefreshManager, tmdbApiService, settingsRepository)
    }

    @Test
    fun `getShows fetches from API on first call`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows("Bearer test-token") } returns testShows

        val result = repository.getShows()
        assertEquals(2, result.size)
        assertEquals("Show 1", result[0].entry.show.title)
        coVerify(exactly = 1) { traktApi.getWatchedShows(any()) }
    }

    @Test
    fun `getShows returns cached result on second call`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns testShows

        repository.getShows()
        repository.getShows()
        coVerify(exactly = 1) { traktApi.getWatchedShows(any()) }
    }

    @Test
    fun `getShows returns empty list when token refresh fails`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns null

        val result = repository.getShows()
        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { traktApi.getWatchedShows(any()) }
    }

    @Test
    fun `getShows calls API with Bearer token`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "my-secret-token"
        coEvery { traktApi.getWatchedShows("Bearer my-secret-token") } returns testShows

        repository.getShows()
        coVerify { traktApi.getWatchedShows("Bearer my-secret-token") }
    }

    @Test
    fun `getShows returns empty list when API throws with no prior cache`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("Network error")

        val result = repository.getShows()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getShows returns stale cached data when API throws after a successful fetch`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns testShows

        repository.getShows()
        repository.invalidateCache()

        coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("Trakt unavailable")

        val result = repository.getShows()

        assertEquals(2, result.size)
        coVerify(exactly = 2) { traktApi.getWatchedShows(any()) }
    }

    @Test
    fun `getShows retries API on next call after a failure`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } throws RuntimeException("Network error")
        repository.getShows()

        coEvery { traktApi.getWatchedShows(any()) } returns testShows
        val result = repository.getShows()

        coVerify(exactly = 2) { traktApi.getWatchedShows(any()) }
        assertEquals(2, result.size)
    }

    @Test
    fun `invalidateCache causes getShows to hit network even within TTL`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns testShows

        repository.getShows()
        coVerify(exactly = 1) { traktApi.getWatchedShows(any()) }

        repository.invalidateCache()
        repository.getShows()

        coVerify(exactly = 2) { traktApi.getWatchedShows(any()) }
    }

    @Test
    fun `getShows enriches entries with TMDB poster path when API key is set`() = runTest {
        every { settingsRepository.getTmdbApiKey() } returns flowOf("api-key")
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns testShows
        coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns TmdbShow(100, "Show 1", poster_path = "/one.jpg")
        coEvery { tmdbApiService.getShow(200, "api-key", any()) } returns TmdbShow(200, "Show 2", poster_path = "/two.jpg")

        val result = repository.getShows()

        assertEquals("/one.jpg", result[0].posterPath)
        assertEquals("/two.jpg", result[1].posterPath)
    }

    @Test
    fun `getShows forwards TMDB overview into TmdbProgressHint`() = runTest {
        every { settingsRepository.getTmdbApiKey() } returns flowOf("api-key")
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns testShows
        coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns
            TmdbShow(100, "Show 1", overview = "A great show about things.", poster_path = "/one.jpg")
        coEvery { tmdbApiService.getShow(200, "api-key", any()) } returns
            TmdbShow(200, "Show 2", overview = null, poster_path = "/two.jpg")

        val result = repository.getShows()

        assertEquals("A great show about things.", result[0].tmdb?.overview)
        assertNull(result[1].tmdb?.overview)
    }

    @Test
    fun `getShows emits shows sorted by last-watched DESC with unwatched at bottom`() = runTest {
        val entries = listOf(
            watchedEntry(trakt = 1, title = "Old", lastWatchedAt = "2026-04-10T10:00:00Z"),
            watchedEntry(trakt = 2, title = "Newest", lastWatchedAt = "2026-04-15T10:00:00Z"),
            watchedEntry(trakt = 3, title = "Never", lastWatchedAt = null)
        )
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns entries

        val result = repository.getShows()

        assertEquals(listOf(2, 1, 3), result.map { it.entry.show.ids.trakt })
        assertEquals(listOf(2, 1, 3), repository.shows.value.map { it.entry.show.ids.trakt })
    }

    @Test
    fun `updateLocalWatched re-sorts so a toggled older show moves to the top`() = runTest {
        val entries = listOf(
            watchedEntry(trakt = 1, title = "Old", lastWatchedAt = "2026-04-10T10:00:00Z"),
            watchedEntry(trakt = 2, title = "Newest", lastWatchedAt = "2026-04-15T10:00:00Z")
        )
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns entries
        repository.getShows()

        assertEquals(listOf(2, 1), repository.shows.value.map { it.entry.show.ids.trakt })

        // Marking a new episode on the older show "Old" should push it to the top:
        // the freshly generated Instant.now() is newer than the other show's timestamp.
        repository.updateLocalWatched(traktShowId = 1, season = 2, episode = 1, watched = true)

        assertEquals(listOf(1, 2), repository.shows.value.map { it.entry.show.ids.trakt })
    }

    @Test
    fun `updateLocalWatched back-fill does not change sort when a higher episode already exists`() = runTest {
        // Show 1 has S02E01 (highest S×E, ts = recent). Show 2 has S01E01 (older).
        // Back-filling S01E05 on show 1 (which gets Instant.now() as timestamp) must
        // NOT displace show 1's sort key — S02E01 is still the highest S×E.
        val entries = listOf(
            TraktWatchedEntry(
                show = TraktShow("Show 1", 2024, TraktIds(trakt = 1, tmdb = 100)),
                seasons = listOf(
                    TraktWatchedSeason(1, listOf(TraktWatchedEpisode(5, last_watched_at = "2026-04-01T10:00:00Z"))),
                    TraktWatchedSeason(2, listOf(TraktWatchedEpisode(1, last_watched_at = "2026-04-20T10:00:00Z")))
                )
            ),
            TraktWatchedEntry(
                show = TraktShow("Show 2", 2024, TraktIds(trakt = 2, tmdb = 200)),
                seasons = listOf(
                    TraktWatchedSeason(1, listOf(TraktWatchedEpisode(1, last_watched_at = "2026-04-19T10:00:00Z")))
                )
            )
        )
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns entries
        repository.getShows()

        // Initial order: show 1 first (S02E01 ts=Apr 20 > show 2 ts=Apr 19)
        assertEquals(listOf(1, 2), repository.shows.value.map { it.entry.show.ids.trakt })

        // Back-fill S01E03 on show 1 — gets Instant.now() but S02E01 is still highest S×E
        repository.updateLocalWatched(traktShowId = 1, season = 1, episode = 3, watched = true)

        // Order must be unchanged: show 1 still on top because its highest S×E (S02E01)
        // timestamp (Apr 20) is still newer than show 2's S01E01 timestamp (Apr 19).
        assertEquals(listOf(1, 2), repository.shows.value.map { it.entry.show.ids.trakt })
    }

    @Nested
    @DisplayName("concurrent fetch and toggle — atomic update (#532)")
    inner class ConcurrentFetchToggleTest {

        @Test
        fun `updateLocalWatched emits correct state via StateFlow (Turbine)`() = runTest {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(
                TraktWatchedEntry(TraktShow("Show A", 2024, TraktIds(trakt = 1, tmdb = 100)))
            )
            // Seed state BEFORE subscribing so the initial StateFlow emission is non-empty.
            // StateFlow always replays its current value to new collectors; subscribing before
            // the fetch would deliver the empty-list snapshot as the first item.
            repository.getShows()

            repository.shows.test {
                awaitItem() // consume the seeded [Show A] emission

                // Apply local toggle — must emit a new value atomically.
                repository.updateLocalWatched(traktShowId = 1, season = 2, episode = 7, watched = true)
                val afterToggle = awaitItem()
                assertTrue(
                    afterToggle.any { e ->
                        e.entry.seasons.any { s -> s.number == 2 && s.episodes.any { ep -> ep.number == 7 } }
                    },
                    "S02E07 must appear in the StateFlow after toggle"
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `toggle applied while fetch is in-flight does not lose Trakt data from fetch result`() = runTest {
            // This exercises the specific race fixed by update{}: without the CAS-based
            // update, updateLocalWatched could write a stale-based state AFTER getShows
            // wrote fresh Trakt data, clobbering that fresh data. With update{}, the lambda
            // retries on CAS failure so it always applies the toggle on top of the most
            // current value at write time.
            val apiCallStarted = CompletableDeferred<Unit>()
            val apiCallAllowed = CompletableDeferred<Unit>()

            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            // Seed initial state: Show A with no watched episodes.
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(
                TraktWatchedEntry(TraktShow("Show A", 2024, TraktIds(trakt = 1, tmdb = 100)))
            )
            repository.getShows()

            // Second fetch: returns S01E05 from Trakt, but suspends until signalled.
            repository.invalidateCache()
            coEvery { traktApi.getWatchedShows(any()) } coAnswers {
                apiCallStarted.complete(Unit)
                apiCallAllowed.await()
                listOf(
                    TraktWatchedEntry(
                        show = TraktShow("Show A", 2024, TraktIds(trakt = 1, tmdb = 100)),
                        seasons = listOf(
                            TraktWatchedSeason(
                                number = 1,
                                episodes = listOf(
                                    TraktWatchedEpisode(number = 5, last_watched_at = "2026-04-29T10:00:00Z")
                                )
                            )
                        )
                    )
                )
            }

            repository.shows.test {
                awaitItem() // consume seeded emission

                // Start the second fetch — it suspends at the API call.
                val fetchJob = launch { repository.getShows() }
                apiCallStarted.await()

                // Toggle S02E03 while the fetch is suspended.
                // With update{}, this CAS-writes [old + S02E03].
                // When the fetch later writes [S01E05] directly, the CAS in a concurrent
                // update{} would detect the change and retry — but since our toggle
                // already completed, the toggle emission fires now and the fetch will
                // overwrite it with authoritative Trakt data next.
                repository.updateLocalWatched(traktShowId = 1, season = 2, episode = 3, watched = true)

                val afterToggle = awaitItem()
                assertTrue(
                    afterToggle.any { e ->
                        e.entry.seasons.any { s -> s.number == 2 && s.episodes.any { ep -> ep.number == 3 } }
                    },
                    "Toggle must be visible in the flow before the fetch completes"
                )

                // Let the fetch complete — it writes authoritative Trakt data ([S01E05]).
                apiCallAllowed.complete(Unit)
                fetchJob.join()

                val afterFetch = awaitItem()
                assertTrue(
                    afterFetch.any { e ->
                        e.entry.seasons.any { s -> s.number == 1 && s.episodes.any { ep -> ep.number == 5 } }
                    },
                    "S01E05 from Trakt must be present after the fetch completes"
                )

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `rapid concurrent toggles leave _shows in a consistent state`() = runTest {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "token"
            coEvery { traktApi.getWatchedShows(any()) } returns listOf(
                TraktWatchedEntry(TraktShow("Show A", 2024, TraktIds(trakt = 1, tmdb = 100)))
            )
            repository.getShows()

            // Fire many toggles sequentially within the same test coroutine to verify
            // that the update{} lambda never panics and the state stays non-empty.
            for (ep in 1..10) {
                repository.updateLocalWatched(traktShowId = 1, season = 1, episode = ep, watched = true)
            }

            val state = repository.shows.value
            assertEquals(1, state.size, "Exactly one show must remain")
            val seasons = state.first().entry.seasons
            val s1episodes = seasons.find { it.number == 1 }?.episodes ?: emptyList()
            assertEquals(10, s1episodes.size, "All 10 episodes must be toggled on")
        }
    }

    @Nested
    @DisplayName("addShowToWatchlist — shows StateFlow update (#731)")
    inner class AddShowToWatchlistTest {

        @Test
        fun `addShowToWatchlist updates shows StateFlow immediately without waiting for next fetch`() = runTest {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
            coEvery { traktApi.getWatchedShows(any()) } returns testShows
            coEvery { traktApi.addToWatchlist(any(), any()) } returns SyncWatchlistResult()
            repository.getShows()

            val newShow = TraktShow("New Show", 2025, TraktIds(trakt = 99, tmdb = 999))

            repository.shows.test {
                awaitItem() // consume the initial seeded emission

                repository.addShowToWatchlist("Bearer test-token", newShow)

                val afterAdd = awaitItem()
                assertTrue(
                    afterAdd.any { it.entry.show.ids.trakt == 99 },
                    "New show must appear in the StateFlow immediately after addShowToWatchlist"
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun `addShowToWatchlist does not add duplicate when show is already tracked`() = runTest {
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
            coEvery { traktApi.getWatchedShows(any()) } returns testShows
            coEvery { traktApi.addToWatchlist(any(), any()) } returns SyncWatchlistResult()
            repository.getShows()

            val existingShow = testShows.first().show

            repository.addShowToWatchlist("Bearer test-token", existingShow)

            val shows = repository.shows.value
            assertEquals(2, shows.size, "No duplicate must be added for an already-tracked show")
        }

        @Test
        fun `addShowToWatchlist appended show appears at correct sort position`() = runTest {
            val entries = listOf(
                watchedEntry(trakt = 1, title = "Alpha", lastWatchedAt = "2026-04-15T10:00:00Z"),
                watchedEntry(trakt = 2, title = "Beta", lastWatchedAt = "2026-04-10T10:00:00Z")
            )
            coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
            coEvery { traktApi.getWatchedShows(any()) } returns entries
            coEvery { traktApi.addToWatchlist(any(), any()) } returns SyncWatchlistResult()
            repository.getShows()

            val newShow = TraktShow("Zeta", 2025, TraktIds(trakt = 99, tmdb = 999))
            repository.addShowToWatchlist("Bearer test-token", newShow)

            val ids = repository.shows.value.map { it.entry.show.ids.trakt }
            // The new show has no watch history so latestWatchedInstant == null,
            // meaning it sorts last (after all shows with a last-watched timestamp).
            assertEquals(
                listOf(1, 2, 99),
                ids,
                "Unwatched newly added show must sort after shows with watch history"
            )
        }
    }

    @Test
    fun `getShows tolerates per-show TMDB failures`() = runTest {
        every { settingsRepository.getTmdbApiKey() } returns flowOf("api-key")
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
        coEvery { traktApi.getWatchedShows(any()) } returns testShows
        coEvery { tmdbApiService.getShow(100, "api-key", any()) } returns TmdbShow(100, "Show 1", poster_path = "/one.jpg")
        coEvery { tmdbApiService.getShow(200, "api-key", any()) } throws RuntimeException("TMDB down for Show 2")

        val result = repository.getShows()

        assertEquals(2, result.size)
        assertEquals("/one.jpg", result[0].posterPath)
        assertNull(result[1].posterPath)
        assertNull(result[1].tmdb)
    }

    private fun watchedEntry(
        trakt: Int,
        title: String,
        lastWatchedAt: String?
    ): TraktWatchedEntry = TraktWatchedEntry(
        show = TraktShow(title, 2024, TraktIds(trakt = trakt, tmdb = trakt * 100)),
        seasons = if (lastWatchedAt == null) emptyList() else listOf(
            TraktWatchedSeason(
                number = 1,
                episodes = listOf(TraktWatchedEpisode(number = 1, last_watched_at = lastWatchedAt))
            )
        )
    )
}
