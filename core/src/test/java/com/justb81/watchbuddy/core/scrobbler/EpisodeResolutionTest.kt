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
        fun `explicit marker wins over progress hint`() {
            val marker = EpisodeMarkerExtractor.Marker(season = 2, episode = 5)
            val hint = TmdbProgressHint(
                nextAired = TmdbEpisodeSummary(season_number = 3, episode_number = 1),
            )
            val result = resolveEpisodeFromMetadata(
                explicit = marker,
                hint = hint,
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
        fun `ambiguous when explicit marker disagrees with high-confidence hint`() {
            // Explicit says S2E5; nextEpisodeNumbers from hint says S3E1 — they disagree.
            val marker = EpisodeMarkerExtractor.Marker(season = 2, episode = 5)
            val hint = TmdbProgressHint(
                nextAired = TmdbEpisodeSummary(season_number = 3, episode_number = 1),
            )
            // confidence >= autoScrobbleThreshold so hint is computed
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
    }

    // ── progress hint path ────────────────────────────────────────────────────

    @Nested
    @DisplayName("progress hint path")
    inner class ProgressHintTest {

        @Test
        fun `progress hint used when no explicit marker and confidence at threshold`() {
            // nextEpisodeNumbers will compute S1E4 (latest watched S1E3 + 1)
            val entry = watchedEntry(season = 1, episode = 3)
            val result = resolveEpisodeFromMetadata(
                explicit = null,
                hint = null,
                confidence = defaultTuning.autoScrobbleThreshold,
                tuning = defaultTuning,
                cacheEntry = entry,
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
        fun `unresolved when confidence below hintThreshold and no explicit marker`() {
            val result = resolveEpisodeFromMetadata(
                explicit = null,
                hint = null,
                confidence = defaultTuning.autoScrobbleThreshold - 0.01f,
                tuning = defaultTuning,
                cacheEntry = watchedEntry(),
            )
            assertEquals(EpisodeResolutionResult.Unresolved, result)
        }
    }
}
