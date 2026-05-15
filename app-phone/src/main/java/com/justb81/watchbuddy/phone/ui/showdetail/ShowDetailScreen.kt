package com.justb81.watchbuddy.phone.ui.showdetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.justb81.watchbuddy.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowDetailScreen(
    onBack: () -> Unit,
    viewModel: ShowDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val toggleFailedLabel = stringResource(R.string.show_detail_error_toggle)

    LaunchedEffect(uiState.toggleError) {
        val error = uiState.toggleError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = error.ifBlank { toggleFailedLabel })
        viewModel.clearToggleError()
    }

    val context = LocalContext.current
    LaunchedEffect(uiState.bulkSuccessCount) {
        val count = uiState.bulkSuccessCount ?: return@LaunchedEffect
        val message = context.resources.getQuantityString(
            R.plurals.show_detail_bulk_success,
            count,
            count
        )
        snackbarHostState.showSnackbar(message = message)
        viewModel.clearBulkSuccess()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.show?.title ?: "",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.show_detail_cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { viewModel.loadShowDetail() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.loadShowDetail(isRefresh = true) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            ShowHeader(
                                posterUrl = uiState.posterUrl,
                                title = uiState.show?.title ?: "",
                                year = uiState.show?.year,
                                overview = uiState.overview,
                                tmdbId = uiState.show?.ids?.tmdb,
                                imdbId = uiState.show?.ids?.imdb,
                            )
                        }

                        if (uiState.seasons.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.show_detail_no_episodes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                )
                            }
                        } else {
                            item {
                                Text(
                                    text = stringResource(R.string.show_detail_seasons),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            items(uiState.seasons, key = { it.number }) { season ->
                                SeasonCard(
                                    season = season,
                                    togglingEpisode = uiState.togglingEpisode,
                                    bulkInProgress = uiState.bulkInProgress,
                                    onExpandToggle = { viewModel.toggleSeasonExpanded(season.number) },
                                    onEpisodeToggle = { episode -> viewModel.toggleEpisodeWatched(episode) },
                                    onMarkEarlierWatched = { episode -> viewModel.markEarlierWatched(episode) },
                                    allSeasons = uiState.seasons
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowHeader(
    posterUrl: String?,
    title: String,
    year: Int?,
    overview: String?,
    tmdbId: Int?,
    imdbId: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = stringResource(R.string.show_detail_cd_poster),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(100.dp)
                    .height(150.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(150.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.outline)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            year?.let {
                Text(
                    text = it.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (!overview.isNullOrBlank()) {
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    maxLines = 6
                )
            }
            if (tmdbId != null || !imdbId.isNullOrBlank()) {
                ExternalLinksRow(tmdbId = tmdbId, imdbId = imdbId)
            }
        }
    }
}

@Composable
private fun ExternalLinksRow(tmdbId: Int?, imdbId: String?) {
    val uriHandler = LocalUriHandler.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tmdbId != null) {
            val cd = stringResource(R.string.show_detail_cd_open_tmdb)
            Image(
                painter = painterResource(R.drawable.ic_tmdb_logo),
                contentDescription = cd,
                modifier = Modifier
                    .height(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { uriHandler.openUri("https://www.themoviedb.org/tv/$tmdbId") }
                    .semantics { contentDescription = cd }
            )
        }
        if (!imdbId.isNullOrBlank()) {
            val cd = stringResource(R.string.show_detail_cd_open_imdb)
            Image(
                painter = painterResource(R.drawable.ic_imdb_logo),
                contentDescription = cd,
                modifier = Modifier
                    .height(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { uriHandler.openUri("https://www.imdb.com/title/$imdbId/") }
                    .semantics { contentDescription = cd }
            )
        }
    }
}

@Composable
private fun SeasonCard(
    season: SeasonUi,
    togglingEpisode: Pair<Int, Int>?,
    bulkInProgress: Boolean,
    onExpandToggle: () -> Unit,
    onEpisodeToggle: (EpisodeUi) -> Unit,
    onMarkEarlierWatched: (EpisodeUi) -> Unit,
    allSeasons: List<SeasonUi>
) {
    val rotation by animateFloatAsState(
        targetValue = if (season.expanded) 180f else 0f,
        label = "season-chevron"
    )
    val headerCd = stringResource(
        if (season.expanded) R.string.show_detail_cd_collapse_season
        else R.string.show_detail_cd_expand_season,
        season.number
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpandToggle)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.show_detail_season, season.number),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        R.string.show_detail_season_progress,
                        season.watchedCount,
                        season.totalCount
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = headerCd,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotation)
                )
            }

            AnimatedVisibility(visible = season.expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    season.episodes.forEach { episode ->
                        EpisodeRow(
                            episode = episode,
                            isToggling = togglingEpisode == (episode.season to episode.number),
                            bulkInProgress = bulkInProgress,
                            allSeasons = allSeasons,
                            onToggle = { onEpisodeToggle(episode) },
                            onMarkEarlierWatched = { onMarkEarlierWatched(episode) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Counts unwatched episodes in season >= 1 that are at or before [episode]. */
private fun candidateCount(episode: EpisodeUi, allSeasons: List<SeasonUi>): Int =
    allSeasons
        .filter { it.number >= 1 }
        .sumOf { season ->
            season.episodes.count { ep ->
                !ep.watched &&
                    (season.number < episode.season ||
                        (season.number == episode.season && ep.number <= episode.number))
            }
        }

@Composable
private fun EpisodeRow(
    episode: EpisodeUi,
    isToggling: Boolean,
    bulkInProgress: Boolean,
    allSeasons: List<SeasonUi>,
    onToggle: () -> Unit,
    onMarkEarlierWatched: () -> Unit
) {
    var showBulkConfirm by remember { mutableStateOf(false) }
    val count = candidateCount(episode, allSeasons)
    val toggleCd = stringResource(
        R.string.show_detail_cd_toggle_watched,
        episode.number,
        episode.season
    )

    if (showBulkConfirm) {
        BulkConfirmDialog(
            episode = episode,
            candidateCount = count,
            bulkInProgress = bulkInProgress,
            onConfirm = {
                showBulkConfirm = false
                onMarkEarlierWatched()
            },
            onDismiss = { showBulkConfirm = false }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isToggling, onClick = onToggle)
            .semantics { contentDescription = toggleCd }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isToggling) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Checkbox(
                    checked = episode.watched,
                    onCheckedChange = { onToggle() }
                )
            }
        }
        Text(
            text = formatEpisodeLabel(episode),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        EpisodeOverflowMenu(
            episode = episode,
            candidateCount = count,
            bulkInProgress = bulkInProgress,
            onShowBulkConfirm = { showBulkConfirm = true }
        )
    }
}

@Composable
private fun EpisodeOverflowMenu(
    episode: EpisodeUi,
    candidateCount: Int,
    bulkInProgress: Boolean,
    onShowBulkConfirm: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(
                    R.string.show_detail_cd_episode_menu,
                    episode.number,
                    episode.season
                ),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.show_detail_mark_earlier_watched)) },
                onClick = {
                    menuExpanded = false
                    onShowBulkConfirm()
                },
                enabled = candidateCount > 0 && !bulkInProgress
            )
        }
    }
}

@Composable
private fun BulkConfirmDialog(
    episode: EpisodeUi,
    candidateCount: Int,
    bulkInProgress: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.show_detail_bulk_confirm_title)) },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.show_detail_bulk_confirm_body,
                    candidateCount,
                    episode.season,
                    episode.number,
                    candidateCount
                )
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !bulkInProgress
            ) {
                if (bulkInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.show_detail_bulk_confirm_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
    )
}

@Composable
private fun formatEpisodeLabel(episode: EpisodeUi): String {
    val prefix = stringResource(
        R.string.show_detail_episode_label,
        episode.season,
        episode.number
    )
    return if (episode.title.isNullOrBlank()) prefix
    else "$prefix — ${episode.title}"
}
