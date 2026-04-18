package com.justb81.watchbuddy.phone.ui.home

import android.content.res.Configuration
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.CrashReporter
import com.justb81.watchbuddy.core.logging.DiagnosticShare
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.ScrobbleAction
import com.justb81.watchbuddy.core.model.ScrobbleDisplayEvent
import com.justb81.watchbuddy.core.progress.ShowProgress
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.phone.permissions.NotificationPermission
import com.justb81.watchbuddy.phone.permissions.rememberNotificationPermissionRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit,
    onConnectClick: () -> Unit,
    onShowClick: (traktShowId: Int) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    // Polled once per composition; the diagnostic banner doesn't need real-time updates
    // and this avoids adding Flow plumbing to HomeViewModel just for a debug surface.
    var pendingReports by remember { mutableStateOf(CrashReporter.listReports(context).size) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var showNotificationRationale by remember { mutableStateOf(false) }

    val requestNotificationPermission = rememberNotificationPermissionRequest { granted ->
        if (granted) {
            viewModel.toggleWatchingTv(true)
        } else {
            showNotificationRationale = true
        }
    }

    val handleToggleWatchingTv: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            viewModel.toggleWatchingTv(false)
        } else if (NotificationPermission.isGranted(context)) {
            viewModel.toggleWatchingTv(true)
        } else {
            requestNotificationPermission()
        }
    }

    if (showNotificationRationale) {
        AlertDialog(
            onDismissRequest = { showNotificationRationale = false },
            title = {
                Text(stringResource(R.string.companion_notification_permission_rationale_title))
            },
            text = {
                Text(stringResource(R.string.companion_notification_permission_rationale_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationRationale = false
                    NotificationPermission.openAppNotificationSettings(context)
                }) {
                    Text(stringResource(R.string.companion_notification_permission_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationRationale = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }

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
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.home_cd_overflow)
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diagnostics_export)) },
                                onClick = {
                                    overflowExpanded = false
                                    DiagnosticShare.launchShare(context)
                                    pendingReports = CrashReporter.listReports(context).size
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (pendingReports > 0) {
                DiagnosticsBanner(
                    reportCount = pendingReports,
                    onShare = { DiagnosticShare.launchShare(context) },
                    onDismiss = {
                        CrashReporter.clearReports(context)
                        pendingReports = 0
                    }
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
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
                            Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
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
                        HomeContent(
                            state = uiState,
                            onShowClick = onShowClick,
                            onToggleWatchingTv = handleToggleWatchingTv
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onShowClick: (Int) -> Unit,
    onToggleWatchingTv: (Boolean) -> Unit
) {
    val orientation = LocalConfiguration.current.orientation
    val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE

    val (continueWatching, allOthers) = remember(state.shows, state.progress) {
        state.shows.partition { entry ->
            val traktId = entry.entry.show.ids.trakt
            val progress = traktId?.let { state.progress[it] }
            progress is ShowProgress.InProgress || progress is ShowProgress.CaughtUpAiring
        }
    }
    var allShowsExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.canWatch) {
            item {
                WatchingTvToggle(
                    isWatching = state.isWatchingTv,
                    onToggle = onToggleWatchingTv
                )
            }
        }
        state.latestScrobbleEvent?.let { event ->
            item { NowWatchingCard(event = event) }
        }

        if (continueWatching.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_continue_watching)) }
            if (isLandscape) {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        items(continueWatching, key = { it.entry.show.ids.trakt ?: it.entry.show.title }) { enriched ->
                            ShelfCard(enriched, state.progress[enriched.entry.show.ids.trakt], onShowClick)
                        }
                    }
                }
            } else {
                items(continueWatching, key = { it.entry.show.ids.trakt ?: it.entry.show.title }) { enriched ->
                    ShowRowCard(enriched, state.progress[enriched.entry.show.ids.trakt], onShowClick)
                }
            }
        }

        item {
            CollapsibleAllShowsHeader(
                count = allOthers.size,
                expanded = allShowsExpanded,
                onToggle = { allShowsExpanded = !allShowsExpanded }
            )
        }

        if (allShowsExpanded) {
            if (isLandscape) {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        items(allOthers, key = { it.entry.show.ids.trakt ?: it.entry.show.title }) { enriched ->
                            ShelfCard(enriched, state.progress[enriched.entry.show.ids.trakt], onShowClick)
                        }
                    }
                }
            } else {
                items(allOthers, key = { it.entry.show.ids.trakt ?: it.entry.show.title }) { enriched ->
                    ShowRowCard(enriched, state.progress[enriched.entry.show.ids.trakt], onShowClick)
                }
            }
        }

        state.lastSyncTime?.let { time ->
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

@Composable
private fun CollapsibleAllShowsHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val cd = stringResource(if (expanded) R.string.home_section_collapse else R.string.home_section_expand)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text       = stringResource(R.string.home_all_shows),
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color      = MaterialTheme.colorScheme.onBackground,
            modifier   = Modifier.weight(1f)
        )
        Text(
            text  = pluralStringResource(R.plurals.home_section_all_shows_count, count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = cd,
            modifier = Modifier.rotate(rotation),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun DiagnosticsBanner(
    reportCount: Int,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.diagnostics_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(R.string.diagnostics_banner_message, reportCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                )
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.diagnostics_banner_dismiss))
            }
            Button(onClick = onShare) {
                Text(stringResource(R.string.diagnostics_banner_share))
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
                Icons.Default.PlayArrow,
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
            Switch(checked = isWatching, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun NowWatchingCard(event: ScrobbleDisplayEvent) {
    val (actionText, actionIcon) = when (event.action) {
        ScrobbleAction.START -> stringResource(R.string.home_scrobble_started) to Icons.Default.PlayArrow
        ScrobbleAction.PAUSE -> stringResource(R.string.home_scrobble_paused) to Icons.Default.PlayArrow
        ScrobbleAction.STOP  -> stringResource(R.string.home_scrobble_stopped) to Icons.Default.Check
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
private fun ShowRowCard(
    enriched: EnrichedShowEntry,
    progress: ShowProgress?,
    onShowClick: (Int) -> Unit
) {
    val entry = enriched.entry
    val posterUrl = TmdbImageHelper.poster(enriched.posterPath, 300)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { entry.show.ids.trakt?.let(onShowClick) },
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
                ProgressLines(progress)
            }

            ProgressBadge(progress)
        }
    }
}

@Composable
private fun ShelfCard(
    enriched: EnrichedShowEntry,
    progress: ShowProgress?,
    onShowClick: (Int) -> Unit
) {
    val entry = enriched.entry
    val posterUrl = TmdbImageHelper.poster(enriched.posterPath, 500)

    Box(
        modifier = Modifier
            .width(180.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .clickable { entry.show.ids.trakt?.let(onShowClick) }
    ) {
        if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = entry.show.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.outline)
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
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2
            )
            ProgressLines(progress, compact = true)
        }
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            ProgressBadge(progress)
        }
    }
}

@Composable
private fun ProgressLines(progress: ShowProgress?, compact: Boolean = false) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val textColor = if (compact)
        Color.White.copy(alpha = 0.85f)
    else
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall

    when (progress) {
        is ShowProgress.InProgress -> {
            Text(
                text = stringResource(
                    R.string.home_last_watched,
                    progress.latestWatchedLabel,
                    relativeTime(context, progress.latestWatched, now)
                ),
                style = style, color = textColor, maxLines = 1
            )
            Text(
                text = stringResource(
                    R.string.home_last_aired_episode,
                    progress.lastAiredLabel,
                    relativeDate(context, progress.lastAired, now)
                ),
                style = style, color = textColor, maxLines = 1
            )
        }
        is ShowProgress.CaughtUpAiring -> {
            if (progress.latestWatchedLabel != null && progress.latestWatched != null) {
                Text(
                    text = stringResource(
                        R.string.home_last_watched,
                        progress.latestWatchedLabel!!,
                        relativeTime(context, progress.latestWatched!!, now)
                    ),
                    style = style, color = textColor, maxLines = 1
                )
            }
            Text(
                text = stringResource(
                    R.string.home_next_episode,
                    progress.nextAiredLabel.substringAfter('S').substringBefore('E').toIntOrNull() ?: 0,
                    progress.nextAiredLabel.substringAfter('E').toIntOrNull() ?: 0
                ),
                style = style, color = textColor, maxLines = 1
            )
        }
        is ShowProgress.CaughtUpEnded -> {
            progress.latestWatched?.let {
                Text(
                    text = stringResource(R.string.home_show_ended_caught_up, relativeTime(context, it, now)),
                    style = style, color = textColor, maxLines = 1
                )
            }
        }
        is ShowProgress.NotStarted -> {
            progress.nextAiredLabel?.let { label ->
                val s = label.substringAfter('S').substringBefore('E').toIntOrNull() ?: 0
                val e = label.substringAfter('E').toIntOrNull() ?: 0
                Text(
                    text = stringResource(R.string.home_next_episode, s, e),
                    style = style, color = textColor, maxLines = 1
                )
            }
        }
        is ShowProgress.Unknown -> Unit
        null -> Unit
    }
}

@Composable
private fun ProgressBadge(progress: ShowProgress?) {
    when (progress) {
        is ShowProgress.InProgress -> {
            val n = progress.episodesBehind
            BadgePill(
                text = pluralStringResource(R.plurals.home_episodes_behind, n, n),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
        is ShowProgress.CaughtUpAiring -> BadgePill(
            text = stringResource(R.string.home_caught_up),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            leadingIcon = true
        )
        is ShowProgress.CaughtUpEnded -> BadgePill(
            text = stringResource(R.string.home_show_completed),
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant
        )
        is ShowProgress.NotStarted -> BadgePill(
            text = stringResource(R.string.home_not_started),
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
                modifier = Modifier.size(14.dp)
            )
        }
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/**
 * Formats a moment in time relative to [now]. Uses [DateUtils.getRelativeTimeSpanString]
 * when more than a day has passed, otherwise returns a localized "today / yesterday / tomorrow" string.
 */
private fun relativeTime(context: android.content.Context, moment: Instant, now: Long): String {
    val momentMs = moment.toEpochMilli()
    val delta = momentMs - now
    val dayMs = 24 * 60 * 60 * 1000L
    return when {
        delta in -dayMs..dayMs -> context.getString(R.string.home_time_today)
        delta in -2 * dayMs..-dayMs -> context.getString(R.string.home_time_yesterday)
        delta in dayMs..2 * dayMs -> context.getString(R.string.home_time_tomorrow)
        else -> DateUtils.getRelativeTimeSpanString(
            momentMs, now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
}

/**
 * Same as [relativeTime] but for TMDB `air_date` (day-precision) values interpreted as
 * local midnight to avoid "in 0 days" when the value is today.
 */
private fun relativeDate(context: android.content.Context, moment: Instant, now: Long): String {
    val today = LocalDate.now(ZoneId.systemDefault())
    val day = moment.atZone(ZoneId.systemDefault()).toLocalDate()
    return when {
        day.isEqual(today) -> context.getString(R.string.home_time_today)
        day.isEqual(today.minusDays(1)) -> context.getString(R.string.home_time_yesterday)
        day.isEqual(today.plusDays(1)) -> context.getString(R.string.home_time_tomorrow)
        else -> DateUtils.getRelativeTimeSpanString(
            moment.toEpochMilli(), now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
}
