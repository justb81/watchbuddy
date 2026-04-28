package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the Phase 0.5 content-ID short-circuit in [MediaSessionScrobbler.matchSnapshot].
 *
 * Phase 0.5 fires before Phase 1 (Levenshtein) and returns a confidence-1.0 candidate
 * immediately when the snapshot contains a `watchNext.contentId: tmdb:XXXX` line whose
 * TMDB ID is found in the user's cached library — no LLM, no TMDB search.
 */
@DisplayName("MediaSessionScrobbler — Phase 0.5 content-ID short-circuit")
class MediaSessionScrobblerPhase05Test {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private val titleExtractor: TitleExtractor = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    private val strangersShow = TraktShow(
        title = "Stranger Things",
        year = 2016,
        ids = TraktIds(trakt = 104439, tmdb = 66732),
    )
    private val strangersEntry = TraktWatchedEntry(show = strangersShow)

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(
            context, tmdbApiService, watchedShowSource, scrobbleDispatcher, titleExtractor,
        )
        coEvery { watchedShowSource.getTmdbApiKey() } returns null
        coEvery { watchedShowSource.getShowHint(any()) } returns null
        coEvery { titleExtractor.extract(any()) } returns null
    }

    // ── tmdb: prefix ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("tmdb: content ID")
    inner class TmdbContentId {

        @Test
        fun `returns confidence-1_0 candidate when tmdb-id matches cached show`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            val snapshot = buildWatchNextSnapshot(
                packageName = "com.disney.disneyplus",
                title = "Stranger Things",
                season = "4",
                episode = "1",
                contentId = "tmdb:66732",
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals(strangersShow, result!!.matchedShow)
            assertEquals(1.0f, result.confidence)
            assertEquals(4, result.matchedEpisode?.season)
            assertEquals(1, result.matchedEpisode?.number)
        }

        @Test
        fun `skips LLM and TMDB search on tmdb-id hit`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            val snapshot = buildWatchNextSnapshot(
                packageName = "com.disney.disneyplus",
                title = "Stranger Things",
                season = "4",
                episode = "1",
                contentId = "tmdb:66732",
            )

            scrobbler.matchSnapshot(snapshot)

            coVerify(exactly = 0) { titleExtractor.extract(any()) }
            coVerify(exactly = 0) { tmdbApiService.searchTv(any(), any()) }
        }

        @Test
        fun `returns candidate with null episode when season or episode is missing`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            val snapshot = buildWatchNextSnapshot(
                packageName = "com.disney.disneyplus",
                title = "Stranger Things",
                season = null,
                episode = null,
                contentId = "tmdb:66732",
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals(strangersShow, result!!.matchedShow)
            assertEquals(1.0f, result.confidence)
            assertNull(result.matchedEpisode)
        }

        @Test
        fun `falls through to Phase 1 when tmdb-id is not in the cached library`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            val snapshot = buildWatchNextSnapshot(
                packageName = "com.disney.disneyplus",
                title = "The Bear",
                season = "1",
                episode = "1",
                contentId = "tmdb:99999", // unknown ID
            )

            // Phase 1 won't match either (no "The Bear" in cache) — result is null
            val result = scrobbler.matchSnapshot(snapshot)

            // Phase 0.5 returned null; Phase 1 should have run but produced no match
            coVerify(exactly = 1) { watchedShowSource.getCachedShows() }
            assertNull(result)
        }

        @Test
        fun `falls through to Phase 1 when cache is empty`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns emptyList()
            val snapshot = buildWatchNextSnapshot(
                packageName = "com.disney.disneyplus",
                title = "Stranger Things",
                season = "4",
                episode = "1",
                contentId = "tmdb:66732",
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNull(result)
        }
    }

    // ── other / no content ID ─────────────────────────────────────────────────

    @Nested
    @DisplayName("no or non-tmdb content ID")
    inner class NoContentId {

        @Test
        fun `falls through to Phase 1 when contentId is absent`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            val snapshot = buildWatchNextSnapshot(
                packageName = "com.netflix.ninja",
                title = "Stranger Things",
                season = "4",
                episode = "2",
                contentId = null,
            )

            val result = scrobbler.matchSnapshot(snapshot)

            // Phase 1 resolves via watchNext.title matching the cached show
            assertNotNull(result)
            assertEquals(strangersShow, result!!.matchedShow)
            // Phase 0.5 did not fire (no contentId), but Phase 1 succeeds via title
        }

        @Test
        fun `falls through to Phase 1 when contentId has an opaque prefix`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            val snapshot = buildWatchNextSnapshot(
                packageName = "com.netflix.ninja",
                title = "Stranger Things",
                season = "4",
                episode = "2",
                contentId = "netflix:81231234", // opaque Netflix ID — not trusted
            )

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals(strangersShow, result!!.matchedShow)
        }
    }

    // ── enricher integration ──────────────────────────────────────────────────

    @Nested
    @DisplayName("enricher integration — watchNext lines in snapshot")
    inner class EnricherIntegration {

        @Test
        fun `snapshot built by WatchNextMetadataSource enricher triggers Phase 0_5`() = runTest {
            coEvery { watchedShowSource.getCachedShows() } returns listOf(strangersEntry)
            // Simulate what WatchNextMetadataSource would add to the builder
            val builder = MediaSnapshotBuilder("com.disney.disneyplus")
            builder.add("watchNext.title", "Stranger Things")
            builder.add("watchNext.season", "4")
            builder.add("watchNext.episode", "1")
            builder.add("watchNext.episodeTitle", "Chapter One: The Hellfire Club")
            builder.add("watchNext.contentId", "tmdb:66732")
            builder.add("watchNext.marker", "S04E01")
            builder.addSource("watchNext")
            val snapshot = builder.build()

            val result = scrobbler.matchSnapshot(snapshot)

            assertNotNull(result)
            assertEquals(strangersShow, result!!.matchedShow)
            assertEquals(1.0f, result.confidence)
            assertEquals(4, result.matchedEpisode?.season)
            assertEquals(1, result.matchedEpisode?.number)
            coVerify(exactly = 0) { titleExtractor.extract(any()) }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun buildWatchNextSnapshot(
        packageName: String,
        title: String?,
        season: String?,
        episode: String?,
        contentId: String?,
    ): com.justb81.watchbuddy.core.model.MediaMetadataSnapshot {
        val builder = MediaSnapshotBuilder(packageName)
        title?.let { builder.add("watchNext.title", it) }
        season?.let { builder.add("watchNext.season", it) }
        episode?.let { builder.add("watchNext.episode", it) }
        contentId?.let { builder.add("watchNext.contentId", it) }
        return builder.build()
    }
}
