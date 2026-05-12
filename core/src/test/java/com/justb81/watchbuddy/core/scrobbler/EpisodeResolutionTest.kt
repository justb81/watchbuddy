package com.justb81.watchbuddy.core.scrobbler

import com.justb81.watchbuddy.core.model.TmdbEpisodeSummary
import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("resolveEpisodeFromMetadata()")
class EpisodeResolutionTest {

    private val defaultTuning = ScrobbleTuning()

    private fun watchedEntry(
        traktId: Int = 1,
        season: Int = 1,
        episode: Int = 3,
    ) = TraktWatchedEntry(
        show = TraktShow(title = "Test Show", ids = TraktIds(trakt = traktId)),
        seasons = listOf(
            TraktWatchedSeason(
                number = season,
                episodes = listOf(TraktWatchedEpisode(number = episode)),
            ),
        ),
    )

    // ── explicit marker wins ──────────────────────────────────────────────────

    @Nested
    @DisplayName("explicit marker behaviour")
    inner class ExplicitMarkerTest {

        @Test
        fun `explicit marker wins when no hint provided`() {
            val marker = EpisodeMarkerExtractor.Marker(season = 2, episode = 5)
            val result = resolveEpisodeFromMetadata(
                explicit = marker,
                hint = null,
                confidence = 0.98f,
                tuning = defaultTuning,
                cacheEntry = watchedEntry(),
            )
            assertEquals(
                EpisodeResolutionResult.Resolved(
                    season = 2,
                    episode = 5,
                    source = ResolveSource.EXPLICIT_MARKER,
                ),
                result,
            )
        }

        @Test
        fun `ambiguous when explicit marker disagrees with non-null hint`() {
            // Explicit says S2E5; nextEpisodeNumbers from hint says S3E1 — they disagree.
            val marker = EpisodeMarkerExtractor.Marker(season = 2, episode = 5)
            val hint = TmdbProgressHint(
                nextAired = TmdbEpisodeSummary(season_number = 3, episode_number = 1),
            )
            val result = resolveEpisodeFromMetadata(
                explicit = marker,
                hint = hint,
                confidence = defaultTuning.autoScrobbleThreshold,
                tuning = defaultTuning,
                cacheEntry = watchedEntry(season = 2, episode = 5),
            )
            assertEquals(EpisodeResolutionResult.Ambiguous, result)
        }

        @Test
        fun `explicit marker resolves without ambiguity when hint is null`() {
            val marker = EpisodeMarkerExtractor.Marker(season = 1, episode = 1)
            val result = resolveEpisodeFromMetadata(
                explicit = marker,
                hint = null,
                confidence = 0.98f,
                tuning = defaultTuning,
                cacheEntry = watchedEntry(),
            )
            assertEquals(
                EpisodeResolutionResult.Resolved(
                    season = 1,
                    episode = 1,
                    source = ResolveSource.EXPLICIT_MARKER,
                ),
                result,
            )
        }

        @Test
        fun `explicit marker resolves when hint agrees`() {
            // Explicit says S3E1; hint also points to S3E1 — no ambiguity.
            val marker = EpisodeMarkerExtractor.Marker(season = 3, episode = 1)
            val hint = TmdbProgressHint(
                nextAired = TmdbEpisodeSummary(season_number = 3, episode_number = 1),
            )
            val result = resolveEpisodeFromMetadata(
                explicit = marker,
                hint = hint,
                confidence = defaultTuning.autoScrobbleThreshold,
                tuning = defaultTuning,
                cacheEntry = watchedEntry(),
            )
            assertEquals(
                EpisodeResolutionResult.Resolved(
                    season = 3,
                    episode = 1,
                    source = ResolveSource.EXPLICIT_MARKER,
                ),
                result,
            )
        }
    }

    // ── progress hint path ────────────────────────────────────────────────────

    @Nested
    @DisplayName("progress hint path")
    inner class ProgressHintTest {

        @Test
        fun `progress hint used when no explicit marker and confidence at threshold`() {
            // hint carries nextAired = S1E4; nextEpisodeNumbers returns S1E4
            val hint = TmdbProgressHint(
                nextAired = TmdbEpisodeSummary(season_number = 1, episode_number = 4),
            )
            val result = resolveEpisodeFromMetadata(
                explicit = null,
                hint = hint,
                confidence = defaultTuning.autoScrobbleThreshold,
                tuning = defaultTuning,
                cacheEntry = watchedEntry(),
            )
            assertEquals(
                EpisodeResolutionResult.Resolved(
                    season = 1,
                    episode = 4,
                    source = ResolveSource.PROGRESS_HINT,
                ),
                result,
            )
        }

        @Test
        fun `unresolved when hint is null and no explicit marker`() {
            val result = resolveEpisodeFromMetadata(
                explicit = null,
                hint = null,
                confidence = defaultTuning.autoScrobbleThreshold,
                tuning = defaultTuning,
                cacheEntry = watchedEntry(),
            )
            assertEquals(EpisodeResolutionResult.Unresolved, result)
        }

        @Test
        fun `unresolved when confidence below threshold and no explicit marker`() {
            val hint = TmdbProgressHint(
                nextAired = TmdbEpisodeSummary(season_number = 1, episode_number = 4),
            )
            val result = resolveEpisodeFromMetadata(
                explicit = null,
                hint = hint,
                confidence = defaultTuning.autoScrobbleThreshold - 0.01f,
                tuning = defaultTuning,
                cacheEntry = watchedEntry(),
            )
            assertEquals(EpisodeResolutionResult.Unresolved, result)
        }
    }
}
