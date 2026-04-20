package com.justb81.watchbuddy.phone.ui.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Suppress("MagicNumber")
@DisplayName("RelativeDateFormatter")
class RelativeDateFormatterTest {

    private val now = Instant.parse("2026-04-20T12:00:00Z").toEpochMilli()

    // Fake relativeSpanFn that records the call so tests know when the 2–7 day branch is hit.
    private fun fakeSpan(ms: Long, @Suppress("UNUSED_PARAMETER") n: Long) = "relative:$ms"

    @Nested
    @DisplayName("formatRelativeTime")
    inner class FormatRelativeTimeTest {

        @Test
        fun `returns todayStr when moment is exactly now`() {
            val moment = Instant.ofEpochMilli(now)
            assertEquals(
                "today",
                formatRelativeTime(now, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            )
        }

        @Test
        fun `returns todayStr when moment is 23 hours ago`() {
            val momentMs = now - 23 * 60 * 60 * 1000L
            assertEquals(
                "today",
                formatRelativeTime(momentMs, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            )
        }

        @Test
        fun `returns todayStr when moment is 23 hours in the future`() {
            val momentMs = now + 23 * 60 * 60 * 1000L
            assertEquals(
                "today",
                formatRelativeTime(momentMs, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            )
        }

        @Test
        fun `returns yesterdayStr when moment is 25 hours ago`() {
            val momentMs = now - 25 * 60 * 60 * 1000L
            assertEquals(
                "yesterday",
                formatRelativeTime(momentMs, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            )
        }

        @Test
        fun `returns tomorrowStr when moment is 25 hours in the future`() {
            val momentMs = now + 25 * 60 * 60 * 1000L
            assertEquals(
                "tomorrow",
                formatRelativeTime(momentMs, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            )
        }

        @Test
        fun `delegates to relativeSpanFn for moment 3 days ago`() {
            val momentMs = now - 3 * DAY_MS
            val result = formatRelativeTime(momentMs, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            assertEquals("relative:$momentMs", result)
        }

        @Test
        fun `delegates to relativeSpanFn for moment 6 days ago`() {
            val momentMs = now - 6 * DAY_MS
            val result = formatRelativeTime(momentMs, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            assertEquals("relative:$momentMs", result)
        }

        @Test
        fun `returns short absolute date for moment 8 days ago`() {
            val momentMs = now - 8 * DAY_MS
            val result = formatRelativeTime(momentMs, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            val expectedDate = Instant.ofEpochMilli(momentMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            assertEquals(expectedDate, result)
        }

        @Test
        fun `returns short absolute date for moment 30 days ago`() {
            val momentMs = now - 30 * DAY_MS
            val result = formatRelativeTime(momentMs, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            val expectedDate = Instant.ofEpochMilli(momentMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            assertEquals(expectedDate, result)
            assertFalse(result.contains("relative"), "Should not delegate to relativeSpanFn for >7 days")
        }

        @Test
        fun `returns short absolute date for moment far in the future`() {
            val momentMs = now + 14 * DAY_MS
            val result = formatRelativeTime(momentMs, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            val expectedDate = Instant.ofEpochMilli(momentMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            assertEquals(expectedDate, result)
        }
    }

    @Nested
    @DisplayName("formatRelativeDate")
    inner class FormatRelativeDateTest {

        private val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        private val todayMidnight = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
        private val yesterdayMidnight = today.minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        private val tomorrowMidnight = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

        @Test
        fun `returns todayStr for today midnight`() {
            assertEquals(
                "today",
                formatRelativeDate(todayMidnight, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            )
        }

        @Test
        fun `returns yesterdayStr for yesterday midnight`() {
            assertEquals(
                "yesterday",
                formatRelativeDate(yesterdayMidnight, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            )
        }

        @Test
        fun `returns tomorrowStr for tomorrow midnight`() {
            assertEquals(
                "tomorrow",
                formatRelativeDate(tomorrowMidnight, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            )
        }

        @Test
        fun `delegates to relativeSpanFn for 3 days ago`() {
            val moment = today.minusDays(3).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val result = formatRelativeDate(moment, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            assertEquals("relative:${moment.toEpochMilli()}", result)
        }

        @Test
        fun `returns short absolute date for 10 days ago`() {
            val moment = today.minusDays(10).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val day = today.minusDays(10)
            val expected = day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            assertEquals(
                expected,
                formatRelativeDate(moment, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            )
        }

        @Test
        fun `returns short absolute date for 8 days in the future`() {
            val moment = today.plusDays(8).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val day = today.plusDays(8)
            val expected = day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            assertEquals(
                expected,
                formatRelativeDate(moment, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            )
        }

        @Test
        fun `does not surface specials-only as today when air date is today`() {
            val result = formatRelativeDate(todayMidnight, now, "today", "yesterday", "tomorrow", ::fakeSpan)
            assertEquals("today", result)
        }
    }
}
