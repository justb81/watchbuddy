package com.justb81.watchbuddy.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val Primary    = Color(0xFFE53935)
private val Background = Color(0xFF0A0A0A)
private val Surface    = Color(0xFF1C1C1E)
private val OnSurface  = Color(0xFFEEEEEE)

@OptIn(ExperimentalTvMaterial3Api::class)
private val TvColors = darkColorScheme(
    primary       = Primary,
    onPrimary     = Color.White,
    background    = Background,
    surface       = Surface,
    onBackground  = OnSurface,
    onSurface     = OnSurface,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WatchBuddyTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TvColors,
        content     = content
    )
}
