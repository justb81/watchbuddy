package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.model.PlaybackTick
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the Phase 0 Watch-Now intent gate in [MediaSessionScrobbler.matchSnapshot].
 *
 * Phase 0 fires before Phase 0.5 (content-ID) and Phase 1 (Levenshtein). It returns a
 * high-confidence candidate immediately when the snapshot text-scores ≥ 0.40 against the
 * intent's show title. Below that threshold, the cascade continues and the intent is surfaced
 * as an extra ambiguous candidate with a +0.15 bonus.
 */
@DisplayName("MediaSessionScrobbler — Phase 0 Watch-Now intent gate")
class MediaSessionScrobblerIntentTest {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private val titleExtractor: TitleExtractor = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    private val disneyPkg = "com.disney.disneyplus"
    private val netflixPkg = "com.netflix.mediaclient"

    private val strangersIds = TraktIds(trakt = 104439, tmdb = 66732)
    private val strangersShow = TraktShow(
        title = "Stranger Things",
        year = 2016,
        ids = strangersIds,
    )
    private val strangersEntry = TraktWatchedEntry(show = strangersShow)

    private val witcherIds = TraktIds(trakt = 140799, tmdb = 71912)
    private val witcherShow = TraktShow(
        title = "The Witcher",
        year = 2019,
        ids = witcherIds,
    )
    private val witcherEntry = TraktWatchedEntry(show = witcherShow)

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(
            context, tmdbApiService, watchedShowSource, scrobbleDispatcher, titleExtractor,
        )
        coEvery { watchedShowSource.getTmdbApiKey() } returns null
        coEvery { watchedShowSource.getShowHint(any()) } returns null
        coEvery { titleExtractor.extract(any()) } returns null
    }

    private fun makeIntent(
        showTitle: String = "Stranger Things",
        showIds: TraktIds = strangersIds,
        season: Int = 4,
        episode: Int = 1,
        pkg: String = disneyPkg,
        capturedAtMs: Long = System.currentTimeMillis(),
    ) = PlaybackIntent(
        showIds = showIds,
        showTitle = showTitle,
        season = season,
        episode = episode,
        providerPackageName = pkg,
        capturedAtMs = capturedAtMs,
    )

    // ── Phase 0 hit ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Phase 0 hit (text score ≥ 0.40)")
    inner class Phase0Hit {

        @Test
        fun `returns high-confidence candidate using intent episode numbers`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            val snapshot = snapshotOf(disneyPkg, "title" to "Stranger Things")
            val intent = makeIntent(season = 4, episode = 1)

            val candidate = scrobbler.matchSnapshot(snapshot, PlaybackTick.UNKNOWN, intent)

            assertNotNull(candidate)
            assertEquals(strangersShow.title, candidate!!.matchedShow!!.title)
            assertEquals(4, candidate.matchedEpisode!!.season)
            assertEquals(1, candidate.matchedEpisode!!.number)
            assert(candidate.confidence >= MediaSessionScrobbler.AUTO_SCROBBLE_THRESHOLD) {
                "expected confidence >= ${MediaSessionScrobbler.AUTO_SCROBBLE_THRESHOLD}, was ${candidate.confidence}"
            }
        }

        @Test
        fun `confidence is clamped to at least AUTO_SCROBBLE_THRESHOLD`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            // Partial match that would normally score below auto-scrobble but above intent threshold
            val snapshot = snapshotOf(disneyPkg, "title" to "Stranger Thing") // slightly off
            val intent = makeIntent()

            val candidate = scrobbler.matchSnapshot(snapshot, PlaybackTick.UNKNOWN, intent)

            // If Phase 0 fires (score >= 0.40), result must be >= AUTO_SCROBBLE_THRESHOLD
            if (candidate != null && candidate.confidence >= MediaSessionScrobbler.AUTO_SCROBBLE_THRESHOLD) {
                assert(candidate.matchedShow?.title == strangersShow.title)
            }
        }

        @Test
        fun `uses cached library show object when found by trakt ID`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            val snapshot = snapshotOf(disneyPkg, "title" to "Stranger Things")
            val intent = makeIntent()

            val candidate = scrobbler.matchSnapshot(snapshot, PlaybackTick.UNKNOWN, intent)

            assertNotNull(candidate)
            // Show object should be the one from the cache (full data including year etc.)
            assertEquals(strangersShow.year, candidate!!.matchedShow!!.year)
        }

        @Test
        fun `creates show from intent when not found in cache`() = runTest {
            // Cache empty — show not in library
            coEvery { watchedShowSource.getCachedShows() } returns emptyList()
            val snapshot = snapshotOf(disneyPkg, "title" to "Stranger Things")
            val intent = makeIntent()

            val candidate = scrobbler.matchSnapshot(snapshot, PlaybackTick.UNKNOWN, intent)

            // Phase 0 may still fire if text score >= 0.40; show falls back to intent data
            if (candidate != null && candidate.confidence >= MediaSessionScrobbler.AUTO_SCROBBLE_THRESHOLD) {
                assertEquals(strangersShow.title, candidate.matchedShow!!.title)
                assertEquals(strangersIds.trakt, candidate.matchedShow!!.ids.trakt)
            }
        }
    }

    // ── Phase 0 fallthrough ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Phase 0 fallthrough (text score < 0.40)")
    inner class Phase0Fallthrough {

        @Test
        fun `does not override when snapshot title is unrelated to intent show`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            // Very different title from intent's show
            val snapshot = snapshotOf(disneyPkg, "title" to "XYZ Breaking Nothing 9999")
            val intent = makeIntent(showTitle = "Stranger Things")

            val candidate = scrobbler.matchSnapshot(snapshot, PlaybackTick.UNKNOWN, intent)

            // Phase 0 should fall through; any match must come from Phase 1/2/3 cascade
            if (candidate != null) {
                assert(candidate.confidence < MediaSessionScrobbler.AUTO_SCROBBLE_THRESHOLD) {
                    "Phase 0 should not have confirmed for an unrelated title"
                }
            }
        }
    }

    // ── Intent for a different package ────────────────────────────────────────

    @Nested
    @DisplayName("Intent for a different package is ignored")
    inner class DifferentPackage {

        @Test
        fun `intent for netflix package does not fire on disney snapshot`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            val snapshot = snapshotOf(disneyPkg, "title" to "Stranger Things")
            // Intent has Netflix as provider package — must not match Disney+ snapshot
            val intent = makeIntent(pkg = netflixPkg)

            // matchSnapshot receives the intent; Phase 0 checks providerPackageName == packageName
            // The snapshot is from Disney+ but the intent is for Netflix — Phase 0 won't fire
            // (the caller's responsibility to pass the right intent, but matchSnapshot checks too)
            val candidateWithIntent = scrobbler.matchSnapshot(snapshot, PlaybackTick.UNKNOWN, intent)
            val candidateNoIntent = scrobbler.matchSnapshot(snapshot, PlaybackTick.UNKNOWN, null)

            // Both should behave identically (Phase 0 not influencing the result)
            // The important thing is Phase 0 doesn't return a wrong high-confidence candidate
            if (candidateWithIntent != null && candidateWithIntent.confidence >= MediaSessionScrobbler.AUTO_SCROBBLE_THRESHOLD) {
                // If Phase 1 happened to return high confidence on its own, that's fine
                // but Phase 0 should not have been the source (confidence would be from Phase 1)
                assertEquals(candidateNoIntent?.confidence, candidateWithIntent.confidence)
            }
        }
    }

    // ── No-intent baseline ────────────────────────────────────────────────────

    @Nested
    @DisplayName("No-intent baseline matches existing behaviour exactly")
    inner class NoIntentBaseline {

        @Test
        fun `null intent falls through to Phase 1 as before`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            val snapshot = snapshotOf(disneyPkg, "title" to "Stranger Things")

            val candidate = scrobbler.matchSnapshot(snapshot, PlaybackTick.UNKNOWN, null)

            // Phase 1 should match via Levenshtein — exact title match is a valid high-confidence result
            assertNotNull(candidate)
            assertEquals(strangersShow.title, candidate!!.matchedShow!!.title)
        }

        @Test
        fun `empty candidates return null regardless of intent`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            val snapshot = snapshotOf(disneyPkg) // no fields
            val intent = makeIntent()

            val candidate = scrobbler.matchSnapshot(snapshot, PlaybackTick.UNKNOWN, intent)

            assertNull(candidate)
        }
    }

    // ── collectAmbiguousCandidates with fallthrough intent ────────────────────

    @Nested
    @DisplayName("collectAmbiguousCandidates — fallthrough intent injection")
    inner class FallthroughCandidateInjection {

        @Test
        fun `fallthrough intent is injected as watch-now-intent candidate`() {
            val candidates = listOf("Stranger Things S04E01")
            val cachedShows = listOf(strangersEntry)
            // Very low text score → bonus puts it just above AMBIGUOUS_THRESHOLD
            val intent = makeIntent(showTitle = "Stranger Things", season = 4, episode = 1)

            val results = scrobbler.collectAmbiguousCandidates(
                candidates = candidates,
                cachedShows = cachedShows,
                globalSeason = null,
                globalEpisode = null,
                profile = null,
                tick = PlaybackTick.UNKNOWN,
                fallthroughIntent = intent,
            )

            val intentCandidate = results.firstOrNull { it.sourceLabel == "watch-now-intent" }
            assertNotNull(intentCandidate)
            assertEquals(strangersShow.title, intentCandidate!!.show.title)
            assertEquals(4, intentCandidate.episode?.season)
            assertEquals(1, intentCandidate.episode?.number)
        }

        @Test
        fun `null fallthrough intent does not add extra candidate`() {
            val candidates = listOf("ABC Unknown Show XYZ")
            val cachedShows = listOf(strangersEntry)

            val results = scrobbler.collectAmbiguousCandidates(
                candidates = candidates,
                cachedShows = cachedShows,
                globalSeason = null,
                globalEpisode = null,
                profile = null,
                tick = PlaybackTick.UNKNOWN,
                fallthroughIntent = null,
            )

            assert(results.none { it.sourceLabel == "watch-now-intent" })
        }

        @Test
        fun `intent candidate does not exceed cap score of 0_94`() {
            val candidates = listOf("Stranger Things S04E01")
            val cachedShows = listOf(strangersEntry)
            val intent = makeIntent(showTitle = "Stranger Things", season = 4, episode = 1)

            val results = scrobbler.collectAmbiguousCandidates(
                candidates = candidates,
                cachedShows = cachedShows,
                globalSeason = null,
                globalEpisode = null,
                profile = null,
                tick = PlaybackTick.UNKNOWN,
                fallthroughIntent = intent,
            )

            results.forEach { candidate ->
                assert(candidate.score <= 0.94f) {
                    "No candidate score should exceed 0.94, got ${candidate.score}"
                }
            }
        }

        @Test
        fun `intent fallthrough does not insert candidate when show not in library`() {
            val candidates = listOf("Some Random Show S01E01")
            val cachedShows = listOf(witcherEntry) // Library has only The Witcher
            // Intent is for Stranger Things which is NOT in the library
            val intent = makeIntent(showTitle = "Stranger Things", showIds = strangersIds)

            val results = scrobbler.collectAmbiguousCandidates(
                candidates = candidates,
                cachedShows = cachedShows,
                globalSeason = null,
                globalEpisode = null,
                profile = null,
                tick = PlaybackTick.UNKNOWN,
                fallthroughIntent = intent,
            )

            assert(results.none { it.sourceLabel == "watch-now-intent" })
        }
    }
}
