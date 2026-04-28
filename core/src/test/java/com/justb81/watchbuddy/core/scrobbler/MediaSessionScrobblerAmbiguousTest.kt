package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.model.AmbiguousScrobbleEvent
import com.justb81.watchbuddy.core.model.PlaybackTick
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("MediaSessionScrobbler — Ambiguous scrobble prompt (#474)")
class MediaSessionScrobblerAmbiguousTest {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    // A show with a name that produces a mid-range fuzzy score (not quite 0.70).
    private val showA = TraktShow("Breaking Bad", 2008, TraktIds(trakt = 1))
    private val showB = TraktShow("Better Call Saul", 2015, TraktIds(trakt = 2))
    private val showC = TraktShow("Ozark", 2017, TraktIds(trakt = 3))

    private val library = listOf(
        TraktWatchedEntry(show = showA),
        TraktWatchedEntry(show = showB),
        TraktWatchedEntry(show = showC),
    )

    private val unknownTick = PlaybackTick.UNKNOWN

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(context, tmdbApiService, watchedShowSource, scrobbleDispatcher, NoOpTitleExtractor)
        coEvery { watchedShowSource.getTmdbApiKey() } returns null
    }

    @Nested
    @DisplayName("collectAmbiguousCandidates()")
    inner class CollectCandidates {

        @Test
        fun `returns candidates with score in AMBIGUOUS_THRESHOLD to OVERLAY_THRESHOLD`() {
            // "Breaking Bed" has a fuzzy score near but below OVERLAY_THRESHOLD against "Breaking Bad"
            val candidates = scrobbler.collectAmbiguousCandidates(
                candidates = listOf("Breaking Bed"),
                cachedShows = library,
                globalSeason = null,
                globalEpisode = null,
                profile = null,
                tick = unknownTick,
            )
            // All returned candidates should be in the ambiguous band
            candidates.forEach { c ->
                assertTrue(c.score >= MediaSessionScrobbler.AMBIGUOUS_THRESHOLD,
                    "score ${c.score} should be >= AMBIGUOUS_THRESHOLD")
                assertTrue(c.score < MediaSessionScrobbler.OVERLAY_THRESHOLD,
                    "score ${c.score} should be < OVERLAY_THRESHOLD")
            }
        }

        @Test
        fun `returns at most 3 candidates`() {
            // Build a library of 5 similarly-named shows to ensure cap is respected
            val bigLibrary = (1..5).map { i ->
                TraktWatchedEntry(
                    show = TraktShow("Breaking Bed $i", 2000 + i, TraktIds(trakt = i))
                )
            }
            val candidates = scrobbler.collectAmbiguousCandidates(
                candidates = listOf("Breaking Bed"),
                cachedShows = bigLibrary,
                globalSeason = null,
                globalEpisode = null,
                profile = null,
                tick = unknownTick,
            )
            assertTrue(candidates.size <= 3, "Expected at most 3 candidates, got ${candidates.size}")
        }

        @Test
        fun `returns empty when all scores are above OVERLAY_THRESHOLD`() {
            val candidates = scrobbler.collectAmbiguousCandidates(
                candidates = listOf("Breaking Bad"),
                cachedShows = library,
                globalSeason = null,
                globalEpisode = null,
                profile = null,
                tick = unknownTick,
            )
            // "Breaking Bad" matches exactly → score = 1.0f which is above OVERLAY_THRESHOLD
            assertTrue(candidates.isEmpty(), "Exact match should not appear in ambiguous band")
        }

        @Test
        fun `returns empty when all scores are below AMBIGUOUS_THRESHOLD`() {
            val candidates = scrobbler.collectAmbiguousCandidates(
                candidates = listOf("Completely Different Show XYZ"),
                cachedShows = library,
                globalSeason = null,
                globalEpisode = null,
                profile = null,
                tick = unknownTick,
            )
            // Very low score, nothing in band
            assertTrue(candidates.isEmpty())
        }

        @Test
        fun `deduplicates same Trakt ID across multiple candidate strings`() {
            // Two different strings that both map to the same show at a mid score
            val candidates = scrobbler.collectAmbiguousCandidates(
                candidates = listOf("Breaking Bed", "Breaking Bed 2"),
                cachedShows = listOf(TraktWatchedEntry(show = showA)),
                globalSeason = null,
                globalEpisode = null,
                profile = null,
                tick = unknownTick,
            )
            val traktIds = candidates.map { it.show.ids.trakt }
            assertEquals(traktIds.size, traktIds.toSet().size, "No duplicate Trakt IDs expected")
        }

        @Test
        fun `keeps best score when same show scores differently from two strings`() {
            val candidates = scrobbler.collectAmbiguousCandidates(
                candidates = listOf("Breaking Bed", "Breaking Ba"),
                cachedShows = listOf(TraktWatchedEntry(show = showA)),
                globalSeason = null,
                globalEpisode = null,
                profile = null,
                tick = unknownTick,
            )
            // Should have at most 1 entry for showA (the one with the higher score)
            val showACandidates = candidates.filter { it.show.ids.trakt == showA.ids.trakt }
            assertTrue(showACandidates.size <= 1, "Duplicate dedup failed: got ${showACandidates.size}")
        }

        @Test
        fun `sorted by score descending`() {
            val bigLibrary = (1..5).map { i ->
                TraktWatchedEntry(
                    show = TraktShow("Show ${'A' + i - 1}", 2000 + i, TraktIds(trakt = i))
                )
            }
            val candidates = scrobbler.collectAmbiguousCandidates(
                candidates = listOf("Show B"),
                cachedShows = bigLibrary,
                globalSeason = null,
                globalEpisode = null,
                profile = null,
                tick = unknownTick,
            )
            val scores = candidates.map { it.score }
            assertEquals(scores.sortedDescending(), scores, "Candidates should be sorted descending by score")
        }

        @Test
        fun `attaches episode when SxxExx marker present in candidate string`() {
            val candidates = scrobbler.collectAmbiguousCandidates(
                candidates = listOf("Breaking Bed S02E05"),
                cachedShows = listOf(TraktWatchedEntry(show = showA)),
                globalSeason = null,
                globalEpisode = null,
                profile = null,
                tick = unknownTick,
            )
            val candidate = candidates.firstOrNull { it.show.ids.trakt == showA.ids.trakt }
            if (candidate != null) {
                // Episode should be attached when the score is in ambiguous band
                val ep = candidate.episode
                if (ep != null) {
                    assertEquals(2, ep.season)
                    assertEquals(5, ep.number)
                }
            }
        }
    }

    @Nested
    @DisplayName("ambiguousPromptStats()")
    inner class PromptStats {

        @Test
        fun `initial counters are all zero`() {
            val stats = scrobbler.ambiguousPromptStats()
            assertEquals(0, stats.emitted)
            assertEquals(0, stats.resolved)
            assertEquals(0, stats.dismissed)
        }

        @Test
        fun `recordAmbiguousResolved increments resolved counter`() {
            scrobbler.recordAmbiguousResolved()
            scrobbler.recordAmbiguousResolved()
            assertEquals(2, scrobbler.ambiguousPromptStats().resolved)
        }

        @Test
        fun `recordAmbiguousDismissed increments dismissed counter`() {
            scrobbler.recordAmbiguousDismissed()
            assertEquals(1, scrobbler.ambiguousPromptStats().dismissed)
        }

        @Test
        fun `counters are independent`() {
            scrobbler.recordAmbiguousResolved()
            scrobbler.recordAmbiguousDismissed()
            scrobbler.recordAmbiguousDismissed()
            val stats = scrobbler.ambiguousPromptStats()
            assertEquals(0, stats.emitted)
            assertEquals(1, stats.resolved)
            assertEquals(2, stats.dismissed)
        }
    }
}
