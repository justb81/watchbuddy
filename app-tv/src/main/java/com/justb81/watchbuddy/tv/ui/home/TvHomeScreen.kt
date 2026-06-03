package com.justb81.watchbuddy.tv.ui.home

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.AvatarSource
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.progress.ShowProgress
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.tv.ui.components.InitialsAvatar
import com.justb81.watchbuddy.tv.ui.theme.TvSpacing
import com.justb81.watchbuddy.tv.ui.theme.extendedColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val TvNewSeasonBadgeCorner = 10.dp
private val TvNewSeasonBadgePadding = 4.dp
private val TvNewSeasonBadgeIconSize = 10.dp
private val TvShowCardBadgeOffset = 6.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeScreen(
    onShowClick: (EnrichedShowEntry) -> Unit,
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
            TvHomeHeader(uiState = uiState, onSettingsClick = onSettingsClick)
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
                        if (uiState.isShowingStaleCache) StaleCacheBanner()
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
private fun TvHomeHeader(uiState: TvHomeUiState, onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TvSpacing.screenHorizontal, vertical = TvSpacing.sectionGap),
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
            when {
                uiState.activeViewers.isNotEmpty() -> ActiveViewersRow(viewers = uiState.activeViewers)
                uiState.isDiscoveryPending -> DiscoveryPendingIndicator()
                else -> Text(
                    text = stringResource(R.string.tv_no_phone),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
            OutlinedButton(
                onClick = onSettingsClick,
                scale = ButtonDefaults.scale(scale = 1f)
            ) { Text(stringResource(R.string.tv_settings_title)) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvHomeShelves(
    state: TvHomeUiState,
    onShowClick: (EnrichedShowEntry) -> Unit,
    onLoadMore: () -> Unit
) {
    val continueWatching = state.continueWatching
    val allOthers = state.allShows
    var allShowsExpanded by rememberSaveable { mutableStateOf(false) }

    var focusedShowKey by rememberSaveable { mutableStateOf<String?>(null) }
    val continueWatchingState = rememberLazyListState()
    val allShowsState = rememberLazyListState()
    val cwRequesters = remember(continueWatching) { mutableMapOf<String, FocusRequester>() }
    val allRequesters = remember(allOthers) { mutableMapOf<String, FocusRequester>() }

    val cwShelf = ShelfFocusContext(continueWatching, continueWatchingState, cwRequesters)
    val allShelf = ShelfFocusContext(allOthers, allShowsState, allRequesters)
    RestoreShelfFocusEffect(focusedShowKey, cwShelf, allShelf, allShowsExpanded)

    LazyColumn(
        contentPadding = PaddingValues(horizontal = TvSpacing.screenHorizontal, vertical = TvSpacing.itemGap),
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
                TvShowShelfRow(
                    shows = continueWatching,
                    state = state,
                    listState = continueWatchingState,
                    requesters = cwRequesters,
                    onShowClick = onShowClick,
                    onFocused = { focusedShowKey = it }
                )
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
                TvShowShelfRow(
                    shows = allOthers,
                    state = state,
                    listState = allShowsState,
                    requesters = allRequesters,
                    onShowClick = onShowClick,
                    onFocused = { focusedShowKey = it }
                )
            }
            if (state.canLoadMore) {
                item { AllShowsLoadMoreTrigger(state, onLoadMore) }
            }
        }
    }
}

@Composable
private fun AllShowsLoadMoreTrigger(state: TvHomeUiState, onLoadMore: () -> Unit) {
    LaunchedEffect(Unit) { onLoadMore() }
    if (state.isLoadingMore) {
        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

private fun EnrichedShowEntry.focusKey(): String =
    entry.show.ids.trakt?.toString() ?: entry.show.title

private class ShelfFocusContext(
    val shows: List<EnrichedShowEntry>,
    val listState: LazyListState,
    val requesters: Map<String, FocusRequester>
)

@Composable
private fun RestoreShelfFocusEffect(
    focusedShowKey: String?,
    continueWatching: ShelfFocusContext,
    allShows: ShelfFocusContext,
    allShowsExpanded: Boolean
) {
    LaunchedEffect(continueWatching.shows, allShows.shows, allShowsExpanded) {
        if (focusedShowKey == null) {
            val firstCwKey = continueWatching.shows.firstOrNull()?.focusKey()
            if (firstCwKey != null) {
                continueWatching.listState.scrollToItem(0)
                continueWatching.requesters[firstCwKey]?.requestFocus()
                return@LaunchedEffect
            }
            if (allShowsExpanded) {
                val firstAllKey = allShows.shows.firstOrNull()?.focusKey()
                if (firstAllKey != null) {
                    allShows.listState.scrollToItem(0)
                    allShows.requesters[firstAllKey]?.requestFocus()
                }
            }
            return@LaunchedEffect
        }
        val cwIndex = continueWatching.shows.indexOfFirst { it.focusKey() == focusedShowKey }
        if (cwIndex >= 0) {
            continueWatching.listState.scrollToItem(cwIndex)
            continueWatching.requesters[focusedShowKey]?.requestFocus()
            return@LaunchedEffect
        }
        if (allShowsExpanded) {
            val allIndex = allShows.shows.indexOfFirst { it.focusKey() == focusedShowKey }
            if (allIndex >= 0) {
                allShows.listState.scrollToItem(allIndex)
                allShows.requesters[focusedShowKey]?.requestFocus()
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvShowShelfRow(
    shows: List<EnrichedShowEntry>,
    state: TvHomeUiState,
    listState: LazyListState,
    requesters: MutableMap<String, FocusRequester>,
    onShowClick: (EnrichedShowEntry) -> Unit,
    onFocused: (String) -> Unit
) {
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(shows, key = { it.focusKey() }) { enriched ->
            val key = enriched.focusKey()
            val requester = remember(key) { FocusRequester() }
            DisposableEffect(key, requester) {
                requesters[key] = requester
                onDispose { requesters.remove(key, requester) }
            }
            TvShowCard(
                enriched = enriched,
                progress = enriched.entry.show.ids.trakt?.let { state.progress[it] },
                hasNewSeason = enriched.entry.show.ids.trakt?.let { state.hasNewSeason[it] } == true,
                onClick = { onShowClick(enriched) },
                modifier = Modifier
                    .focusRequester(requester)
                    .onFocusChanged { if (it.isFocused) onFocused(key) }
            )
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
            .padding(horizontal = TvSpacing.screenHorizontal, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.tv_error_phone_unreachable),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun StaleCacheBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = TvSpacing.screenHorizontal, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.tv_stale_cache_banner),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer
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
    hasNewSeason: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entry = enriched.entry
    val posterUrl = TmdbImageHelper.poster(enriched.posterPath, 500)

    val lastSeason = entry.seasons.maxByOrNull { it.number }
    val lastEp = lastSeason?.episodes?.maxByOrNull { it.number }
    val cardDescription = showCardContentDescription(entry.show.title, lastSeason?.number, lastEp?.number)

    Card(
        onClick = onClick,
        modifier = modifier
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

            if (hasNewSeason) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(TvShowCardBadgeOffset)) {
                    TvNewSeasonBadge()
                }
            }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(TvShowCardBadgeOffset)) {
                TvProgressBadge(progress)
            }
        }
    }
}

@Composable
private fun TvNewSeasonBadge() {
    val description = stringResource(R.string.tv_new_season_available_cd)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(TvNewSeasonBadgeCorner))
            .background(MaterialTheme.colorScheme.primary)
            .padding(TvNewSeasonBadgePadding)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(TvNewSeasonBadgeIconSize)
        )
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
private fun DiscoveryPendingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = stringResource(R.string.tv_discovering_phone),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ActiveViewersRow(viewers: List<ActiveViewer>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        viewers.take(4).forEach { viewer ->
            ActiveViewerChip(viewer)
        }
        if (viewers.size > 4) {
            Text(
                text = "+${viewers.size - 4}",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ActiveViewerChip(viewer: ActiveViewer) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ViewerAvatar(viewer = viewer, size = 24.dp)
        Text(
            text = viewer.displayName,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun ViewerAvatar(viewer: ActiveViewer, size: androidx.compose.ui.unit.Dp) {
    when (viewer.avatarSource) {
        AvatarSource.GENERATED -> InitialsAvatar(name = viewer.displayName, size = size)
        AvatarSource.TRAKT, AvatarSource.CUSTOM -> {
            val url = viewer.avatarUrl
            if (url.isNullOrBlank()) {
                InitialsAvatar(name = viewer.displayName, size = size)
            } else {
                SubcomposeAsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(size)
                        .clip(RoundedCornerShape(percent = 50)),
                    loading = { InitialsAvatar(name = viewer.displayName, size = size) },
                    error = { InitialsAvatar(name = viewer.displayName, size = size) }
                )
            }
        }
    }
}
