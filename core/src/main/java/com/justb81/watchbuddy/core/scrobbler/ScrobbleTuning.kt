package com.justb81.watchbuddy.core.scrobbler

/**
 * All behavioural knobs for [MediaSessionScrobbler] in one place.
 *
 * Every threshold, bonus, penalty, and size limit that governs scrobble
 * decisions is declared here with a rationale comment. Reference this
 * class — not individual call sites — when adjusting scrobble behaviour.
 * The documented thresholds in CLAUDE.md mirror [DEFAULT] so the prose
 * and the code can never drift.
 *
 * The production singleton is [ScrobbleTuning.DEFAULT]; tests may supply
 * a custom instance to probe boundary conditions without patching fields.
 */
data class ScrobbleTuning(

    /**
     * Minimum confidence for an automatic scrobble with no user confirmation.
     * Above this threshold the TV dispatches `POST /scrobble/start` immediately.
     *
     * Chosen at 0.95 so only near-certain title matches fire automatically.
     * Scores in [overlayThreshold, autoScrobbleThreshold) surface the
     * confirmation overlay instead.
     */
    val autoScrobbleThreshold: Float = 0.95f,

    /**
     * Minimum confidence to surface the overlay confirmation UI.
     *
     * Scores in [overlayThreshold, autoScrobbleThreshold) ask the user to
     * confirm before recording the episode on Trakt. The 0.70 floor keeps
     * the overlay from firing on weak partial matches while still catching
     * the common case where the show name is correct but the episode is
     * ambiguous.
     */
    val overlayThreshold: Float = 0.70f,

    /**
     * Minimum confidence to emit an ambiguous-scrobble picker prompt.
     *
     * Scores in [ambiguousThreshold, overlayThreshold) are surfaced as an
     * [com.justb81.watchbuddy.core.model.AmbiguousScrobbleEvent] with up to
     * [ambiguousCandidatesMax] choices so the user can pick manually.
     * Scores below this are silently ignored — too uncertain to be useful.
     */
    val ambiguousThreshold: Float = 0.40f,

    /**
     * Minimum text-match score for a Watch-Now intent to be confirmed in Phase 0.
     *
     * Matched against the [com.justb81.watchbuddy.core.model.MediaMetadataSnapshot]
     * fields using [MediaSessionScrobbler.fuzzyScore]. Below this the Phase 0
     * gate falls through to the full match cascade (Phases 1–3); the intent
     * is still offered as a candidate in the ambiguous picker via
     * [intentFallthroughBonus].
     */
    val intentConfirmThreshold: Float = 0.40f,

    /**
     * Score bonus applied to a Phase-0 fallthrough intent candidate when it
     * appears in the ambiguous-picker list.
     *
     * Nudges the Watch-Now hint above purely fuzzy matches so the user sees
     * it prominently. Capped by [intentFallthroughCap] to prevent it from
     * crossing [autoScrobbleThreshold] and firing silently.
     */
    val intentFallthroughBonus: Float = 0.15f,

    /**
     * Maximum confidence a fallthrough-intent candidate may receive after
     * applying [intentFallthroughBonus].
     *
     * Kept one tick below [autoScrobbleThreshold] (0.94 vs 0.95) so Watch-Now
     * hints always surface in the overlay rather than auto-scrobbling — the
     * user launched a specific app which is a strong signal but not infallible.
     */
    val intentFallthroughCap: Float = 0.94f,

    /**
     * Maximum number of entries shown in the ambiguous-scrobble picker.
     *
     * Three candidates balance usefulness (more choices is better) against
     * the screen real-estate available on a TV remote-driven interface where
     * each entry is a full card.
     */
    val ambiguousCandidatesMax: Int = 3,

    /**
     * Runtime delta (minutes) within which the MediaSession duration is
     * considered a "close" match — earns a [runtimeAffinityBoost] multiplier.
     *
     * Episodes and their on-device durations rarely differ by more than
     * 5 minutes after accounting for cold-open recaps and credit rolls.
     */
    val runtimeDeltaCloseMin: Int = 5,

    /**
     * Runtime delta (minutes) within which the duration is "medium" —
     * neither boosted nor penalised ([runtimeAffinityNeutral]).
     *
     * 6–10 minutes of divergence is plausible (extended versions, ads) so
     * the score is left unchanged to avoid false negatives.
     */
    val runtimeDeltaMediumMin: Int = 10,

    /**
     * Runtime delta (minutes) above which the duration is "far" — earns
     * [runtimeAffinityPenaltyFar]. Deltas larger than this receive
     * [runtimeAffinityPenaltyVeryFar].
     *
     * A 20-minute gap is a strong signal the candidate runtime doesn't match
     * (e.g. a 45-min drama matched against a 22-min sitcom), so confidence
     * is docked to prefer the right entry in a multi-show library.
     */
    val runtimeDeltaFarMin: Int = 20,

    /**
     * Confidence multiplier applied when the episode runtime closely matches
     * the MediaSession duration (delta ≤ [runtimeDeltaCloseMin]).
     *
     * A 1.10× boost lets a correct runtime break ties in favour of the
     * well-matched library entry. The multiplier is capped at
     * `1 / autoScrobbleThreshold` inside [MediaSessionScrobbler.runtimeAffinity]
     * so a borderline text score can never reach auto-scrobble territory
     * purely from a runtime win.
     */
    val runtimeAffinityBoost: Float = 1.10f,

    /**
     * Neutral multiplier — applied when the runtime difference is in the
     * medium range (delta in (runtimeDeltaCloseMin, runtimeDeltaMediumMin]).
     * Leaves the fuzzy score unchanged.
     */
    val runtimeAffinityNeutral: Float = 1.00f,

    /**
     * Penalty multiplier for a "far" runtime mismatch
     * (delta in (runtimeDeltaMediumMin, runtimeDeltaFarMin]).
     *
     * Reduces confidence slightly to prefer closer matches when the library
     * contains multiple candidates for the same title (e.g. original + remake).
     */
    val runtimeAffinityPenaltyFar: Float = 0.90f,

    /**
     * Penalty multiplier for a "very far" runtime mismatch
     * (delta > [runtimeDeltaFarMin]).
     *
     * Substantially reduces confidence so a two-hour movie can't accidentally
     * match a 45-minute episode of the same franchise title.
     */
    val runtimeAffinityPenaltyVeryFar: Float = 0.75f,

    /**
     * Minimum TMDB search-result score to accept a Phase-3 result as a valid
     * match. Results below this threshold are treated as noise and discarded.
     *
     * 0.50 is deliberately low because TMDB is the last resort — a half-decent
     * title match from TMDB is still better than no result at all. The
     * overlay/auto thresholds still gate what happens next.
     */
    val tmdbMinScore: Float = 0.50f,
) {
    companion object {
        /**
         * Production defaults — the values documented in CLAUDE.md under
         * "auto ≥ 95 %, prompt 70–95 %, ignore < 70 %". Update this comment
         * whenever [ScrobbleTuning] field defaults change.
         */
        val DEFAULT = ScrobbleTuning()
    }
}
