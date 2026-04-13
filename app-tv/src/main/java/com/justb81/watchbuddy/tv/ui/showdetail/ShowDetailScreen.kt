package com.justb81.watchbuddy.tv.ui.showdetail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.*
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.TraktWatchedEntry

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShowDetailScreen(
    entry: TraktWatchedEntry,
    onRecapClick: () -> Unit,
    onBack: () -> Unit,
    viewModel: ShowDetailViewModel = hiltViewModel()
) {
    val context      = LocalContext.current
    val lastSeason   = entry.seasons.maxByOrNull { it.number }
    val lastEpisode  = lastSeason?.episodes?.maxByOrNull { it.number }
    val nextSeason   = lastSeason?.number ?: 1
    val nextEpisode  = (lastEpisode?.number ?: 0) + 1

    val services by viewModel.availableServices.collectAsState(initial = emptyList())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Background poster area (left 40%)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.4f)
                .background(MaterialTheme.colorScheme.surface)
                .align(Alignment.CenterStart)
        )

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colors      = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
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
                    stringResource(R.string.tv_next_episode)
                ),
                fontSize   = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color      = Color.White
            )

            Spacer(Modifier.height(8.dp))

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {

                // Watch Now — deep link using preferred service
                Button(
                    onClick = {
                        val deepLink = viewModel.resolveDeepLink(entry, services)
                        if (deepLink != null) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.primary
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
                    Text(stringResource(R.string.tv_back))
                }
            }

            // Streaming service list — only subscribed services (fallback: all)
            if (services.isNotEmpty()) {
                Text(
                    text     = stringResource(R.string.tv_available_at),
                    fontSize = 12.sp,
                    color    = Color.White.copy(alpha = 0.4f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    services.take(4).forEach { service ->
                        MetaChip(service.name, color = MaterialTheme.colorScheme.surface)
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
            Text(stringResource(R.string.tv_back_arrow))
        }
    }
}

@Composable
private fun MetaChip(text: String, color: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color)
    ) {
        Text(
            text     = text,
            fontSize = 12.sp,
            color    = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
