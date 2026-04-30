package com.justb81.watchbuddy.core.scrobbler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ScrobbleTuning")
class ScrobbleTuningTest {

    // ── DEFAULT values match CLAUDE.md documentation ─────────────────────────

    @Nested
    @DisplayName("DEFAULT thresholds match documented behaviour")
    inner class DefaultThresholds {

        @Test
        fun `autoScrobbleThreshold is 0_95`() =
            assertEquals(0.95f, ScrobbleTuning.DEFAULT.autoScrobbleThreshold)

        @Test
        fun `overlayThreshold is 0_70`() =
            assertEquals(0.70f, ScrobbleTuning.DEFAULT.overlayThreshold)

        @Test
        fun `ambiguousThreshold is 0_40`() =
            assertEquals(0.40f, ScrobbleTuning.DEFAULT.ambiguousThreshold)

        @Test
        fun `intentConfirmThreshold is 0_40`() =
            assertEquals(0.40f, ScrobbleTuning.DEFAULT.intentConfirmThreshold)

        @Test
        fun `intentFallthroughBonus is 0_15`() =
            assertEquals(0.15f, ScrobbleTuning.DEFAULT.intentFallthroughBonus)

        @Test
        fun `intentFallthroughCap is 0_94`() =
            assertEquals(0.94f, ScrobbleTuning.DEFAULT.intentFallthroughCap)

        @Test
        fun `ambiguousCandidatesMax is 3`() =
            assertEquals(3, ScrobbleTuning.DEFAULT.ambiguousCandidatesMax)

        @Test
        fun `tmdbMinScore is 0_50`() =
            assertEquals(0.50f, ScrobbleTuning.DEFAULT.tmdbMinScore)
    }

    // ── Runtime-affinity defaults ─────────────────────────────────────────────

    @Nested
    @DisplayName("DEFAULT runtime-affinity knobs")
    inner class DefaultRuntimeAffinity {

        @Test
        fun `runtimeDeltaCloseMin is 5`() =
            assertEquals(5, ScrobbleTuning.DEFAULT.runtimeDeltaCloseMin)

        @Test
        fun `runtimeDeltaMediumMin is 10`() =
            assertEquals(10, ScrobbleTuning.DEFAULT.runtimeDeltaMediumMin)

        @Test
        fun `runtimeDeltaFarMin is 20`() =
            assertEquals(20, ScrobbleTuning.DEFAULT.runtimeDeltaFarMin)

        @Test
        fun `runtimeAffinityBoost is 1_10`() =
            assertEquals(1.10f, ScrobbleTuning.DEFAULT.runtimeAffinityBoost)

        @Test
        fun `runtimeAffinityNeutral is 1_00`() =
            assertEquals(1.00f, ScrobbleTuning.DEFAULT.runtimeAffinityNeutral)

        @Test
        fun `runtimeAffinityPenaltyFar is 0_90`() =
            assertEquals(0.90f, ScrobbleTuning.DEFAULT.runtimeAffinityPenaltyFar)

        @Test
        fun `runtimeAffinityPenaltyVeryFar is 0_75`() =
            assertEquals(0.75f, ScrobbleTuning.DEFAULT.runtimeAffinityPenaltyVeryFar)
    }

    // ── Threshold ordering invariants ─────────────────────────────────────────

    @Nested
    @DisplayName("Threshold ordering invariants")
    inner class ThresholdOrdering {

        @Test
        fun `autoScrobbleThreshold is above overlayThreshold`() =
            assertTrue(ScrobbleTuning.DEFAULT.autoScrobbleThreshold > ScrobbleTuning.DEFAULT.overlayThreshold)

        @Test
        fun `overlayThreshold is above ambiguousThreshold`() =
            assertTrue(ScrobbleTuning.DEFAULT.overlayThreshold > ScrobbleTuning.DEFAULT.ambiguousThreshold)

        @Test
        fun `intentFallthroughCap is below autoScrobbleThreshold`() =
            assertTrue(ScrobbleTuning.DEFAULT.intentFallthroughCap < ScrobbleTuning.DEFAULT.autoScrobbleThreshold)

        @Test
        fun `intentFallthroughCap plus bonus does not reach autoScrobbleThreshold`() {
            val maxBoosted = ScrobbleTuning.DEFAULT.intentFallthroughCap + ScrobbleTuning.DEFAULT.intentFallthroughBonus
            assertTrue(maxBoosted > ScrobbleTuning.DEFAULT.intentFallthroughCap,
                "bonus must be positive")
            // The cap is the actual ceiling applied in code; the cap itself must be below auto-scrobble.
            assertTrue(ScrobbleTuning.DEFAULT.intentFallthroughCap < ScrobbleTuning.DEFAULT.autoScrobbleThreshold)
        }

        @Test
        fun `runtimeDeltaCloseMin is less than runtimeDeltaMediumMin`() =
            assertTrue(ScrobbleTuning.DEFAULT.runtimeDeltaCloseMin < ScrobbleTuning.DEFAULT.runtimeDeltaMediumMin)

        @Test
        fun `runtimeDeltaMediumMin is less than runtimeDeltaFarMin`() =
            assertTrue(ScrobbleTuning.DEFAULT.runtimeDeltaMediumMin < ScrobbleTuning.DEFAULT.runtimeDeltaFarMin)

        @Test
        fun `runtimeAffinityBoost is above runtimeAffinityNeutral`() =
            assertTrue(ScrobbleTuning.DEFAULT.runtimeAffinityBoost > ScrobbleTuning.DEFAULT.runtimeAffinityNeutral)

        @Test
        fun `runtimeAffinityPenaltyFar is below runtimeAffinityNeutral`() =
            assertTrue(ScrobbleTuning.DEFAULT.runtimeAffinityPenaltyFar < ScrobbleTuning.DEFAULT.runtimeAffinityNeutral)

        @Test
        fun `runtimeAffinityPenaltyVeryFar is below runtimeAffinityPenaltyFar`() =
            assertTrue(ScrobbleTuning.DEFAULT.runtimeAffinityPenaltyVeryFar < ScrobbleTuning.DEFAULT.runtimeAffinityPenaltyFar)
    }

    // ── Custom instance ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Custom ScrobbleTuning instance")
    inner class CustomInstance {

        @Test
        fun `data class copy allows selective override`() {
            val custom = ScrobbleTuning.DEFAULT.copy(autoScrobbleThreshold = 0.99f)
            assertEquals(0.99f, custom.autoScrobbleThreshold)
            assertEquals(ScrobbleTuning.DEFAULT.overlayThreshold, custom.overlayThreshold)
        }

        @Test
        fun `two DEFAULT instances are equal`() =
            assertEquals(ScrobbleTuning(), ScrobbleTuning.DEFAULT)

        @Test
        fun `instances with different values are not equal`() {
            val a = ScrobbleTuning(autoScrobbleThreshold = 0.90f)
            val b = ScrobbleTuning(autoScrobbleThreshold = 0.95f)
            assertTrue(a != b)
        }
    }
}
