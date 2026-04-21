package com.justb81.watchbuddy.phone.ui.util

import android.content.Context
import android.text.format.DateUtils
import com.justb81.watchbuddy.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal const val DAY_MS = 24L * 60 * 60 * 1000
internal const val WEEK_MS = 7 * DAY_MS
private const val TWO_DAYS_MS = 2 * DAY_MS

private val shortDateFormatter get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/**
 * Pure branching logic for [relativeTime], extracted to allow unit testing without an Android
 * Context or DateUtils stubs.
 *
 * [relativeSpanFn] is injected so tests can supply a fake; the default calls DateUtils.
 */
internal fun formatRelativeTime(
    momentMs: Long,
    now: Long,
    todayStr: String,
    yesterdayStr: String,
    tomorrowStr: String,
    relativeSpanFn: (Long, Long) -> String = { ms, n ->
        DateUtils.getRelativeTimeSpanString(
            ms, n, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    },
): String {
    val delta = momentMs - now
    return when {
        delta in -DAY_MS..DAY_MS -> todayStr
        delta in -TWO_DAYS_MS..-DAY_MS -> yesterdayStr
        delta in DAY_MS..TWO_DAYS_MS -> tomorrowStr
        abs(delta) > WEEK_MS -> Instant.ofEpochMilli(momentMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(shortDateFormatter)
        else -> relativeSpanFn(momentMs, now)
    }
}

/**
 * Pure branching logic for [relativeDate], comparing at day-boundary precision.
 */
internal fun formatRelativeDate(
    moment: Instant,
    now: Long,
    todayStr: String,
    yesterdayStr: String,
    tomorrowStr: String,
    relativeSpanFn: (Long, Long) -> String = { ms, n ->
        DateUtils.getRelativeTimeSpanString(
            ms, n, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    },
): String {
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val day = moment.atZone(ZoneId.systemDefault()).toLocalDate()
    val delta = moment.toEpochMilli() - now
    return when {
        day.isEqual(today) -> todayStr
        day.isEqual(today.minusDays(1)) -> yesterdayStr
        day.isEqual(today.plusDays(1)) -> tomorrowStr
        abs(delta) > WEEK_MS -> day.format(shortDateFormatter)
        else -> relativeSpanFn(moment.toEpochMilli(), now)
    }
}

/**
 * Formats a moment in time relative to [now]. Returns a localized "today / yesterday / tomorrow"
 * string within ±1 day, a relative span ("3 days ago") within ±7 days, or a short absolute date
 * ("12 Mar") for anything older or further in the future.
 */
fun relativeTime(context: Context, moment: Instant, now: Long): String =
    formatRelativeTime(
        momentMs = moment.toEpochMilli(),
        now = now,
        todayStr = context.getString(R.string.home_time_today),
        yesterdayStr = context.getString(R.string.home_time_yesterday),
        tomorrowStr = context.getString(R.string.home_time_tomorrow),
    )

/**
 * Same as [relativeTime] but for TMDB `air_date` (day-precision) values interpreted at local
 * midnight to avoid "in 0 days" when the value is today.
 */
fun relativeDate(context: Context, moment: Instant, now: Long): String =
    formatRelativeDate(
        moment = moment,
        now = now,
        todayStr = context.getString(R.string.home_time_today),
        yesterdayStr = context.getString(R.string.home_time_yesterday),
        tomorrowStr = context.getString(R.string.home_time_tomorrow),
    )
