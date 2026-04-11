package com.justb81.watchbuddy.tv.ui.showdetail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.KNOWN_STREAMING_SERVICES
import com.justb81.watchbuddy.core.model.TraktWatchedEntry

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShowDetailScreen(
    entry: TraktWatchedEntry,
    onRecapClick: () -> Unit,
    onBack: () -> Unit
) {
    val context      = LocalContext.current
    val lastSeason   = entry.seasons.maxByOrNull { it.number }
    val lastEpisode  = lastSeason?.episodes?.maxByOrNull { it.number }
    val nextSeason   = lastSeason?.number ?: 1
    val nextEpisode  = (lastEpisode?.number ?: 0) + 1
    val tmdbId       = entry.show.ids.tmdb

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        // Background poster area (left 40%)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.4f)
                .background(Color(0xFF1C1C1E))
                .align(Alignment.CenterStart)
        )

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colors      = listOf(Color.Transparent, Color(0xFF0A0A0A)),
                        startX      = 400f,
                        endX        = 900f
                    )
                )
        )

        // Content (right side)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.55f)
                .padding(end = 64.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Show title
            Text(
                text       = entry.show.title,
                fontSize   = 40.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White
            )

            // Year + stats
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                entry.show.year?.let {
                    MetaChip(it.toString())
                }
                MetaChip(
                    stringResource(
                        R.string.tv_watched_episodes,
                        entry.seasons.sumOf { it.episodes.size }
                    )
                )
            }

            Spacer(Modifier.height(8.dp))

            // Next episode label
            Text(
                text     = stringResource(R.string.tv_next_episode),
                fontSize = 14.sp,
                color    = Color.White.copy(alpha = 0.5f)
            )
            Text(
                text       = stringResource(
                    R.string.tv_episode_label,
                    nextSeason,
                    nextEpisode,
                    "Nächste Folge"
                ),
                fontSize   = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color      = Color.White
            )

            Spacer(Modifier.height(8.dp))

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {

                // Watch Now — deep link
                Button(
                    onClick = {
                        val deepLink = resolveDeepLink(entry, nextSeason, nextEpisode)
                        if (deepLink != null) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0xFFE53935)
                    )
                ) {
                    Text(
                        text       = stringResource(R.string.tv_watch_now),
                        fontWeight = FontWeight.Bold
                    )
                }

                // Recap button
                OutlinedButton(onClick = onRecapClick) {
                    Text(stringResource(R.string.tv_recap))
                }

                // Back
                OutlinedButton(onClick = onBack) {
                    Text("Zurück")
                }
            }

            // Streaming service list
            if (KNOWN_STREAMING_SERVICES.isNotEmpty()) {
                Text(
                    text     = "Verfügbar bei:",
                    fontSize = 12.sp,
                    color    = Color.White.copy(alpha = 0.4f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // TODO: resolve actual availability via JustWatch / user mapping
                    KNOWN_STREAMING_SERVICES.take(4).forEach { service ->
                        MetaChip(service.name, color = Color(0xFF1C1C1E))
                    }
                }
            }
        }

        // Back button top-left
        OutlinedButton(
            onClick  = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(32.dp)
        ) {
            Text("‹ Zurück")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaChip(text: String, color: Color = Color(0xFF2C2C2E)) {
    Surface(
        shape  = RoundedCornerShape(20.dp),
        colors = NonInteractiveSurfaceDefaults.colors(containerColor = color)
    ) {
        Text(
            text     = text,
            fontSize = 12.sp,
            color    = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Resolves the best available deep link for a show.
 * TODO: replace with user-configured streaming service mapping.
 */
private fun resolveDeepLink(
    entry: TraktWatchedEntry,
    season: Int,
    episode: Int
): String? {
    val tmdbId = entry.show.ids.tmdb ?: return null
    // Default to Netflix — real implementation checks user's subscriptions
    return "https://www.netflix.com/title/$tmdbId"
}
