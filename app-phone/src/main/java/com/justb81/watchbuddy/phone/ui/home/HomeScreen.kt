package com.justb81.watchbuddy.phone.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.ScrobbleAction
import com.justb81.watchbuddy.core.model.ScrobbleDisplayEvent
import com.justb81.watchbuddy.core.model.TraktWatchedEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit,
    onConnectClick: () -> Unit,
    onShowClick: (traktShowId: Int) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.home_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(24.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                            color       = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButton(onClick = { viewModel.sync() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.home_cd_sync))
                        }
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.home_cd_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                uiState.error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            uiState.error!!,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { viewModel.loadShows() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }

                uiState.shows.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text  = stringResource(R.string.home_no_shows),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Button(onClick = onConnectClick) {
                            Text(stringResource(R.string.home_connect_to_trakt))
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Watching TV toggle
                        if (uiState.canWatch) {
                            item {
                                WatchingTvToggle(
                                    isWatching = uiState.isWatchingTv,
                                    onToggle = { viewModel.toggleWatchingTv(it) }
                                )
                            }
                        }

                        // Now watching card (scrobble event from TV)
                        uiState.latestScrobbleEvent?.let { event ->
                            item {
                                NowWatchingCard(event = event)
                            }
                        }

                        // Continue watching section
                        val inProgress = uiState.shows.filter { it.seasons.isNotEmpty() }
                        if (inProgress.isNotEmpty()) {
                            item {
                                SectionHeader(stringResource(R.string.home_continue_watching))
                            }
                            items(inProgress.take(5)) { entry ->
                                ShowCard(
                                    entry = entry,
                                    posterUrl = entry.show.ids.tmdb?.let { uiState.posterUrls[it] },
                                    onClick = {
                                        entry.show.ids.trakt?.let { onShowClick(it) }
                                    }
                                )
                            }
                        }

                        // All shows
                        item {
                            SectionHeader(stringResource(R.string.home_all_shows))
                        }
                        items(uiState.shows) { entry ->
                            ShowCard(
                                entry = entry,
                                posterUrl = entry.show.ids.tmdb?.let { uiState.posterUrls[it] },
                                onClick = {
                                    entry.show.ids.trakt?.let { onShowClick(it) }
                                }
                            )
                        }

                        // Last sync footer
                        uiState.lastSyncTime?.let { time ->
                            item {
                                Text(
                                    text     = stringResource(R.string.home_last_sync, time),
                                    style    = MaterialTheme.typography.bodySmall,
                                    color    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text       = title,
        style      = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color      = MaterialTheme.colorScheme.onBackground,
        modifier   = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun WatchingTvToggle(
    isWatching: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isWatching)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Tv,
                contentDescription = null,
                tint = if (isWatching)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_watching_tv_toggle),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isWatching)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.home_watching_tv_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isWatching)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Switch(
                checked = isWatching,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun NowWatchingCard(event: ScrobbleDisplayEvent) {
    val (actionText, actionIcon) = when (event.action) {
        ScrobbleAction.START -> stringResource(R.string.home_scrobble_started) to Icons.Default.PlayArrow
        ScrobbleAction.PAUSE -> stringResource(R.string.home_scrobble_paused) to Icons.Default.Pause
        ScrobbleAction.STOP  -> stringResource(R.string.home_scrobble_stopped) to Icons.Default.Stop
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                actionIcon,
                contentDescription = actionText,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_now_watching),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = event.show.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = "S%02dE%02d — %s".format(
                        event.episode.season,
                        event.episode.number,
                        actionText
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ShowCard(
    entry: TraktWatchedEntry,
    posterUrl: String?,
    onClick: () -> Unit
) {
    val totalEpisodes = entry.seasons.sumOf { it.episodes.size }
    val lastSeason    = entry.seasons.maxByOrNull { it.number }
    val lastEpisode   = lastSeason?.episodes?.maxByOrNull { it.number }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = entry.show.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp, 80.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp, 80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.outline)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text       = entry.show.title,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                if (lastSeason != null && lastEpisode != null) {
                    Text(
                        text  = stringResource(
                            R.string.home_episode_progress,
                            lastSeason.number,
                            lastEpisode.number,
                            totalEpisodes,
                            totalEpisodes
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                entry.show.year?.let { year ->
                    Text(
                        text  = year.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }
            }

            // Arrow indicator
            Text(
                text  = "›",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}
