package com.justb81.watchbuddy.phone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Primary        = Color(0xFF1E88E5)   // Blue – logo / banner dominant
private val Secondary      = Color(0xFFE53935)   // Red – Trakt brand accent
private val Tertiary       = Color(0xFF00BCD4)   // Cyan – logo neon glow
private val Background     = Color(0xFF0D0D1A)   // Dark navy – launcher background
private val Surface        = Color(0xFF1C1C1E)
private val SurfaceVariant = Color(0xFF2C2C2E)
private val OnSurface      = Color(0xFFEEEEEE)
private val OnPrimary      = Color.White
private val ErrorColor     = Color(0xFFEF5350)
private val Outline        = Color(0xFF3A3A3C)

private val WatchBuddyColors = darkColorScheme(
    primary         = Primary,
    onPrimary       = OnPrimary,
    secondary       = Secondary,
    onSecondary     = Color.White,
    tertiary        = Tertiary,
    onTertiary      = Color.Black,
    background      = Background,
    surface         = Surface,
    surfaceVariant  = SurfaceVariant,
    onBackground    = OnSurface,
    onSurface       = OnSurface,
    error           = ErrorColor,
    onError         = Color.White,
    outline         = Outline,
)

@Composable
fun WatchBuddyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WatchBuddyColors,
        content     = content
    )
}
