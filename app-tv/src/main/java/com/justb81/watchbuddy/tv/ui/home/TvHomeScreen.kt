package com.justb81.watchbuddy.tv.ui.home

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.progress.ShowProgress
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.tv.ui.theme.extendedColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeScreen(
    onShowClick: (TraktWatchedEntry) -> Unit,
    onUserSelectClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    viewModel: TvHomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

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
                    if (uiState.connectedPhones > 0) {
                        PhoneStatusBadge(count = uiState.connectedPhones, bestName = uiState.bestPhoneName)
                    } else {
                        Text(
                            text = stringResource(R.string.tv_no_phone),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                    }

                    OutlinedButton(
                        onClick = onSettingsClick,
                        scale = ButtonDefaults.scale(scale = 1f)
                    ) { Text(stringResource(R.string.tv_settings_button)) }

                    Button(
                        onClick = onUserSelectClick,
                        scale = ButtonDefaults.scale(scale = 1f)
                    ) { Text(stringResource(R.string.tv_select_user)) }
                }
            }

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
                            text = stringResource(R.string.tv_no_shows),
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }

                else -> {
                    Column(Modifier.fillMaxSize()) {
                        if (uiState.phoneApiError) PhoneUnreachableBanner()
                        TvHomeShelves(
                            state = uiState,
                            onShowClick = onShowClick,
                            onLoadMore = { viewModel.loadMoreShows() }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvHomeShelves(
    state: TvHomeUiState,
    onShowClick: (TraktWatchedEntry) -> Unit,
    onLoadMore: () -> Unit
) {
    val (continueWatching, allOthers) = remember(state.shows, state.progress) {
        state.shows.partition { entry ->
            val id = entry.entry.show.ids.trakt
            val p = id?.let { state.progress[it] }
            p is ShowProgress.InProgress || p is ShowProgress.CaughtUpAiring
        }
    }
    var allShowsExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (continueWatching.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.tv_continue_watching),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(continueWatching, key = { it.entry.show.ids.trakt ?: it.entry.show.title }) { enriched ->
                        TvShowCard(
                            enriched = enriched,
                            progress = enriched.entry.show.ids.trakt?.let { state.progress[it] },
                            onClick = { onShowClick(enriched.entry) }
                        )
                    }
                }
            }
        }

        item {
            TvAllShowsHeader(
                count = allOthers.size,
                expanded = allShowsExpanded,
                onToggle = { allShowsExpanded = !allShowsExpanded }
            )
        }

        if (allShowsExpanded) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(allOthers, key = { it.entry.show.ids.trakt ?: it.entry.show.title }) { enriched ->
                        TvShowCard(
                            enriched = enriched,
                            progress = enriched.entry.show.ids.trakt?.let { state.progress[it] },
                            onClick = { onShowClick(enriched.entry) }
                        )
                    }
                }
            }
            // Trigger load-more when the All shows section is expanded.
            if (state.canLoadMore) {
                item {
                    LaunchedEffect(allShowsExpanded) { if (allShowsExpanded) onLoadMore() }
                    if (state.isLoadingMore) {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
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
private fun TvAllShowsHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val cd = stringResource(if (expanded) R.string.tv_section_collapse else R.string.tv_section_expand)
    Surface(
        onClick = onToggle,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
            focusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = cd }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.tv_all_shows),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = pluralStringResource(R.plurals.tv_section_all_shows_count, count, count),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.rotate(rotation),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NoPhoneConnectedState(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.tv_no_phone),
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Button(onClick = onRetry, scale = ButtonDefaults.scale(scale = 1f)) {
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
                text = stringResource(R.string.tv_error_phone_unreachable_no_cache),
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Button(onClick = onRetry, scale = ButtonDefaults.scale(scale = 1f)) {
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
            text = stringResource(R.string.tv_error_phone_unreachable),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/**
 * Pure Kotlin content-description builder — unit-testable without Compose context.
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
private fun TvShowCard(
    enriched: EnrichedShowEntry,
    progress: ShowProgress?,
    onClick: () -> Unit
) {
    val entry = enriched.entry
    val posterUrl = TmdbImageHelper.poster(enriched.posterPath, 500)

    val lastSeason = entry.seasons.maxByOrNull { it.number }
    val lastEp = lastSeason?.episodes?.maxByOrNull { it.number }
    val cardDescription = showCardContentDescription(entry.show.title, lastSeason?.number, lastEp?.number)

    Card(
        onClick = onClick,
        modifier = Modifier
            .width(180.dp)
            .aspectRatio(2f / 3f)
            .semantics { contentDescription = cardDescription },
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        scale = CardDefaults.scale(focusedScale = 1.05f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.extendedColors.placeholder)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = entry.show.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2
                )
                TvProgressLines(progress)
            }

            Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                TvProgressBadge(progress)
            }
        }
    }
}

@Composable
private fun TvProgressLines(progress: ShowProgress?) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val color = Color.White.copy(alpha = 0.85f)

    when (progress) {
        is ShowProgress.InProgress -> {
            Text(
                text = stringResource(
                    R.string.tv_last_watched,
                    progress.latestWatchedLabel,
                    relativeTime(context, progress.latestWatched, now)
                ),
                fontSize = 11.sp, color = color, maxLines = 1
            )
            Text(
                text = stringResource(
                    R.string.tv_last_aired_episode,
                    progress.lastAiredLabel,
                    relativeDate(context, progress.lastAired, now)
                ),
                fontSize = 11.sp, color = color, maxLines = 1
            )
        }
        is ShowProgress.CaughtUpAiring -> {
            Text(
                text = stringResource(
                    R.string.tv_next_aired,
                    progress.nextAiredLabel,
                    relativeDate(context, progress.nextAired, now)
                ),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1
            )
        }
        is ShowProgress.CaughtUpEnded -> {
            progress.latestWatched?.let {
                Text(
                    text = relativeTime(context, it, now),
                    fontSize = 11.sp, color = color, maxLines = 1
                )
            }
        }
        is ShowProgress.NotStarted -> {
            progress.nextAired?.let {
                Text(
                    text = relativeDate(context, it, now),
                    fontSize = 11.sp, color = color, maxLines = 1
                )
            }
        }
        is ShowProgress.Unknown, null -> Unit
    }
}

@Composable
private fun TvProgressBadge(progress: ShowProgress?) {
    when (progress) {
        is ShowProgress.InProgress -> {
            val n = progress.episodesBehind
            BadgePill(
                text = pluralStringResource(R.plurals.tv_episodes_behind, n, n),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
        is ShowProgress.CaughtUpAiring -> BadgePill(
            text = stringResource(R.string.tv_caught_up),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            leadingIcon = true
        )
        is ShowProgress.CaughtUpEnded -> BadgePill(
            text = stringResource(R.string.tv_show_completed),
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant
        )
        is ShowProgress.NotStarted -> BadgePill(
            text = stringResource(R.string.tv_not_started),
            container = MaterialTheme.colorScheme.surface,
            content = MaterialTheme.colorScheme.primary
        )
        is ShowProgress.Unknown, null -> Unit
    }
}

@Composable
private fun BadgePill(
    text: String,
    container: Color,
    content: Color,
    leadingIcon: Boolean = false
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (leadingIcon) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(text = text, fontSize = 10.sp, color = content)
    }
}

private fun relativeTime(context: android.content.Context, moment: Instant, now: Long): String {
    val momentMs = moment.toEpochMilli()
    val delta = momentMs - now
    val dayMs = 24 * 60 * 60 * 1000L
    return when {
        delta in -dayMs..dayMs -> context.getString(R.string.tv_time_today)
        delta in -2 * dayMs..-dayMs -> context.getString(R.string.tv_time_yesterday)
        delta in dayMs..2 * dayMs -> context.getString(R.string.tv_time_tomorrow)
        else -> DateUtils.getRelativeTimeSpanString(
            momentMs, now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
}

private fun relativeDate(context: android.content.Context, moment: Instant, now: Long): String {
    val today = LocalDate.now(ZoneId.systemDefault())
    val day = moment.atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        day.isEqual(today) -> context.getString(R.string.tv_time_today)
        day.isEqual(today.minusDays(1)) -> context.getString(R.string.tv_time_yesterday)
        day.isEqual(today.plusDays(1)) -> context.getString(R.string.tv_time_tomorrow)
        else -> DateUtils.getRelativeTimeSpanString(
            moment.toEpochMilli(), now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
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
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.extendedColors.success)
                    .clearAndSetSemantics {}
            )
            Text(
                text = if (bestName != null) bestName else stringResource(R.string.tv_devices_count, count),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}
