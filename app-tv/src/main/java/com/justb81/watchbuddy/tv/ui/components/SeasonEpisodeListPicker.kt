package com.justb81.watchbuddy.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.TraktEpisode
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes

/**
 * Scrollable list of seasons and episodes. Each row shows a check/uncheck icon
 * reflecting the current watched state, and calls [onToggle] when tapped.
 *
 * @param seasons        Full season+episode structure fetched from the phone.
 * @param watchedSet     Set of (season, episode) pairs already marked watched.
 * @param highlightSeason Season number to scroll into view on first composition.
 * @param onToggle       Called with (season, episode, currentlyWatched) when the
 *                       user taps a row — the ViewModel decides what to do next
 *                       (e.g. show scope picker or toggle immediately).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeasonEpisodeListPicker(
    seasons: List<TraktSeasonWithEpisodes>,
    watchedSet: Set<Pair<Int, Int>>,
    highlightSeason: Int?,
    onToggle: (season: Int, episode: Int, currentlyWatched: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState: LazyListState = rememberLazyListState()

    // Scroll the highlighted season into view on first render
    LaunchedEffect(highlightSeason) {
        if (highlightSeason == null) return@LaunchedEffect
        val seasonIndex = seasons.indexOfFirst { it.number == highlightSeason }
        if (seasonIndex >= 0) {
            listState.animateScrollToItem(seasonIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        seasons.forEach { season ->
            item(key = "season_${season.number}") {
                SeasonHeader(season.number)
                Spacer(Modifier.height(4.dp))
            }
            items(season.episodes, key = { "ep_${season.number}_${it.number}" }) { episode ->
                val isWatched = watchedSet.contains(season.number to episode.number)
                EpisodeRow(
                    season = season.number,
                    episode = episode,
                    isWatched = isWatched,
                    onToggle = { onToggle(season.number, episode.number, isWatched) },
                )
            }
        }
    }
}

@Composable
private fun SeasonHeader(seasonNumber: Int) {
    Text(
        text = stringResource(R.string.tv_detail_season_header, seasonNumber),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeRow(
    season: Int,
    episode: TraktEpisode,
    isWatched: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isWatched) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (isWatched) {
                    stringResource(R.string.tv_detail_mark_unwatched_cd)
                } else {
                    stringResource(R.string.tv_detail_mark_watched_cd)
                },
                tint = if (isWatched) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "S%02dE%02d".format(season, episode.number),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
                episode.title?.let { title ->
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.87f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
