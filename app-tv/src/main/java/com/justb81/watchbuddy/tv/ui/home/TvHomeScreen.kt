package com.justb81.watchbuddy.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.TraktWatchedEntry

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeScreen(
    onShowClick: (TraktWatchedEntry) -> Unit,
    onUserSelectClick: () -> Unit,
    viewModel: TvHomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text       = stringResource(R.string.tv_home_title),
                    fontSize   = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.primary
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Phone status badge
                    if (uiState.connectedPhones > 0) {
                        PhoneStatusBadge(
                            count   = uiState.connectedPhones,
                            bestName = uiState.bestPhoneName
                        )
                    } else {
                        Text(
                            text  = stringResource(R.string.tv_no_phone),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                    }

                    // User select button
                    Button(
                        onClick = onUserSelectClick,
                        scale   = ButtonDefaults.scale(scale = 1f)
                    ) {
                        Text(stringResource(R.string.tv_select_user))
                    }
                }
            }

            // ── Content ───────────────────────────────────────────────────────
            when {
                uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                uiState.shows.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text      = stringResource(R.string.tv_no_shows),
                            fontSize  = 18.sp,
                            color     = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns            = GridCells.Adaptive(minSize = 180.dp),
                        contentPadding     = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement   = Arrangement.spacedBy(16.dp),
                        modifier           = Modifier.fillMaxSize()
                    ) {
                        items(uiState.shows, key = { it.show.ids.trakt ?: it.show.title }) { entry ->
                            ShowCard(
                                entry    = entry,
                                onClick  = { onShowClick(entry) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ShowCard(entry: TraktWatchedEntry, onClick: () -> Unit) {
    val lastSeason  = entry.seasons.maxByOrNull { it.number }
    val lastEpisode = lastSeason?.episodes?.maxByOrNull { it.number }

    Card(
        onClick   = onClick,
        modifier  = Modifier
            .width(180.dp)
            .aspectRatio(2f / 3f),
        shape     = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors    = CardDefaults.colors(
            containerColor         = Color(0xFF1C1C1E),
            focusedContainerColor  = Color(0xFF2C2C2E),
        ),
        scale     = CardDefaults.scale(focusedScale = 1.05f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Poster placeholder
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2A2A2C))
            )

            // Bottom overlay
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text       = entry.show.title,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White,
                    maxLines   = 2
                )
                if (lastSeason != null && lastEpisode != null) {
                    Text(
                        text     = "S${lastSeason.number.toString().padStart(2,'0')}E${lastEpisode.number.toString().padStart(2,'0')}",
                        fontSize = 11.sp,
                        color    = Color(0xFFE53935)
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneStatusBadge(count: Int, bestName: String?) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1C1C1E))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Green dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF4CAF50))
            )
            Text(
                text     = if (bestName != null) bestName else "$count Gerät(e)",
                fontSize = 12.sp,
                color    = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}
