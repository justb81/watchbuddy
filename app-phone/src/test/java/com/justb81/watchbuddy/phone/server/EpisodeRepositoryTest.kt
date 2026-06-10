package com.justb81.watchbuddy.phone.server

import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.tracking.TrackingProvider
import com.justb81.watchbuddy.core.trakt.SyncHistorySeasonItem
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

    private val trackingProvider: TrackingProvider = mockk()
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
        repository = EpisodeRepository(trackingProvider, tokenRefreshManager)
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
    }

    @Test
    fun `getSeasonsWithEpisodes fetches from API on first call`() = runTest {
        coEvery { trackingProvider.getSeasonsWithEpisodes(any(), any()) } returns sampleSeasons

        val result = repository.getSeasonsWithEpisodes("42")

        assertEquals(sampleSeasons, result)
        coVerify(exactly = 1) { trackingProvider.getSeasonsWithEpisodes("Bearer test-token", "42") }
    }

    @Test
    fun `getSeasonsWithEpisodes returns cached result on second call within TTL`() = runTest {
        coEvery { trackingProvider.getSeasonsWithEpisodes(any(), any()) } returns sampleSeasons

        repository.getSeasonsWithEpisodes("42")
        repository.getSeasonsWithEpisodes("42")

        coVerify(exactly = 1) { trackingProvider.getSeasonsWithEpisodes(any(), any()) }
    }

    @Test
    fun `getSeasonsWithEpisodes fetches separately for different show ids`() = runTest {
        coEvery { trackingProvider.getSeasonsWithEpisodes(any(), "42") } returns sampleSeasons
        coEvery { trackingProvider.getSeasonsWithEpisodes(any(), "99") } returns emptyList()

        repository.getSeasonsWithEpisodes("42")
        repository.getSeasonsWithEpisodes("99")
        repository.getSeasonsWithEpisodes("42")

        coVerify(exactly = 1) { trackingProvider.getSeasonsWithEpisodes(any(), "42") }
        coVerify(exactly = 1) { trackingProvider.getSeasonsWithEpisodes(any(), "99") }
    }

    @Test
    fun `getSeasonsWithEpisodes throws when no access token`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns null

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { repository.getSeasonsWithEpisodes("42") }
        }
    }

    @Test
    fun `markEpisodeWatched invalidates per-show cache so next getSeasonsWithEpisodes refetches`() = runTest {
        coEvery { trackingProvider.getSeasonsWithEpisodes(any(), "42") } returns sampleSeasons
        coEvery { trackingProvider.markWatched(any(), any(), any()) } returns Result.success(Unit)

        repository.getSeasonsWithEpisodes("42")
        repository.markEpisodeWatched(TraktIds(trakt = 42), season = 1, episode = 1)
        repository.getSeasonsWithEpisodes("42")

        coVerify(exactly = 2) { trackingProvider.getSeasonsWithEpisodes(any(), "42") }
    }

    @Test
    fun `markEpisodeUnwatched invalidates per-show cache so next getSeasonsWithEpisodes refetches`() = runTest {
        coEvery { trackingProvider.getSeasonsWithEpisodes(any(), "42") } returns sampleSeasons
        coEvery { trackingProvider.markUnwatched(any(), any(), any(), any()) } returns Result.success(Unit)

        repository.getSeasonsWithEpisodes("42")
        repository.markEpisodeUnwatched(TraktIds(trakt = 42), season = 1, episode = 1)
        repository.getSeasonsWithEpisodes("42")

        coVerify(exactly = 2) { trackingProvider.getSeasonsWithEpisodes(any(), "42") }
    }

    @Test
    fun `markEpisodeWatched posts correct seasons to trackingProvider`() = runTest {
        val seasonsSlot = slot<List<SyncHistorySeasonItem>>()
        val idsSlot = slot<com.justb81.watchbuddy.core.model.TraktIds>()
        coEvery {
            trackingProvider.markWatched(any(), capture(idsSlot), capture(seasonsSlot))
        } returns Result.success(Unit)

        val ids = TraktIds(trakt = 7, slug = "show-7")
        val result = repository.markEpisodeWatched(ids, season = 2, episode = 5)

        assertTrue(result.isSuccess)
        assertEquals(ids, idsSlot.captured)
        val seasons = seasonsSlot.captured
        assertEquals(1, seasons.size)
        assertEquals(2, seasons[0].number)
        assertEquals(1, seasons[0].episodes.size)
        assertEquals(5, seasons[0].episodes[0].number)
    }

    @Test
    fun `markEpisodeUnwatched calls markUnwatched with correct params`() = runTest {
        val idsSlot = slot<com.justb81.watchbuddy.core.model.TraktIds>()
        val seasonSlot = slot<Int>()
        val episodeSlot = slot<Int>()
        coEvery {
            trackingProvider.markUnwatched(any(), capture(idsSlot), capture(seasonSlot), capture(episodeSlot))
        } returns Result.success(Unit)

        val ids = TraktIds(trakt = 7)
        val result = repository.markEpisodeUnwatched(ids, season = 1, episode = 3)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { trackingProvider.markUnwatched(any(), any(), any(), any()) }
        coVerify(exactly = 0) { trackingProvider.markWatched(any(), any(), any()) }
        assertEquals(3, episodeSlot.captured)
    }

    @Test
    fun `markEpisodeWatched returns failure when API throws`() = runTest {
        coEvery { trackingProvider.markWatched(any(), any(), any()) } returns
            Result.failure(RuntimeException("Network error"))

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

class EpisodeRepositoryBulkTest {

    private val trackingProvider: TrackingProvider = mockk()
    private val tokenRefreshManager: TokenRefreshManager = mockk()
    private lateinit var repository: EpisodeRepository

    @BeforeEach
    fun setUp() {
        repository = EpisodeRepository(trackingProvider, tokenRefreshManager)
        coEvery { tokenRefreshManager.getValidAccessToken() } returns "test-token"
    }

    @Test
    fun `markEpisodesWatchedUpTo builds one SyncHistoryBody grouped by season`() = runTest {
        val seasonsSlot = slot<List<SyncHistorySeasonItem>>()
        coEvery { trackingProvider.markWatched(any(), any(), capture(seasonsSlot)) } returns Result.success(Unit)

        val ids = TraktIds(trakt = 42, slug = "test-show")
        val candidates = listOf(1 to 1, 1 to 3, 2 to 1, 2 to 2)
        val result = repository.markEpisodesWatchedUpTo(
            ids = ids,
            targetSeason = 2,
            targetEpisode = 2,
            candidates = candidates
        )

        assertTrue(result.isSuccess)
        val seasons = seasonsSlot.captured.sortedBy { it.number }
        assertEquals(2, seasons.size)

        val s1 = seasons[0]
        assertEquals(1, s1.number)
        assertEquals(setOf(1, 3), s1.episodes.map { it.number }.toSet())

        val s2 = seasons[1]
        assertEquals(2, s2.number)
        assertEquals(setOf(1, 2), s2.episodes.map { it.number }.toSet())
    }

    @Test
    fun `markEpisodesWatchedUpTo returns success and makes no HTTP call for empty candidates`() = runTest {
        val result = repository.markEpisodesWatchedUpTo(
            ids = TraktIds(trakt = 1),
            targetSeason = 1,
            targetEpisode = 1,
            candidates = emptyList()
        )

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { trackingProvider.markWatched(any(), any(), any()) }
    }

    @Test
    fun `markEpisodesWatchedUpTo returns failure when API throws`() = runTest {
        coEvery { trackingProvider.markWatched(any(), any(), any()) } returns
            Result.failure(RuntimeException("Network error"))

        val result = repository.markEpisodesWatchedUpTo(
            ids = TraktIds(trakt = 1),
            targetSeason = 2,
            targetEpisode = 3,
            candidates = listOf(1 to 1, 1 to 2)
        )

        assertTrue(result.isFailure)
        assertEquals("Network error", result.exceptionOrNull()?.message)
    }

    @Test
    fun `markEpisodesWatchedUpTo returns failure when no access token`() = runTest {
        coEvery { tokenRefreshManager.getValidAccessToken() } returns null

        val result = repository.markEpisodesWatchedUpTo(
            ids = TraktIds(trakt = 1),
            targetSeason = 1,
            targetEpisode = 1,
            candidates = listOf(1 to 1)
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
}
