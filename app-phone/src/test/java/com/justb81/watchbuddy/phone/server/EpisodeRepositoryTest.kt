package com.justb81.watchbuddy.phone.server

import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.trakt.SyncHistoryBody
import com.justb81.watchbuddy.core.trakt.SyncHistoryResult
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("EpisodeRepository")
class EpisodeRepositoryTest {

    private val traktApi: TraktApiService = mockk()
    private val tokenRefreshManager: TokenRefreshManager = mockk()
    private lateinit var repository: EpisodeRepository

    private val sampleSeasons = listOf(
        TraktSeasonWithEpisodes(
            number = 1,
            episodes = listOf(
                TraktEpisode(season = 1, number = 1, title = "Pilot"),
                TraktEpisode(season = 1, number = 2, title = "Second")
            )
        )
    )

    @BeforeEach
    fun setUp() {
        repository = EpisodeRepository(traktApi, tokenRefreshManager)
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
    }

    @Test
    fun `getSeasonsWithEpisodes fetches from API on first call`() = runTest {
        coEvery { traktApi.getShowSeasons(any(), any(), any()) } returns sampleSeasons

        val result = repository.getSeasonsWithEpisodes("42")

        assertEquals(sampleSeasons, result)
        coVerify(exactly = 1) { traktApi.getShowSeasons("Bearer test-token", "42", "episodes") }
    }

    @Test
    fun `getSeasonsWithEpisodes returns cached result on second call within TTL`() = runTest {
        coEvery { traktApi.getShowSeasons(any(), any(), any()) } returns sampleSeasons

        repository.getSeasonsWithEpisodes("42")
        repository.getSeasonsWithEpisodes("42")

        coVerify(exactly = 1) { traktApi.getShowSeasons(any(), any(), any()) }
    }

    @Test
    fun `getSeasonsWithEpisodes fetches separately for different show ids`() = runTest {
        coEvery { traktApi.getShowSeasons(any(), "42", any()) } returns sampleSeasons
        coEvery { traktApi.getShowSeasons(any(), "99", any()) } returns emptyList()

        repository.getSeasonsWithEpisodes("42")
        repository.getSeasonsWithEpisodes("99")
        repository.getSeasonsWithEpisodes("42")

        coVerify(exactly = 1) { traktApi.getShowSeasons(any(), "42", any()) }
        coVerify(exactly = 1) { traktApi.getShowSeasons(any(), "99", any()) }
    }

    @Test
    fun `getSeasonsWithEpisodes throws when no access token`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns null

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { repository.getSeasonsWithEpisodes("42") }
        }
    }

    @Test
    fun `markEpisodeWatched posts correct SyncHistoryBody`() = runTest {
        val slot = slot<SyncHistoryBody>()
        coEvery { traktApi.addToHistory(any(), capture(slot)) } returns SyncHistoryResult()

        val ids = TraktIds(trakt = 7, slug = "show-7")
        val result = repository.markEpisodeWatched(ids, season = 2, episode = 5)

        assertTrue(result.isSuccess)
        val body = slot.captured
        assertEquals(1, body.shows.size)
        assertEquals(ids, body.shows[0].ids)
        assertEquals(1, body.shows[0].seasons.size)
        assertEquals(2, body.shows[0].seasons[0].number)
        assertEquals(1, body.shows[0].seasons[0].episodes.size)
        assertEquals(5, body.shows[0].seasons[0].episodes[0].number)
    }

    @Test
    fun `markEpisodeUnwatched calls removeFromHistory`() = runTest {
        val slot = slot<SyncHistoryBody>()
        coEvery { traktApi.removeFromHistory(any(), capture(slot)) } returns SyncHistoryResult()

        val ids = TraktIds(trakt = 7)
        val result = repository.markEpisodeUnwatched(ids, season = 1, episode = 3)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { traktApi.removeFromHistory(any(), any()) }
        coVerify(exactly = 0) { traktApi.addToHistory(any(), any()) }
        assertEquals(3, slot.captured.shows[0].seasons[0].episodes[0].number)
    }

    @Test
    fun `markEpisodeWatched returns failure when API throws`() = runTest {
        coEvery { traktApi.addToHistory(any(), any()) } throws RuntimeException("Network error")

        val result = repository.markEpisodeWatched(TraktIds(trakt = 7), season = 1, episode = 1)

        assertTrue(result.isFailure)
        assertEquals("Network error", result.exceptionOrNull()?.message)
    }

    @Test
    fun `markEpisodeWatched returns failure when no access token`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns null

        val result = repository.markEpisodeWatched(TraktIds(trakt = 7), season = 1, episode = 1)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
}
