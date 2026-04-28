package com.justb81.watchbuddy.core.scrobbler

import android.content.Context
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MediaSessionScrobbler — runtimeAffinity()")
class MediaSessionScrobblerRuntimeAffinityTest {

    private val context: Context = mockk(relaxed = true)
    private val tmdbApiService: TmdbApiService = mockk()
    private val watchedShowSource: WatchedShowSource = mockk()
    private val scrobbleDispatcher: ScrobbleDispatcher = mockk()
    private lateinit var scrobbler: MediaSessionScrobbler

    @BeforeEach
    fun setUp() {
        scrobbler = MediaSessionScrobbler(context, tmdbApiService, watchedShowSource, scrobbleDispatcher, NoOpTitleExtractor)
    }

    @Nested
    @DisplayName("degenerate inputs — returns 1f")
    inner class DegenerateInputs {

        @Test
        fun `tickDurationMs zero returns 1f`() =
            assertEquals(1f, scrobbler.runtimeAffinity(tickDurationMs = 0L, candidateRuntimeMin = 60))

        @Test
        fun `tickDurationMs negative returns 1f`() =
            assertEquals(1f, scrobbler.runtimeAffinity(tickDurationMs = -1L, candidateRuntimeMin = 60))

        @Test
        fun `candidateRuntimeMin null returns 1f`() =
            assertEquals(1f, scrobbler.runtimeAffinity(tickDurationMs = 60 * 60_000L, candidateRuntimeMin = null))

        @Test
        fun `candidateRuntimeMin zero returns 1f`() =
            assertEquals(1f, scrobbler.runtimeAffinity(tickDurationMs = 60 * 60_000L, candidateRuntimeMin = 0))

        @Test
        fun `candidateRuntimeMin negative returns 1f`() =
            assertEquals(1f, scrobbler.runtimeAffinity(tickDurationMs = 60 * 60_000L, candidateRuntimeMin = -1))
    }

    @Nested
    @DisplayName("within 5 minutes delta — boosts to 1.10f (clamped)")
    inner class WithinFiveMin {

        @Test
        fun `exact match returns 1_10f clamped to cap`() {
            val result = scrobbler.runtimeAffinity(
                tickDurationMs = 45 * 60_000L,
                candidateRuntimeMin = 45
            )
            // 1.10f is allowed if it doesn't exceed 1/AUTO_SCROBBLE_THRESHOLD ≈ 1.053
            // AUTO_SCROBBLE_THRESHOLD = 0.95, so cap = 1/0.95 ≈ 1.053 → 1.10 exceeds cap, clamped
            val cap = 1.0f / MediaSessionScrobbler.AUTO_SCROBBLE_THRESHOLD
            assertTrue(result <= cap, "result $result should not exceed cap $cap")
            assertTrue(result >= 1.00f, "result $result should be >= 1.00 for near match")
        }

        @Test
        fun `4-minute delta also in close band`() {
            val result = scrobbler.runtimeAffinity(
                tickDurationMs = 49 * 60_000L,
                candidateRuntimeMin = 45
            )
            assertTrue(result >= 1.00f)
        }

        @Test
        fun `exactly 5-min delta returns boost`() {
            val result = scrobbler.runtimeAffinity(
                tickDurationMs = 50 * 60_000L,
                candidateRuntimeMin = 45
            )
            assertTrue(result >= 1.00f)
        }
    }

    @Nested
    @DisplayName("delta 6–10 minutes — neutral 1.00f")
    inner class SixToTenMin {

        @Test
        fun `6-minute delta returns 1_00f`() {
            val result = scrobbler.runtimeAffinity(
                tickDurationMs = 51 * 60_000L,
                candidateRuntimeMin = 45
            )
            assertEquals(1.00f, result, 0.001f)
        }

        @Test
        fun `10-minute delta returns 1_00f`() {
            val result = scrobbler.runtimeAffinity(
                tickDurationMs = 55 * 60_000L,
                candidateRuntimeMin = 45
            )
            assertEquals(1.00f, result, 0.001f)
        }
    }

    @Nested
    @DisplayName("delta 11–20 minutes — penalty 0.90f")
    inner class ElevenToTwentyMin {

        @Test
        fun `11-minute delta returns 0_90f`() {
            val result = scrobbler.runtimeAffinity(
                tickDurationMs = 56 * 60_000L,
                candidateRuntimeMin = 45
            )
            assertEquals(0.90f, result, 0.001f)
        }

        @Test
        fun `20-minute delta returns 0_90f`() {
            val result = scrobbler.runtimeAffinity(
                tickDurationMs = 65 * 60_000L,
                candidateRuntimeMin = 45
            )
            assertEquals(0.90f, result, 0.001f)
        }
    }

    @Nested
    @DisplayName("delta >20 minutes — heavy penalty 0.75f")
    inner class OverTwentyMin {

        @Test
        fun `21-minute delta returns 0_75f`() {
            val result = scrobbler.runtimeAffinity(
                tickDurationMs = 66 * 60_000L,
                candidateRuntimeMin = 45
            )
            assertEquals(0.75f, result, 0.001f)
        }

        @Test
        fun `large delta returns 0_75f`() {
            val result = scrobbler.runtimeAffinity(
                tickDurationMs = 120 * 60_000L,
                candidateRuntimeMin = 45
            )
            assertEquals(0.75f, result, 0.001f)
        }
    }

    @Test
    fun `result never exceeds 1 over AUTO_SCROBBLE_THRESHOLD`() {
        val cap = 1.0f / MediaSessionScrobbler.AUTO_SCROBBLE_THRESHOLD
        val result = scrobbler.runtimeAffinity(tickDurationMs = 60 * 60_000L, candidateRuntimeMin = 60)
        assertTrue(result <= cap, "result $result must not exceed cap $cap")
    }
}
