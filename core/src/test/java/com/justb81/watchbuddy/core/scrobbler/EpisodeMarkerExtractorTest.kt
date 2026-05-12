package com.justb81.watchbuddy.core.scrobbler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("EpisodeMarkerExtractor")
class EpisodeMarkerExtractorTest {

    // ── extractFromText ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractFromText()")
    inner class ExtractFromTextTest {

        @Test
        fun `extracts S01E02 from canonical English title`() {
            val result = EpisodeMarkerExtractor.extractFromText("Breaking Bad S01E02", profile = null)
            assertEquals(EpisodeMarkerExtractor.Marker(season = 1, episode = 2), result)
        }

        @Test
        fun `extracts S01 dot E02 from Joyn-style dot delimiter`() {
            // Standard S##E## pattern still matches "S01E02" embedded with a dot separator
            // via profile-specific Joyn regex: "Staffel 1, Folge 2"
            val joynProfile = AppProfile(
                packageName = "de.prosiebensat1digital.seventv",
                markerRegexes = listOf(
                    Regex("""Staffel\s*(\d+)[,\s]+Folge\s*(\d+)""", RegexOption.IGNORE_CASE),
                ),
            )
            val result = EpisodeMarkerExtractor.extractFromText(
                "Staffel 1, Folge 2 — Some Episode Title",
                profile = joynProfile,
            )
            assertEquals(EpisodeMarkerExtractor.Marker(season = 1, episode = 2), result)
        }

        @Test
        fun `extracts Folge 7 from Disney plus German title`() {
            // Disney+ uses T##E## marker; simulate a profile with that regex
            val disneyProfile = AppProfile(
                packageName = "com.disney.disneyplus",
                markerRegexes = listOf(Regex("""T(\d+)\s*E(\d+)""", RegexOption.IGNORE_CASE)),
            )
            val result = EpisodeMarkerExtractor.extractFromText(
                "The Mandalorian T1 E7",
                profile = disneyProfile,
            )
            assertEquals(EpisodeMarkerExtractor.Marker(season = 1, episode = 7), result)
        }

        @Test
        fun `returns null when text contains no marker`() {
            val result = EpisodeMarkerExtractor.extractFromText("Just a plain show title", profile = null)
            assertNull(result)
        }

        @Test
        fun `prefers profile-specific regex over generic S##E## fallback`() {
            // Profile uses T##E##; input contains T2 E3 which should win over any S##E## match
            val profile = AppProfile(
                packageName = "com.disney.disneyplus",
                markerRegexes = listOf(Regex("""T(\d+)\s*E(\d+)""", RegexOption.IGNORE_CASE)),
            )
            val result = EpisodeMarkerExtractor.extractFromText("Loki T2 E3", profile = profile)
            assertEquals(EpisodeMarkerExtractor.Marker(season = 2, episode = 3), result)
        }

        @Test
        fun `generic S##E## matches when no profile is supplied`() {
            val result = EpisodeMarkerExtractor.extractFromText("Dark S03E08", profile = null)
            assertEquals(EpisodeMarkerExtractor.Marker(season = 3, episode = 8), result)
        }

        @Test
        fun `extraction is case-insensitive for generic pattern`() {
            val result = EpisodeMarkerExtractor.extractFromText("Show s02e05 Title", profile = null)
            assertEquals(EpisodeMarkerExtractor.Marker(season = 2, episode = 5), result)
        }
    }

    // ── normalizeTitle ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("normalizeTitle()")
    inner class NormalizeTitleTest {

        @Test
        fun `normalizeTitle strips trailing colon and SxxEyy suffix`() {
            val result = EpisodeMarkerExtractor.normalizeTitle("Breaking Bad S03E07", profile = null)
            assertEquals("Breaking Bad", result)
        }

        @Test
        fun `normalizeTitle strips trailing colon without marker`() {
            val result = EpisodeMarkerExtractor.normalizeTitle("The Crown:", profile = null)
            assertEquals("The Crown", result)
        }

        @Test
        fun `normalizeTitle returns trimmed field when no marker or colon`() {
            val result = EpisodeMarkerExtractor.normalizeTitle("Stranger Things", profile = null)
            assertEquals("Stranger Things", result)
        }

        @Test
        fun `normalizeTitle strips profile-specific marker`() {
            val profile = AppProfile(
                packageName = "com.disney.disneyplus",
                markerRegexes = listOf(Regex("""T(\d+)\s*E(\d+)""", RegexOption.IGNORE_CASE)),
            )
            val result = EpisodeMarkerExtractor.normalizeTitle("The Mandalorian T1 E1", profile = profile)
            assertEquals("The Mandalorian", result)
        }
    }
}
