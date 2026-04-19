package com.justb81.watchbuddy.tv.ui.components

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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/**
 * Deterministic initials avatar — mirrors the phone-side [com.justb81.watchbuddy.phone.ui.components.InitialsAvatar]
 * so phone and TV render the same color + initials for the same name.
 * Used as fallback for failed Coil loads and as the primary render for
 * [com.justb81.watchbuddy.core.model.AvatarSource.GENERATED].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun InitialsAvatar(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
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
            color = Color.White
        )
    }
}

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

internal fun colorFor(name: String): Color {
    val palette = longArrayOf(
        0xFF5B8DEF, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
        0xFFFFA726, 0xFF66BB6A, 0xFFFFCA28, 0xFFEC407A
    )
    val stable = name.fold(0) { acc, c -> (acc * 31 + c.code) }
    val idx = ((stable % palette.size) + palette.size) % palette.size
    return Color(palette[idx])
}
