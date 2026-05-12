package com.justb81.watchbuddy.core.scrobbler

import com.justb81.watchbuddy.core.model.TmdbProgressHint
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.progress.ShowProgressCalculator

/**
 * Result of attempting to resolve a `(season, episode)` pair from scrobble
 * metadata. Pure sealed type — no side effects, no I/O.
 */
sealed interface EpisodeResolutionResult {
    /**
     * A definitive `(season, episode)` pair was resolved.
     * [source] indicates which evidence path produced the result.
     */
    data class Resolved(
        val season: Int,
        val episode: Int,
        val source: ResolveSource,
    ) : EpisodeResolutionResult

    /**
     * An explicit marker and a high-confidence progress hint disagree.
     * The caller should surface a confirmation prompt rather than
     * auto-scrobbling.
     */
    data object Ambiguous : EpisodeResolutionResult

    /**
     * No episode could be resolved: either the explicit marker was absent
     * and confidence was too low to trust the progress hint, or the
     * progress hint itself was unavailable.
     */
    data object Unresolved : EpisodeResolutionResult
}

/** Which evidence path produced a [EpisodeResolutionResult.Resolved] result. */
enum class ResolveSource {
    /** An explicit `S##E##` (or profile-specific) marker was found in the metadata. */
    EXPLICIT_MARKER,

    /** No explicit marker; episode was inferred from the show's progress hint. */
    PROGRESS_HINT,

    /** Reserved for future use — not emitted by the current implementation. */
    FALLBACK,
}

/**
 * Pure function that resolves a `(season, episode)` pair from scrobble metadata.
 *
 * Decision rules (in order):
 * 1. If [explicit] is present, use it ([ResolveSource.EXPLICIT_MARKER]).
 *    When a high-confidence [hint]-derived episode also exists and disagrees,
 *    return [EpisodeResolutionResult.Ambiguous] so the caller can prompt the user.
 * 2. If [confidence] ≥ [tuning.autoScrobbleThreshold] and [hint] yields an episode
 *    via [ShowProgressCalculator.nextEpisodeNumbers], return that episode
 *    ([ResolveSource.PROGRESS_HINT]).
 * 3. Otherwise return [EpisodeResolutionResult.Unresolved].
 *
 * The caller is responsible for fetching [hint] and for logging any diagnostics
 * at the call boundary.
 */
fun resolveEpisodeFromMetadata(
    explicit: EpisodeMarkerExtractor.Marker?,
    hint: TmdbProgressHint?,
    confidence: Float,
    tuning: ScrobbleTuning,
    cacheEntry: TraktWatchedEntry,
): EpisodeResolutionResult {
    val hintEpisode: Pair<Int, Int>? =
        if (confidence >= tuning.autoScrobbleThreshold) {
            ShowProgressCalculator.nextEpisodeNumbers(cacheEntry, hint)
        } else {
            null
        }

    if (explicit != null) {
        if (hintEpisode != null &&
            (hintEpisode.first != explicit.season || hintEpisode.second != explicit.episode)
        ) {
            return EpisodeResolutionResult.Ambiguous
        }
        return EpisodeResolutionResult.Resolved(
            season = explicit.season,
            episode = explicit.episode,
            source = ResolveSource.EXPLICIT_MARKER,
        )
    }

    if (hintEpisode != null) {
        return EpisodeResolutionResult.Resolved(
            season = hintEpisode.first,
            episode = hintEpisode.second,
            source = ResolveSource.PROGRESS_HINT,
        )
    }

    return EpisodeResolutionResult.Unresolved
}
