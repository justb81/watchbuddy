package com.justb81.watchbuddy.phone.ui.home

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.justb81.watchbuddy.phone.permissions.BluetoothAdvertisePermission
import com.justb81.watchbuddy.phone.permissions.NotificationPermission
import com.justb81.watchbuddy.phone.permissions.rememberBluetoothAdvertisePermissionRequest
import com.justb81.watchbuddy.phone.permissions.rememberNotificationPermissionRequest
import com.justb81.watchbuddy.phone.ui.util.relativeDate
import com.justb81.watchbuddy.phone.ui.util.relativeTime

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

    // BLE advertising is a best-effort fallback discovery channel for networks
    // where mDNS doesn't work (AP isolation, mesh VLANs, multicast filtering).
    // If the user denies the permission, we still start the service — NSD
    // continues to work on most networks — so the callback always proceeds.
    val requestBluetoothAdvertisePermission = rememberBluetoothAdvertisePermissionRequest { _ ->
        viewModel.toggleWatchingTv(true)
    }

    val requestNotificationPermission = rememberNotificationPermissionRequest { granted ->
        if (granted) {
            if (BluetoothAdvertisePermission.isGranted(context)) {
                viewModel.toggleWatchingTv(true)
            } else {
                requestBluetoothAdvertisePermission()
            }
        } else {
            showNotificationRationale = true
        }
    }

    val handleToggleWatchingTv: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            viewModel.toggleWatchingTv(false)
        } else if (!NotificationPermission.isGranted(context)) {
            requestNotificationPermission()
        } else if (!BluetoothAdvertisePermission.isGranted(context)) {
            requestBluetoothAdvertisePermission()
        } else {
            viewModel.toggleWatchingTv(true)
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
                            onToggleWatchingTv = handleToggleWatchingTv,
                            isRefreshing = uiState.isSyncing,
                            onRefresh = { viewModel.sync() }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    onShowClick: (Int) -> Unit,
    onToggleWatchingTv: (Boolean) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val orientation = LocalConfiguration.current.orientation
    val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE

    val continueWatching = state.continueWatching
    val allOthers = state.allShows
    var allShowsExpanded by rememberSaveable { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Render the toggle even when disabled so the user can see *why* —
            // credential-missing and Wi-Fi-missing deserve different messages.
            val disabledReason = when {
                !state.canWatch -> stringResource(R.string.home_watching_tv_disabled_reason)
                !state.isOnWifi -> stringResource(R.string.home_watching_tv_disabled_reason_no_wifi)
                else -> null
            }
            WatchingTvToggle(
                isWatching = state.isWatchingTv,
                enabled = state.canStartCompanion,
                disabledReason = disabledReason,
                onToggle = onToggleWatchingTv
            )
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
                            ShelfCard(
                                enriched,
                                state.progress[enriched.entry.show.ids.trakt],
                                state.hasNewSeason[enriched.entry.show.ids.trakt] == true,
                                onShowClick
                            )
                        }
                    }
                }
            } else {
                items(continueWatching, key = { it.entry.show.ids.trakt ?: it.entry.show.title }) { enriched ->
                    ShowRowCard(
                        enriched,
                        state.progress[enriched.entry.show.ids.trakt],
                        state.hasNewSeason[enriched.entry.show.ids.trakt] == true,
                        onShowClick
                    )
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
                            ShelfCard(
                                enriched,
                                state.progress[enriched.entry.show.ids.trakt],
                                state.hasNewSeason[enriched.entry.show.ids.trakt] == true,
                                onShowClick
                            )
                        }
                    }
                }
            } else {
                items(allOthers, key = { it.entry.show.ids.trakt ?: it.entry.show.title }) { enriched ->
                    ShowRowCard(
                        enriched,
                        state.progress[enriched.entry.show.ids.trakt],
                        state.hasNewSeason[enriched.entry.show.ids.trakt] == true,
                        onShowClick
                    )
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
    enabled: Boolean,
    disabledReason: String?,
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
                    text = disabledReason
                        ?: stringResource(R.string.home_watching_tv_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isWatching)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Switch(
                checked = isWatching,
                onCheckedChange = onToggle,
                enabled = enabled
            )
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
    hasNewSeason: Boolean,
    onShowClick: (Int) -> Unit
) {
    val entry = enriched.entry
    val posterUrl = TmdbImageHelper.poster(enriched.posterPath, 300)
    val cardShape = RoundedCornerShape(12.dp)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasNewSeason) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, cardShape)
                else Modifier
            )
            .clickable { entry.show.ids.trakt?.let(onShowClick) },
        shape    = cardShape,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text       = entry.show.title,
                        style      = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onSurface,
                        modifier   = Modifier.weight(1f, fill = false)
                    )
                    if (hasNewSeason) NewSeasonBadge()
                }
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
    hasNewSeason: Boolean,
    onShowClick: (Int) -> Unit
) {
    val entry = enriched.entry
    val posterUrl = TmdbImageHelper.poster(enriched.posterPath, 500)

    Box(
        modifier = Modifier
            .width(180.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (hasNewSeason) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier
            )
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
        if (hasNewSeason) {
            Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                NewSeasonBadge()
            }
        }
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            ProgressBadge(progress)
        }
    }
}

@Composable
private fun InProgressLines(progress: ShowProgress.InProgress, compact: Boolean, now: Long) {
    val context = LocalContext.current
    val labelColor = if (compact) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = if (compact) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall
    val valueStyle = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    if (compact) {
        Text(
            text = "${progress.latestWatchedLabel} · ${relativeTime(context, progress.latestWatched, now)}",
            style = labelStyle, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${progress.lastAiredLabel} · ${relativeDate(context, progress.lastAired, now)}",
            style = labelStyle, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(space = 8.dp)) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_label_last_watched),
                    style = labelStyle,
                    color = labelColor
                )
                Text(
                    text = "${progress.latestWatchedLabel} · ${relativeTime(context, progress.latestWatched, now)}",
                    style = valueStyle,
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_label_last_aired),
                    style = labelStyle,
                    color = labelColor
                )
                Text(
                    text = "${progress.lastAiredLabel} · ${relativeDate(context, progress.lastAired, now)}",
                    style = valueStyle,
                    color = valueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProgressLines(progress: ShowProgress?, compact: Boolean = false) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val singleLineStyle = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    val singleLineColor = if (compact) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    when (progress) {
        is ShowProgress.InProgress -> InProgressLines(progress, compact, now)
        is ShowProgress.CaughtUpAiring -> {
            if (progress.latestWatchedLabel != null && progress.latestWatched != null) {
                Text(
                    text = stringResource(
                        R.string.home_last_watched,
                        progress.latestWatchedLabel!!,
                        relativeTime(context, progress.latestWatched!!, now)
                    ),
                    style = singleLineStyle, color = singleLineColor, maxLines = 1
                )
            }
            Text(
                text = stringResource(
                    R.string.home_next_episode,
                    progress.nextAiredLabel.substringAfter('S').substringBefore('E').toIntOrNull() ?: 0,
                    progress.nextAiredLabel.substringAfter('E').toIntOrNull() ?: 0
                ),
                style = singleLineStyle, color = singleLineColor, maxLines = 1
            )
        }
        is ShowProgress.CaughtUpEnded -> {
            progress.latestWatched?.let {
                Text(
                    text = stringResource(R.string.home_show_ended_caught_up, relativeTime(context, it, now)),
                    style = singleLineStyle, color = singleLineColor, maxLines = 1
                )
            }
        }
        is ShowProgress.NotStarted -> {
            progress.nextAiredLabel?.let { label ->
                val s = label.substringAfter('S').substringBefore('E').toIntOrNull() ?: 0
                val e = label.substringAfter('E').toIntOrNull() ?: 0
                Text(
                    text = stringResource(R.string.home_next_episode, s, e),
                    style = singleLineStyle, color = singleLineColor, maxLines = 1
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
private fun NewSeasonBadge() {
    val description = stringResource(R.string.home_new_season_available_cd)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(4.dp)
            .semantics { contentDescription = description }
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(12.dp)
        )
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
