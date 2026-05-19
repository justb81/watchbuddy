package com.justb81.watchbuddy.phone.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.phone.ui.theme.watchBuddyShapes

private const val POSTER_WIDTH_DP = 42
private const val POSTER_HEIGHT_DP = 60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val addedMsg = stringResource(R.string.search_show_added, uiState.addedShowTitle ?: "")
    LaunchedEffect(uiState.addedShowTitle) {
        if (uiState.addedShowTitle != null) {
            snackbarHostState.showSnackbar(addedMsg)
            viewModel.consumeAddedShowTitle()
        }
    }

    LaunchedEffect(uiState.addError) {
        if (uiState.addError != null) {
            snackbarHostState.showSnackbar(uiState.addError!!)
            viewModel.consumeAddError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.search_cd_back)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = uiState.query,
                        onQueryChange = viewModel::onQueryChanged,
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                    )
                },
                expanded = false,
                onExpandedChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {}

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> LoadingIndicator()
                    uiState.error != null -> ErrorText(uiState.error!!)
                    uiState.query.length >= 2 && uiState.results.isEmpty() ->
                        EmptyText(stringResource(R.string.search_no_results, uiState.query))
                    uiState.query.length < 2 ->
                        EmptyText(stringResource(R.string.search_empty_prompt))
                    else -> SearchResultsList(
                        results = uiState.results,
                        trackedShowIds = uiState.trackedShowIds,
                        addingShowId = uiState.addingShowId,
                        onAddShow = viewModel::addShow
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingIndicator() {
    Box(modifier = Modifier.fillMaxSize()) {
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ErrorText(message: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp)
        )
    }
}

@Composable
private fun EmptyText(message: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp)
        )
    }
}

@Composable
private fun SearchResultsList(
    results: List<SearchResultItem>,
    trackedShowIds: Set<Int>,
    addingShowId: Int?,
    onAddShow: (TraktShow) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(results, key = { it.result.show?.ids?.trakt ?: it.result.show?.title ?: "" }) { item ->
            val show = item.result.show ?: return@items
            SearchResultCard(
                item = item,
                show = show,
                isTracked = show.ids.trakt != null && show.ids.trakt in trackedShowIds,
                isAdding = show.ids.trakt != null && show.ids.trakt == addingShowId,
                onAddShow = { onAddShow(show) }
            )
        }
    }
}

@Composable
private fun SearchResultCard(
    item: SearchResultItem,
    show: TraktShow,
    isTracked: Boolean,
    isAdding: Boolean,
    onAddShow: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.watchBuddyShapes.banner,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PosterThumbnail(posterUrl = item.posterUrl, title = show.title)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = show.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                SeasonRangeAndStatus(item = item)
            }

            when {
                isAdding -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                isTracked -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.search_cd_already_tracked),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                else -> {
                    IconButton(onClick = onAddShow) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.search_cd_add_show, show.title),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonRangeAndStatus(item: SearchResultItem) {
    val seasonRangeText = buildSeasonRangeText(
        firstAirYear = item.firstAirYear,
        lastAirYear = item.lastAirYear,
        status = item.status,
    )
    val statusLabel = item.status?.let { resolveStatusLabel(it) }

    if (seasonRangeText != null || statusLabel != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (seasonRangeText != null) {
                Text(
                    text = seasonRangeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (statusLabel != null) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    border = null,
                    modifier = Modifier.padding(vertical = 0.dp)
                )
            }
        }
    }
}

@Composable
private fun buildSeasonRangeText(firstAirYear: Int?, lastAirYear: Int?, status: String?): String? {
    val first = firstAirYear ?: return null
    val isOngoing = status != null && status.equals("Ended", ignoreCase = true).not() &&
        status.equals("Canceled", ignoreCase = true).not() &&
        status.equals("Cancelled", ignoreCase = true).not()
    return if (isOngoing || lastAirYear == null || lastAirYear == first) {
        stringResource(R.string.search_season_range_ongoing, first)
    } else {
        stringResource(R.string.search_season_range_ended, first, lastAirYear)
    }
}

@Composable
private fun resolveStatusLabel(status: String): String? = when {
    status.equals("Returning Series", ignoreCase = true) ||
        status.equals("In Production", ignoreCase = true) ||
        status.equals("Planned", ignoreCase = true) ||
        status.equals("Pilot", ignoreCase = true) ->
        stringResource(R.string.search_status_ongoing)
    status.equals("Ended", ignoreCase = true) ->
        stringResource(R.string.search_status_ended)
    status.equals("Canceled", ignoreCase = true) ||
        status.equals("Cancelled", ignoreCase = true) ->
        stringResource(R.string.search_status_cancelled)
    else -> null
}

@Composable
private fun PosterThumbnail(posterUrl: String?, title: String) {
    if (posterUrl != null) {
        AsyncImage(
            model = posterUrl,
            contentDescription = stringResource(R.string.search_cd_cover_art, title),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(POSTER_WIDTH_DP.dp, POSTER_HEIGHT_DP.dp)
                .clip(MaterialTheme.watchBuddyShapes.thumbnail)
        )
    } else {
        Box(
            modifier = Modifier
                .size(POSTER_WIDTH_DP.dp, POSTER_HEIGHT_DP.dp)
                .clip(MaterialTheme.watchBuddyShapes.thumbnail)
                .background(MaterialTheme.colorScheme.outline),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
