package com.justb81.watchbuddy.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// ── Core palette (logo-inspired) ─────────────────────────────────────────────
private val Primary        = Color(0xFF1E88E5)   // Blue  – logo / banner dominant
private val Secondary      = Color(0xFFE53935)   // Red   – Trakt brand accent
private val Tertiary       = Color(0xFF00BCD4)   // Cyan  – logo neon glow
private val Background     = Color(0xFF0D0D1A)   // Dark navy – launcher background
private val Surface        = Color(0xFF1C1C1E)
private val SurfaceVariant = Color(0xFF2C2C2E)
private val OnSurface      = Color(0xFFEEEEEE)
private val ErrorColor     = Color(0xFFEF5350)

@OptIn(ExperimentalTvMaterial3Api::class)
private val TvColors = darkColorScheme(
    primary        = Primary,
    onPrimary      = Color.White,
    secondary      = Secondary,
    onSecondary    = Color.White,
    tertiary       = Tertiary,
    onTertiary     = Color.Black,
    background     = Background,
    surface        = Surface,
    surfaceVariant = SurfaceVariant,
    onBackground   = OnSurface,
    onSurface      = OnSurface,
    error          = ErrorColor,
    onError        = Color.White,
)

// ── Extended colors (no standard Material3 slot) ─────────────────────────────
@Immutable
data class TvExtendedColors(
    val outline: Color     = Color(0xFF3A3A3C),
    val traktRed: Color    = Color(0xFFE53935),
    val success: Color     = Color(0xFF4CAF50),
    val llmAiCore: Color   = Color(0xFF4CAF50),
    val llmGpu: Color      = Color(0xFF2196F3),
    val llmNone: Color     = Color(0xFF757575),
    val placeholder: Color = Color(0xFF2A2A2C),
    val scrim: Color       = Color(0xEE0D0D1A),   // background @ ~93 % alpha
)

val LocalTvExtendedColors = staticCompositionLocalOf { TvExtendedColors() }

val MaterialTheme.extendedColors: TvExtendedColors
    @Composable @ReadOnlyComposable
    get() = LocalTvExtendedColors.current

// ── CSS helper for RecapWebView ──────────────────────────────────────────────
fun Color.toCssHex(): String {
    val r = (red * 255).toInt()
    val g = (green * 255).toInt()
    val b = (blue * 255).toInt()
    return "#%02X%02X%02X".format(r, g, b)
}

// ── Theme composable ─────────────────────────────────────────────────────────
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WatchBuddyTvTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTvExtendedColors provides TvExtendedColors()) {
        MaterialTheme(
            colorScheme = TvColors,
            content     = content
        )
    }
}
