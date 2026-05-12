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
     * Both an explicit marker and a progress hint were provided and they
     * disagree. The caller should surface a confirmation prompt rather than
     * auto-scrobbling. This result is only reachable when the caller passes
     * a non-null [hint] together with a non-null [explicit] to
     * [resolveEpisodeFromMetadata] — [MediaSessionScrobbler] never does this
     * (it passes `hint = null` when [explicit] is non-null), so [Ambiguous]
     * is reserved for callers that deliberately provide both.
     */
    data object Ambiguous : EpisodeResolutionResult

    /**
     * No episode could be resolved: either the explicit marker was absent,
     * the progress hint was null or yielded no episode, or confidence was
     * too low to trust the hint.
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
 * 1. If [explicit] is present:
 *    - When [hint] is also non-null and `nextEpisodeNumbers` disagrees with
 *      [explicit], return [EpisodeResolutionResult.Ambiguous].
 *    - Otherwise return [EpisodeResolutionResult.Resolved] with
 *      [ResolveSource.EXPLICIT_MARKER].
 * 2. If [confidence] ≥ [tuning.autoScrobbleThreshold] and [hint] is non-null and
 *    yields an episode via [ShowProgressCalculator.nextEpisodeNumbers], return that
 *    episode ([ResolveSource.PROGRESS_HINT]).
 * 3. Otherwise return [EpisodeResolutionResult.Unresolved].
 *
 * When [hint] is `null`, the progress-hint path is skipped and the function
 * returns [EpisodeResolutionResult.Unresolved] unless [explicit] is present.
 * This preserves the original [MediaSessionScrobbler] contract where a null
 * hint from [WatchedShowSource.getShowHint] causes a dropped scrobble.
 *
 * **Note on [MediaSessionScrobbler] usage:** the scrobbler passes `hint = null`
 * whenever [explicit] is non-null, so [EpisodeResolutionResult.Ambiguous] is never
 * returned from the scrobbler's call path. The Ambiguous branch is exposed for
 * callers that deliberately provide both pieces of evidence.
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
    if (explicit != null) {
        if (hint != null) {
            val hintEpisode = ShowProgressCalculator.nextEpisodeNumbers(cacheEntry, hint)
            if (hintEpisode != null &&
                (hintEpisode.first != explicit.season || hintEpisode.second != explicit.episode)
            ) {
                return EpisodeResolutionResult.Ambiguous
            }
        }
        return EpisodeResolutionResult.Resolved(
            season = explicit.season,
            episode = explicit.episode,
            source = ResolveSource.EXPLICIT_MARKER,
        )
    }

    if (hint != null && confidence >= tuning.autoScrobbleThreshold) {
        val hintEpisode = ShowProgressCalculator.nextEpisodeNumbers(cacheEntry, hint)
        if (hintEpisode != null) {
            return EpisodeResolutionResult.Resolved(
                season = hintEpisode.first,
                episode = hintEpisode.second,
                source = ResolveSource.PROGRESS_HINT,
            )
        }
    }

    return EpisodeResolutionResult.Unresolved
}
