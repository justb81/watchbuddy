package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.model.TmdbEpisodeSummary
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TmdbSeasonSummary
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression tests for issue #401: when a streaming app ships only the show name
 * in `METADATA_KEY_TITLE` (no `S##E##`), the scrobbler previously returned a
 * candidate with `matchedEpisode = null` — silently dropping every Netflix /
 * Prime Video / Disney+ play.
 *
 * The fix uses [ShowProgressCalculator.nextEpisodeNumbers] on the cached progress
 * hint to guess the next unwatched episode when the show match is high-confidence.
 */
@DisplayName("MediaSessionScrobbler — Episode fallback (#401)")
class MediaSessionScrobblerFallbackTest {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    private val breakingBadIds = TraktIds(trakt = 1, tmdb = 1396)
    private val breakingBadShow = TraktShow("Breaking Bad", 2008, breakingBadIds)
    private val breakingBadEntry = TraktWatchedEntry(show = breakingBadShow)

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(context, tmdbApiService, watchedShowSource, scrobbleDispatcher, NoOpTitleExtractor)
        coEvery { watchedShowSource.getTmdbApiKey() } returns null
    }

    @Test
    fun `title without SxxExx uses hint nextAired when high-confidence cache match`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)
        coEvery { watchedShowSource.getShowHint(breakingBadIds) } returns TmdbProgressHint(
            nextAired = TmdbEpisodeSummary(season_number = 4, episode_number = 7, air_date = "2024-01-01"),
            seasons = listOf(TmdbSeasonSummary(4, 13))
        )

        val result = scrobbler.matchTitle("com.netflix", "Breaking Bad")

        assertNotNull(result)
        assertEquals(breakingBadShow, result!!.matchedShow)
        assertEquals(4, result.matchedEpisode?.season)
        assertEquals(7, result.matchedEpisode?.number)
    }

    @Test
    fun `title without SxxExx falls back to last-watched plus one when no nextAired`() = runTest {
        val partiallyWatchedEntry = TraktWatchedEntry(
            show = breakingBadShow,
            seasons = listOf(
                com.justb81.watchbuddy.core.model.TraktWatchedSeason(
                    number = 2,
                    episodes = listOf(
                        com.justb81.watchbuddy.core.model.TraktWatchedEpisode(
                            number = 5,
                            last_watched_at = "2024-01-01T00:00:00Z"
                        )
                    )
                )
            )
        )
        coEvery { watchedShowSource.getCachedShows() } returns listOf(partiallyWatchedEntry)
        coEvery { watchedShowSource.getShowHint(breakingBadIds) } returns TmdbProgressHint(
            seasons = listOf(TmdbSeasonSummary(2, 10))
        )

        val result = scrobbler.matchTitle("com.netflix", "Breaking Bad")

        assertNotNull(result)
        assertEquals(2, result!!.matchedEpisode?.season)
        assertEquals(6, result.matchedEpisode?.number)
    }

    @Test
    fun `title without SxxExx drops with null episode when no hint cached`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)
        coEvery { watchedShowSource.getShowHint(breakingBadIds) } returns null

        val result = scrobbler.matchTitle("com.netflix", "Breaking Bad")

        assertNotNull(result)
        assertEquals(breakingBadShow, result!!.matchedShow)
        assertNull(result.matchedEpisode, "Expected null episode when fallback has no hint to lean on")
    }

    @Test
    fun `title with explicit SxxExx prefers parsed numbers over hint`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)
        coEvery { watchedShowSource.getShowHint(breakingBadIds) } returns TmdbProgressHint(
            nextAired = TmdbEpisodeSummary(season_number = 4, episode_number = 7, air_date = "2024-01-01")
        )

        val result = scrobbler.matchTitle("com.netflix", "Breaking Bad S01E03")

        assertNotNull(result)
        assertEquals(1, result!!.matchedEpisode?.season)
        assertEquals(3, result.matchedEpisode?.number)
        coVerify(exactly = 0) { watchedShowSource.getShowHint(any()) }
    }

    @Test
    fun `low-confidence match does not trigger hint fallback`() = runTest {
        // "Breaking Bad!!" vs "Breaking Bad" is a prefix match = 0.95 confidence.
        // We need a sub-0.95 confidence match to exercise the guard. The fuzzy
        // scorer returns 0.95 for prefix matches and computes Levenshtein
        // otherwise — "Braking Bad" (1 typo) lands below 0.95.
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)

        val result = scrobbler.matchTitle("com.netflix", "Braking Bad")

        assertNotNull(result)
        assertTrue(result!!.confidence < MediaSessionScrobbler.AUTO_SCROBBLE_THRESHOLD)
        assertNull(result.matchedEpisode)
        coVerify(exactly = 0) { watchedShowSource.getShowHint(any()) }
    }

    @Test
    fun `hint lookup is skipped when title already carries SxxExx`() = runTest {
        coEvery { watchedShowSource.getCachedShows() } returns listOf(breakingBadEntry)

        val result = scrobbler.matchTitle("com.netflix", "Breaking Bad S05E14")

        assertNotNull(result)
        assertEquals(5, result!!.matchedEpisode?.season)
        assertEquals(14, result.matchedEpisode?.number)
        coVerify(exactly = 0) { watchedShowSource.getShowHint(any()) }
    }
}
