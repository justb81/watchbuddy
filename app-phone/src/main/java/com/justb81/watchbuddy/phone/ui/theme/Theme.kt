package com.justb81.watchbuddy.phone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Primary    = Color(0xFFE53935)   // Trakt-rot
private val Secondary  = Color(0xFF1E88E5)   // Blau
private val Background = Color(0xFF0A0A0A)
private val Surface    = Color(0xFF1C1C1E)
private val OnSurface  = Color(0xFFEEEEEE)
private val OnPrimary  = Color.White

private val WatchBuddyColors = darkColorScheme(
    primary         = Primary,
    onPrimary       = OnPrimary,
    secondary       = Secondary,
    background      = Background,
    surface         = Surface,
    onBackground    = OnSurface,
    onSurface       = OnSurface,
    surfaceVariant  = Color(0xFF2C2C2E),
    outline         = Color(0xFF3A3A3C),
)

@Composable
fun WatchBuddyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WatchBuddyColors,
        content     = content
    )
}
