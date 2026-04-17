package com.justb81.watchbuddy.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.*
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.CrashReporter
import com.justb81.watchbuddy.core.logging.DiagnosticShare
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.tv.ui.theme.extendedColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeScreen(
    onShowClick: (TraktWatchedEntry) -> Unit,
    onUserSelectClick: () -> Unit,
    onStreamingSettingsClick: () -> Unit = {},
    viewModel: TvHomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pendingReports by remember { mutableStateOf(CrashReporter.listReports(context).size) }

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

                    // Diagnostics export button — always focusable on TV so we can
                    // collect logs even when nothing has crashed yet.
                    OutlinedButton(
                        onClick = {
                            DiagnosticShare.launchShare(context)
                            pendingReports = CrashReporter.listReports(context).size
                        },
                        scale   = ButtonDefaults.scale(scale = 1f)
                    ) {
                        Text(stringResource(R.string.diagnostics_export))
                    }

                    // Streaming settings button
                    OutlinedButton(
                        onClick = onStreamingSettingsClick,
                        scale   = ButtonDefaults.scale(scale = 1f)
                    ) {
                        Text(stringResource(R.string.tv_streaming_settings_button))
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

            if (pendingReports > 0) {
                TvDiagnosticsBanner(
                    reportCount = pendingReports,
                    onShare = { DiagnosticShare.launchShare(context) },
                    onDismiss = {
                        CrashReporter.clearReports(context)
                        pendingReports = 0
                    }
                )
            }

            // ── Content ───────────────────────────────────────────────────────
            when {
                uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                uiState.noPhoneConnected && uiState.shows.isEmpty() -> {
                    NoPhoneConnectedState(onRetry = { viewModel.loadShows() })
                }

                uiState.phoneApiError && uiState.shows.isEmpty() -> {
                    PhoneUnreachableState(onRetry = { viewModel.loadShows() })
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
                    Column(Modifier.fillMaxSize()) {
                        if (uiState.phoneApiError) {
                            PhoneUnreachableBanner()
                        }
                        val gridState = rememberLazyGridState()
                        val loadMoreTrigger by remember {
                            derivedStateOf {
                                val totalItems = gridState.layoutInfo.totalItemsCount
                                val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                totalItems > 0 && lastVisible >= totalItems - 6
                            }
                        }
                        LaunchedEffect(loadMoreTrigger) {
                            if (loadMoreTrigger) viewModel.loadMoreShows()
                        }
                        LazyVerticalGrid(
                            state                 = gridState,
                            columns               = GridCells.Adaptive(minSize = 180.dp),
                            contentPadding        = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement   = Arrangement.spacedBy(16.dp),
                            modifier              = Modifier.weight(1f).fillMaxWidth()
                        ) {
                            items(uiState.shows, key = { it.show.ids.trakt ?: it.show.title }) { entry ->
                                ShowCard(
                                    entry   = entry,
                                    onClick = { onShowClick(entry) }
                                )
                            }
                            if (uiState.isLoadingMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color    = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
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
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NoPhoneConnectedState(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text     = stringResource(R.string.tv_no_phone),
                fontSize = 18.sp,
                color    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Button(
                onClick = onRetry,
                scale   = ButtonDefaults.scale(scale = 1f)
            ) {
                Text(stringResource(R.string.tv_retry))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PhoneUnreachableState(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text     = stringResource(R.string.tv_error_phone_unreachable_no_cache),
                fontSize = 18.sp,
                color    = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Button(
                onClick = onRetry,
                scale   = ButtonDefaults.scale(scale = 1f)
            ) {
                Text(stringResource(R.string.tv_retry))
            }
        }
    }
}

@Composable
private fun PhoneUnreachableBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 48.dp, vertical = 8.dp)
    ) {
        Text(
            text     = stringResource(R.string.tv_error_phone_unreachable),
            fontSize = 13.sp,
            color    = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/**
 * Builds a content description for a show card.
 *
 * Pure Kotlin — no Compose context required, making it easy to unit-test.
 * Format: "Show Title, S01E05" when episode is known, or just "Show Title" otherwise.
 */
internal fun showCardContentDescription(
    showTitle: String,
    lastSeasonNumber: Int?,
    lastEpisodeNumber: Int?
): String = if (lastSeasonNumber != null && lastEpisodeNumber != null) {
    "$showTitle, S${lastSeasonNumber.toString().padStart(2, '0')}E${lastEpisodeNumber.toString().padStart(2, '0')}"
} else {
    showTitle
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ShowCard(entry: TraktWatchedEntry, onClick: () -> Unit) {
    val lastSeason  = entry.seasons.maxByOrNull { it.number }
    val lastEpisode = lastSeason?.episodes?.maxByOrNull { it.number }

    val cardDescription = showCardContentDescription(
        showTitle         = entry.show.title,
        lastSeasonNumber  = lastSeason?.number,
        lastEpisodeNumber = lastEpisode?.number
    )

    Card(
        onClick   = onClick,
        modifier  = Modifier
            .width(180.dp)
            .aspectRatio(2f / 3f)
            .semantics { contentDescription = cardDescription },
        shape     = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors    = CardDefaults.colors(
            containerColor         = MaterialTheme.colorScheme.surface,
            focusedContainerColor  = MaterialTheme.colorScheme.surfaceVariant,
        ),
        scale     = CardDefaults.scale(focusedScale = 1.05f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Poster placeholder
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.extendedColors.placeholder)
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
                        color    = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvDiagnosticsBanner(
    reportCount: Int,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 48.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.diagnostics_banner_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.diagnostics_banner_message, reportCount),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
            )
        }
        OutlinedButton(
            onClick = onDismiss,
            scale = ButtonDefaults.scale(scale = 1f)
        ) {
            Text(stringResource(R.string.diagnostics_banner_dismiss))
        }
        Button(
            onClick = onShare,
            scale = ButtonDefaults.scale(scale = 1f)
        ) {
            Text(stringResource(R.string.diagnostics_banner_share))
        }
    }
}

@Composable
private fun PhoneStatusBadge(count: Int, bestName: String?) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Green dot — purely decorative; the adjacent text already conveys the status
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.extendedColors.success)
                    .clearAndSetSemantics {}
            )
            Text(
                text     = if (bestName != null) bestName else stringResource(R.string.tv_devices_count, count),
                fontSize = 12.sp,
                color    = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}
