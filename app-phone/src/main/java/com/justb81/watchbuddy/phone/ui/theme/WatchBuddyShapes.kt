package com.justb81.watchbuddy.phone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape

internal data class WatchBuddyShapes(
    /** Large interactive cards (WatchingTvToggle, NowWatchingCard) — M3 shapes.large */
    val card: Shape,
    /** Standard surface panels (DiagnosticsBanner, ShowRowCard, ShelfCard) — M3 shapes.medium */
    val banner: Shape,
    /** Compact badge pills (BadgePill) — M3 shapes.medium */
    val pill: Shape,
    /** Image thumbnails (poster crops) — M3 shapes.small */
    val thumbnail: Shape,
)

internal val MaterialTheme.watchBuddyShapes: WatchBuddyShapes
    @Composable get() = WatchBuddyShapes(
        card = shapes.large,
        banner = shapes.medium,
        pill = shapes.medium,
        thumbnail = shapes.small,
    )
