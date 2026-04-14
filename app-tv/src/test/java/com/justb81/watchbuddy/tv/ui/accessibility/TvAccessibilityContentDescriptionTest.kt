package com.justb81.watchbuddy.tv.ui.accessibility

import com.justb81.watchbuddy.tv.ui.home.showCardContentDescription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TV accessibility content descriptions")
class TvAccessibilityContentDescriptionTest {

    @Nested
    @DisplayName("showCardContentDescription")
    inner class ShowCardContentDescriptionTest {

        @Test
        fun `includes show title and S##E## when season and episode are known`() {
            val result = showCardContentDescription("Breaking Bad", 1, 5)
            assertTrue(result.contains("Breaking Bad"), "should contain show title")
            assertTrue(result.contains("S01E05"), "should contain padded season-episode")
        }

        @Test
        fun `pads single-digit season and episode numbers with leading zero`() {
            val result = showCardContentDescription("My Show", 2, 3)
            assertTrue(result.contains("S02E03"), "single-digit values must be zero-padded")
        }

        @Test
        fun `does not pad double-digit season and episode numbers`() {
            val result = showCardContentDescription("Long Show", 12, 10)
            assertTrue(result.contains("S12E10"), "double-digit values must not be over-padded")
        }

        @Test
        fun `returns only show title when season is null`() {
            val result = showCardContentDescription("New Show", lastSeasonNumber = null, lastEpisodeNumber = null)
            assertEquals("New Show", result)
        }

        @Test
        fun `returns only show title when episode is null but season is set`() {
            // Should not include partial info
            val result = showCardContentDescription("Another Show", lastSeasonNumber = 1, lastEpisodeNumber = null)
            assertEquals("Another Show", result)
        }

        @Test
        fun `returns only show title when season is null but episode is set`() {
            val result = showCardContentDescription("Edge Case Show", lastSeasonNumber = null, lastEpisodeNumber = 1)
            assertEquals("Edge Case Show", result)
        }

        @Test
        fun `handles show title with special characters`() {
            val result = showCardContentDescription("It's Always Sunny in Philadelphia", 8, 1)
            assertTrue(result.contains("It's Always Sunny in Philadelphia"))
            assertTrue(result.contains("S08E01"))
        }

        @Test
        fun `season 10 episode 1 renders as S10E01`() {
            val result = showCardContentDescription("Friends", 10, 1)
            assertTrue(result.contains("S10E01"))
            assertFalse(result.contains("S010"), "season should not have three digits")
        }

        @Test
        fun `content description starts with show title`() {
            val result = showCardContentDescription("The Wire", 3, 7)
            assertTrue(result.startsWith("The Wire"), "title should come first")
        }
    }
}
