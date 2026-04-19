package com.justb81.watchbuddy.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/**
 * Generated fallback avatar rendering the initials of a name on a stable,
 * name-derived background color. Used everywhere the Trakt avatar is
 * unavailable (network error, `GENERATED` source, custom-photo load failure).
 *
 * The palette is fixed to a handful of Material-derived accents tuned for
 * dark surfaces — the deterministic hash on the name guarantees the same
 * user always gets the same color across app restarts and devices.
 */
@Composable
fun InitialsAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp,
    fontSize: TextUnit = (size.value / 2.5f).sp
) {
    val initials = initialsFor(name)
    val color = colorFor(name)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/**
 * Extracts up to two leading Unicode characters, one per whitespace-separated
 * token, uppercased. "Bastian Rang" → "BR"; "couchguy" → "C".
 */
internal fun initialsFor(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    return trimmed.split(Regex("\\s+"))
        .take(2)
        .mapNotNull { it.firstOrNull()?.toString() }
        .joinToString("")
        .uppercase()
        .ifBlank { "?" }
}

/**
 * Deterministic color from a stable hash of [name]. Fixed palette so the
 * rendering looks consistent across phone ↔ TV.
 */
internal fun colorFor(name: String): Color {
    val palette = longArrayOf(
        0xFF5B8DEF, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
        0xFFFFA726, 0xFF66BB6A, 0xFFFFCA28, 0xFFEC407A
    )
    val stable = name.fold(0) { acc, c -> (acc * 31 + c.code) }
    val idx = ((stable % palette.size) + palette.size) % palette.size
    return Color(palette[idx])
}
