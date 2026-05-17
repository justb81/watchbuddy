package com.justb81.watchbuddy.tv.ui.showdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.tv.ui.components.SeasonEpisodeListPicker
import com.justb81.watchbuddy.tv.ui.components.UserScopePickerDialog

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AllEpisodesScreen(
    enriched: EnrichedShowEntry,
    onBack: () -> Unit,
    viewModel: ShowDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val ready = uiState as? ShowDetailUiState.Ready
    val episodeListState = ready?.episodeList ?: EpisodeListUiState.Idle
    val entry = enriched.entry

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingToggle by remember { mutableStateOf<Triple<Int, Int, Boolean>?>(null) }

    val allFailedMsg = stringResource(R.string.tv_detail_toggle_failed_all)
    val partialFailedMsg = stringResource(R.string.tv_detail_toggle_failed_partial)

    LaunchedEffect(entry.show.ids.trakt) {
        viewModel.loadEpisodeList(enriched)
    }

    LaunchedEffect(Unit) {
        viewModel.episodeToggleEvents.collect { event ->
            when (event) {
                is EpisodeToggleEvent.AllFailed ->
                    snackbarHostState.showSnackbar(allFailedMsg)
                is EpisodeToggleEvent.PartialFailed ->
                    snackbarHostState.showSnackbar(
                        partialFailedMsg.format(event.failedUserNames.joinToString(", "))
                    )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp, vertical = 48.dp)
        ) {
            Text(
                text = entry.show.title,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tv_all_episodes),
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(24.dp))

            when (episodeListState) {
                is EpisodeListUiState.Idle, is EpisodeListUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                is EpisodeListUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.tv_error_phone_unreachable),
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }

                is EpisodeListUiState.Success -> {
                    SeasonEpisodeListPicker(
                        seasons = episodeListState.seasons,
                        watchedSet = episodeListState.watchedSet,
                        highlightSeason = null,
                        onToggle = { season, episode, currentlyWatched ->
                            if (viewModel.skipScopePickerThisSession) {
                                val allIds = viewModel.connectedUsers().map { it.id }.toSet()
                                viewModel.toggleEpisodeWatched(
                                    showIds = entry.show.ids,
                                    season = season,
                                    episode = episode,
                                    markAsWatched = !currentlyWatched,
                                    selectedUserIds = allIds,
                                )
                            } else {
                                pendingToggle = Triple(season, episode, !currentlyWatched)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(32.dp),
        ) {
            Text(stringResource(R.string.tv_back_arrow))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    pendingToggle?.let { (season, episode, markAsWatched) ->
        val users = viewModel.connectedUsers()
        UserScopePickerDialog(
            connectedUsers = users,
            initialSelection = users.map { it.id }.toSet(),
            onConfirm = { selectedIds, dontAskAgain ->
                if (dontAskAgain) viewModel.onDontAskAgainSet()
                viewModel.toggleEpisodeWatched(
                    showIds = entry.show.ids,
                    season = season,
                    episode = episode,
                    markAsWatched = markAsWatched,
                    selectedUserIds = selectedIds,
                )
                pendingToggle = null
            },
            onDismiss = { pendingToggle = null },
        )
    }
}
