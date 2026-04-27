package com.justb81.watchbuddy.tv.data

import com.justb81.watchbuddy.core.justwatch.JustWatchApiService
import com.justb81.watchbuddy.core.justwatch.JustWatchContent
import com.justb81.watchbuddy.core.justwatch.JustWatchData
import com.justb81.watchbuddy.core.justwatch.JustWatchEpisode
import com.justb81.watchbuddy.core.justwatch.JustWatchExternalIds
import com.justb81.watchbuddy.core.justwatch.JustWatchGraphQlResponse
import com.justb81.watchbuddy.core.justwatch.JustWatchNode
import com.justb81.watchbuddy.core.justwatch.JustWatchOffer
import com.justb81.watchbuddy.core.justwatch.JustWatchPackage
import com.justb81.watchbuddy.core.justwatch.JustWatchSeasonRef
import com.justb81.watchbuddy.core.justwatch.JustWatchTitle
import com.justb81.watchbuddy.core.justwatch.JustWatchTitleConnection
import com.justb81.watchbuddy.core.justwatch.JustWatchTitleEdge
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("JustWatchDeepLinkRepository")
class JustWatchDeepLinkRepositoryTest {

    private val dao: JustWatchDeepLinkDao = mockk()
    private val api: JustWatchApiService = mockk()
    private lateinit var repository: JustWatchDeepLinkRepository

    private fun makeLink(
        tmdbShowId: Int = 100,
        season: Int = 1,
        episode: Int = 2,
        providerId: Int = 8,
        countryCode: String = "US",
        url: String? = "https://www.netflix.com/watch/99",
        fetchedAt: Long = System.currentTimeMillis(),
    ) = JustWatchDeepLink(tmdbShowId, season, episode, providerId, countryCode, url, fetchedAt)

    private fun makeSearchResponse(
        tmdbId: String = "100",
        nodeId: String = "show-123",
        offers: List<JustWatchOffer> = emptyList(),
    ) = JustWatchGraphQlResponse(
        data = JustWatchData(
            popularTitles = JustWatchTitleConnection(
                edges = listOf(
                    JustWatchTitleEdge(
                        node = JustWatchTitle(
                            id = nodeId,
                            offers = offers,
                            content = JustWatchContent(
                                externalIds = JustWatchExternalIds(tmdbId = tmdbId)
                            ),
                        )
                    )
                )
            )
        )
    )

    private fun makeSeasonsResponse(seasonIds: List<String> = listOf("season-1-id")) =
        JustWatchGraphQlResponse(
            data = JustWatchData(
                node = JustWatchNode(seasons = seasonIds.map { JustWatchSeasonRef(id = it) })
            )
        )

    private fun makeEpisodesResponse(
        episodes: List<JustWatchEpisode>,
    ) = JustWatchGraphQlResponse(
        data = JustWatchData(
            node = JustWatchNode(episodes = episodes)
        )
    )

    private fun makeOffer(
        url: String = "https://www.netflix.com/watch/99",
        technicalName: String = "nfx",
        monetizationType: String = "FLATRATE",
    ) = JustWatchOffer(
        standardWebURL = url,
        monetizationType = monetizationType,
        `package` = JustWatchPackage(technicalName = technicalName),
    )

    @BeforeEach
    fun setUp() {
        coEvery { dao.get(any(), any(), any(), any(), any()) } returns null
        coEvery { dao.upsert(any()) } just runs
        repository = JustWatchDeepLinkRepository(dao, api)
    }

    @Nested
    @DisplayName("cache hits")
    inner class CacheHitTests {

        @Test
        fun `returns cached URL without calling API`() = runTest {
            coEvery { dao.get(100, 1, 2, 8, "US") } returns makeLink(url = "https://www.netflix.com/watch/99")

            val result = repository.resolveDeepLink(100, 1, 2, 8, "US", "Breaking Bad", 2008)

            assertEquals("https://www.netflix.com/watch/99", result)
            coVerify(exactly = 0) { api.query(any()) }
        }

        @Test
        fun `returns null for fresh negative cache without calling API`() = runTest {
            val freshNegative = makeLink(url = null, fetchedAt = System.currentTimeMillis())
            coEvery { dao.get(100, 1, 2, 8, "US") } returns freshNegative

            val result = repository.resolveDeepLink(100, 1, 2, 8, "US", "Breaking Bad", 2008)

            assertNull(result)
            coVerify(exactly = 0) { api.query(any()) }
        }

        @Test
        fun `re-fetches when negative cache entry has expired`() = runTest {
            val expiredNegative = makeLink(
                url = null,
                fetchedAt = System.currentTimeMillis() - JustWatchDeepLink.NEGATIVE_TTL_MS - 1000,
            )
            // Episode-level: expired negative → triggers fetch
            coEvery { dao.get(100, 1, 2, 8, "US") } returns expiredNegative
            // After fetch, still returns null (still no offer found)
            coEvery { dao.get(100, 0, 0, 8, "US") } returns null

            // API returns no results
            coEvery { api.query(any()) } returns JustWatchGraphQlResponse(data = null)

            val result = repository.resolveDeepLink(100, 1, 2, 8, "US", "Breaking Bad", 2008)

            assertNull(result)
            coVerify(atLeast = 1) { api.query(any()) }
        }
    }

    @Nested
    @DisplayName("episode-level fetch")
    inner class EpisodeLevelFetchTests {

        @Test
        fun `fetches and returns URL on cache miss`() = runTest {
            val episodeOffer = makeOffer(url = "https://www.netflix.com/watch/42")
            val fetchedAt = System.currentTimeMillis()

            // Initial cache miss for episode
            coEvery { dao.get(100, 1, 2, 8, "US") } returnsMany listOf(
                null,
                JustWatchDeepLink(100, 1, 2, 8, "US", "https://www.netflix.com/watch/42", fetchedAt),
            )

            coEvery { api.query(match { it.query == JustWatchApiService.SEARCH_QUERY }) } returns
                makeSearchResponse(offers = listOf(episodeOffer))
            coEvery { api.query(match { it.query == JustWatchApiService.SEASONS_QUERY }) } returns
                makeSeasonsResponse(listOf("season-1-id"))
            coEvery { api.query(match { it.query == JustWatchApiService.EPISODES_QUERY }) } returns
                makeEpisodesResponse(
                    listOf(
                        JustWatchEpisode(
                            episodeNumber = 2,
                            seasonNumber = 1,
                            offers = listOf(episodeOffer),
                        )
                    )
                )

            val result = repository.resolveDeepLink(100, 1, 2, 8, "US", "Breaking Bad", 2008)

            assertEquals("https://www.netflix.com/watch/42", result)
        }

        @Test
        fun `caches show-level offers during episode fetch`() = runTest {
            val showOffer = makeOffer(url = "https://www.netflix.com/title/100")

            coEvery { dao.get(100, 1, 2, 8, "US") } returns null
            // After episode-level fetch re-check: no episode result
            coEvery { dao.get(100, 1, 2, 8, "US") } returns null andThen null
            // Show-level fallback also null
            coEvery { dao.get(100, 0, 0, 8, "US") } returns null andThen null

            coEvery { api.query(match { it.query == JustWatchApiService.SEARCH_QUERY }) } returns
                makeSearchResponse(offers = listOf(showOffer))
            coEvery { api.query(match { it.query == JustWatchApiService.SEASONS_QUERY }) } returns
                makeSeasonsResponse()
            coEvery { api.query(match { it.query == JustWatchApiService.EPISODES_QUERY }) } returns
                makeEpisodesResponse(emptyList())

            repository.resolveDeepLink(100, 1, 2, 8, "US", "Breaking Bad", 2008)

            // Show-level offers (season=0, episode=0) should have been cached
            coVerify { dao.upsert(match { it.season == 0 && it.episode == 0 && it.standard_web_url != null }) }
        }

        @Test
        fun `caches negative when search returns no matching TMDB id`() = runTest {
            coEvery { dao.get(100, 1, 2, 8, "US") } returns null andThen null
            coEvery { dao.get(100, 0, 0, 8, "US") } returns null andThen null

            // Search returns a result but with wrong TMDB id
            coEvery { api.query(match { it.query == JustWatchApiService.SEARCH_QUERY }) } returns
                makeSearchResponse(tmdbId = "999")

            repository.resolveDeepLink(100, 1, 2, 8, "US", "Unknown Show", 2020)

            // Should have cached negatives for all known providers
            coVerify(atLeast = 1) { dao.upsert(match { it.standard_web_url == null }) }
        }

        @Test
        fun `does not cache negatives on API exception`() = runTest {
            coEvery { dao.get(100, 1, 2, 8, "US") } returns null andThen null
            coEvery { dao.get(100, 0, 0, 8, "US") } returns null andThen null

            coEvery { api.query(any()) } throws RuntimeException("network error")

            val result = repository.resolveDeepLink(100, 1, 2, 8, "US", "Breaking Bad", 2008)

            assertNull(result)
            // No negatives should be cached — allow retry on next call
            coVerify(exactly = 0) { dao.upsert(any()) }
        }

        @Test
        fun `filters out non-FLATRATE-ADS-FREE offers`() = runTest {
            val rentalOffer = makeOffer(
                url = "https://www.amazon.com/rent/99",
                technicalName = "atp",
                monetizationType = "RENT",
            )
            coEvery { dao.get(100, 1, 2, 350, "US") } returns null andThen null
            coEvery { dao.get(100, 0, 0, 350, "US") } returns null andThen null

            coEvery { api.query(match { it.query == JustWatchApiService.SEARCH_QUERY }) } returns
                makeSearchResponse(offers = listOf(rentalOffer))
            coEvery { api.query(match { it.query == JustWatchApiService.SEASONS_QUERY }) } returns
                makeSeasonsResponse()
            coEvery { api.query(match { it.query == JustWatchApiService.EPISODES_QUERY }) } returns
                makeEpisodesResponse(emptyList())

            repository.resolveDeepLink(100, 1, 2, 350, "US", "Some Show", 2020)

            // RENT offer should not produce a positive cache entry
            coVerify(exactly = 0) {
                dao.upsert(match { it.provider_id == 350 && it.standard_web_url != null })
            }
        }
    }

    @Nested
    @DisplayName("show-level fallback")
    inner class ShowLevelFallbackTests {

        @Test
        fun `falls back to show-level cache when episode not in JustWatch`() = runTest {
            val showLink = makeLink(season = 0, episode = 0, url = "https://www.netflix.com/title/100")

            coEvery { dao.get(100, 1, 2, 8, "US") } returns null andThen null
            coEvery { dao.get(100, 0, 0, 8, "US") } returns showLink

            coEvery { api.query(match { it.query == JustWatchApiService.SEARCH_QUERY }) } returns
                makeSearchResponse()
            coEvery { api.query(match { it.query == JustWatchApiService.SEASONS_QUERY }) } returns
                makeSeasonsResponse()
            coEvery { api.query(match { it.query == JustWatchApiService.EPISODES_QUERY }) } returns
                makeEpisodesResponse(emptyList())

            val result = repository.resolveDeepLink(100, 1, 2, 8, "US", "Breaking Bad", 2008)

            assertEquals("https://www.netflix.com/title/100", result)
        }
    }

    @Nested
    @DisplayName("utility functions")
    inner class UtilityTests {

        @Test
        fun `count delegates to dao countPositive`() = runTest {
            coEvery { dao.countPositive() } returns 42
            assertEquals(42, repository.count())
        }

        @Test
        fun `negativeCount delegates to dao countNegative`() = runTest {
            coEvery { dao.countNegative() } returns 7
            assertEquals(7, repository.negativeCount())
        }

        @Test
        fun `lastFetchedAt delegates to dao`() = runTest {
            coEvery { dao.lastFetchedAt() } returns 1_700_000_000_000L
            assertEquals(1_700_000_000_000L, repository.lastFetchedAt())
        }

        @Test
        fun `clearAll delegates to dao deleteAll`() = runTest {
            coEvery { dao.deleteAll() } just runs
            repository.clearAll()
            coVerify { dao.deleteAll() }
        }
    }
}
