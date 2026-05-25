package com.justb81.watchbuddy.tv.ui.showdetail

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.deeplink.ProviderDeepLinkRewriter
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.ResolvedProvider
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.tv.ui.theme.TvSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TMDB_POSTER_WIDTH = 500
private const val MARK_WATCHED_TOAST_DELAY_MS = 3_000L

private data class ShowDetailActions(
    val onWatchNow: () -> Unit,
    val onProviderClick: (ResolvedProvider) -> Unit,
    val onRetryProviders: () -> Unit,
    val onRecapClick: () -> Unit,
    val onMarkWatched: () -> Unit,
    val onAllEpisodesClick: () -> Unit,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun ShowDetailScreen(
    enriched: EnrichedShowEntry,
    onRecapClick: () -> Unit,
    onAllEpisodesClick: () -> Unit,
    onBack: () -> Unit,
    viewModel: ShowDetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val ready = uiState as? ShowDetailUiState.Ready

    // Transparently switch to the optimistically-advanced entry once a mark-watched succeeds.
    val effectiveEntry = ready?.advancedEntry ?: enriched
    val entry = effectiveEntry.entry
    val nextEpisodeUi = ready?.nextEpisode ?: NextEpisodeUiState()
    val providerState = ready?.providers ?: ProviderListUiState.Loading
    val deepLinks = ready?.deepLinks ?: emptyMap()
    val watchNowState = ready?.watchNow ?: WatchNowState.Loading
    val markState = ready?.markWatched ?: MarkWatchedState.Idle

    val scope = rememberCoroutineScope()
    val watchNowFocus = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }

    val markWatchedNoPhonesMsg = stringResource(R.string.tv_mark_watched_no_phones)
    val markWatchedErrorMsg = stringResource(R.string.tv_mark_watched_error)
    val openFailedMsg = stringResource(R.string.tv_provider_open_failed)

    ShowDetailEffects(
        viewModel = viewModel,
        enriched = enriched,
        providerState = providerState,
        watchNowFocus = watchNowFocus,
    )

    // One-shot toast for mark-watched feedback, then acknowledge to reset to Idle.
    LaunchedEffect(markState) {
        val msg = when (markState) {
            MarkWatchedState.NoPhones -> markWatchedNoPhonesMsg
            MarkWatchedState.Error -> markWatchedErrorMsg
            else -> null
        } ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        delay(MARK_WATCHED_TOAST_DELAY_MS)
        viewModel.acknowledgeMarkWatchedFeedback()
    }

    val imageUrl = nextEpisodeUi.stillUrl ?: TmdbImageHelper.poster(effectiveEntry.posterPath, TMDB_POSTER_WIDTH)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ShowDetailImagePanel(imageUrl, modifier = Modifier.align(Alignment.CenterStart))
        ShowDetailGradient()
        ShowDetailContent(
            enriched = effectiveEntry,
            nextEpisode = nextEpisodeUi,
            providerState = providerState,
            deepLinks = deepLinks,
            watchNowState = watchNowState,
            markState = markState,
            watchNowFocus = watchNowFocus,
            actions = ShowDetailActions(
                onWatchNow = {
                    if (watchNowState is WatchNowState.Available) {
                        val firstProvider = (providerState as? ProviderListUiState.Success)
                            ?.providers?.firstOrNull()
                        launchProvider(context, firstProvider, watchNowState.url) {
                            scope.launch { snackbarHostState.showSnackbar(openFailedMsg) }
                        }
                    }
                },
                onProviderClick = { provider ->
                    val link = viewModel.onProviderSelected(provider, effectiveEntry)
                    launchProvider(context, provider, link) {
                        scope.launch { snackbarHostState.showSnackbar(openFailedMsg) }
                    }
                },
                onRetryProviders = { viewModel.loadProviders(effectiveEntry) },
                onRecapClick = onRecapClick,
                onMarkWatched = { viewModel.markCurrentEpisodeWatched(effectiveEntry) },
                onAllEpisodesClick = onAllEpisodesClick,
            ),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(TvSpacing.backButtonInset)
        ) {
            Text(stringResource(R.string.tv_back_arrow))
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}

/**
 * Tries to launch a targeted then untargeted [Intent.ACTION_VIEW] for [candidateUri].
 * Returns `true` and calls [Context.startActivity] on the first intent that resolves;
 * returns `false` when neither resolves.
 */
@Suppress("DEPRECATION")
private fun tryCandidateUri(
    context: Context,
    pm: PackageManager,
    candidateUri: Uri,
    packageName: String?,
): Boolean {
    val targeted = Intent(Intent.ACTION_VIEW, candidateUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    packageName?.let { targeted.setPackage(it) }
    if (pm.resolveActivity(targeted, 0) != null) {
        context.startActivity(targeted)
        return true
    }
    if (packageName != null) {
        val untargeted = Intent(Intent.ACTION_VIEW, candidateUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pm.resolveActivity(untargeted, 0) != null) {
            context.startActivity(untargeted)
            return true
        }
    }
    return false
}

/**
 * Provider launch cascade: tries URI candidates first, then app home, then browser.
 *
 * Stages are tried in order; the function returns as soon as one succeeds:
 *   1. For each URI candidate produced by [ProviderDeepLinkRewriter] (provider-specific
 *      rewrites, e.g. `nflx://` scheme for Netflix), try a targeted
 *      [Intent.ACTION_VIEW] pinned to [ResolvedProvider.packageName] (when known),
 *      then an untargeted [Intent.ACTION_VIEW]. This ensures apps like Netflix that
 *      do not register intent filters for the raw JustWatch `standardWebURL` (e.g.
 *      `https://www.netflix.com/watch/<id>`) are still opened on the correct episode
 *      via the native scheme (`nflx://www.netflix.com/title/<id>`).
 *   2. [android.content.pm.PackageManager.getLaunchIntentForPackage] for
 *      [ResolvedProvider.packageName] — tried unconditionally, regardless of
 *      [ResolvedProvider.isInstalled], because the catalog entry may carry a stale or
 *      mismatched package name while the real app is present on the device.
 *   3. Open [ResolvedProvider.tmdbPageUrl] in the system browser.
 *
 * If every stage fails (no activity resolves) [onFailure] is invoked so the caller can
 * show a user-facing error message. The function never lets the system "you don't have
 * an app for that" dialog surface to the user.
 */
internal fun launchProvider(
    context: Context,
    provider: ResolvedProvider?,
    deepLink: String?,
    onFailure: () -> Unit = {},
) {
    val pm = context.packageManager

    if (deepLink != null && deepLink.isNotBlank()) {
        val candidates = if (provider != null) {
            ProviderDeepLinkRewriter.rewrite(provider.providerId, deepLink)
        } else {
            listOf(deepLink)
        }
        for (candidate in candidates) {
            if (tryCandidateUri(context, pm, candidate.toUri(), provider?.packageName)) return
        }
    }

    // Stage 2: try getLaunchIntentForPackage regardless of isInstalled — the catalog
    // entry may be stale or carry a region-variant package name, but the app may still
    // be present. getLaunchIntentForPackage returns null when the package is absent, so
    // there is no risk of surfacing a system "no app" dialog here.
    provider?.packageName?.let { pkg ->
        pm.getLaunchIntentForPackage(pkg)?.let { launchIntent ->
            context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
    }

    provider?.tmdbPageUrl?.let { pageUrl ->
        val fallback = Intent(Intent.ACTION_VIEW, pageUrl.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        if (pm.resolveActivity(fallback, 0) != null) {
            context.startActivity(fallback)
            return
        }
    }

    onFailure()
}

@Composable
private fun ShowDetailImagePanel(imageUrl: String?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxHeight().fillMaxWidth(0.4f)) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
    }
}

@Composable
private fun ShowDetailGradient() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                    startX = 400f,
                    endX = 900f
                )
            )
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
private fun ShowDetailContent(
    enriched: EnrichedShowEntry,
    nextEpisode: NextEpisodeUiState,
    providerState: ProviderListUiState,
    deepLinks: Map<Int, DeepLinkState>,
    watchNowState: WatchNowState,
    markState: MarkWatchedState,
    watchNowFocus: FocusRequester,
    actions: ShowDetailActions,
    modifier: Modifier = Modifier,
) {
    val entry = enriched.entry
    val fallbackCode = remember(entry) {
        val lastSeason = entry.seasons.maxByOrNull { it.number }
        val lastEpisode = lastSeason?.episodes?.maxByOrNull { it.number }
        "S%02dE%02d".format(lastSeason?.number ?: 1, (lastEpisode?.number ?: 0) + 1)
    }
    val episodeCode = nextEpisode.episodeCode ?: fallbackCode
    val episodeTitle = nextEpisode.episodeName
    Column(
        modifier = modifier.fillMaxWidth(0.55f).padding(end = 64.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = entry.show.title,
            fontSize = 40.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            entry.show.year?.let { MetaChip(it.toString()) }
            val watchedCount = entry.seasons.sumOf { it.episodes.size }
            MetaChip(pluralStringResource(R.plurals.tv_watched_episodes, watchedCount, watchedCount))
        }
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(R.string.tv_next_episode), fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))
        // AnimatedContent slides the episode info out left and slides new episode in from the right
        // whenever the episodeCode changes (driven by advancedEntry optimistic update).
        AnimatedContent(
            targetState = episodeCode,
            transitionSpec = {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it } + fadeOut())
            },
            label = "next-episode",
        ) { code ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = episodeTitle ?: stringResource(R.string.tv_next_episode),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = code, fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WatchNowButton(
                state = watchNowState,
                onClick = actions.onWatchNow,
                focusRequester = watchNowFocus,
            )
            MarkWatchedButton(
                state = markState,
                hasNextEpisode = nextEpisode.episodeCode != null,
                onClick = actions.onMarkWatched,
            )
            OutlinedButton(onClick = actions.onRecapClick) { Text(stringResource(R.string.tv_recap)) }
            OutlinedButton(onClick = actions.onAllEpisodesClick) {
                Text(stringResource(R.string.tv_all_episodes))
            }
        }

        AvailableOnSection(
            state = providerState,
            deepLinks = deepLinks,
            onProviderClick = actions.onProviderClick,
            onRetry = actions.onRetryProviders,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WatchNowButton(
    state: WatchNowState,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
) {
    when (state) {
        is WatchNowState.Loading -> {
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.focusRequester(focusRequester),
                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(text = stringResource(R.string.tv_watch_now), fontWeight = FontWeight.Bold)
            }
        }
        is WatchNowState.Available -> {
            Button(
                onClick = onClick,
                modifier = Modifier.focusRequester(focusRequester),
                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(text = stringResource(R.string.tv_watch_now), fontWeight = FontWeight.Bold)
            }
        }
        is WatchNowState.Unavailable -> {
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.focusRequester(focusRequester),
                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(text = stringResource(R.string.tv_watch_now_unavailable), fontWeight = FontWeight.Bold)
            }
        }
        is WatchNowState.NoProvider -> {
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.focusRequester(focusRequester),
                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(text = stringResource(R.string.tv_watch_now_no_provider), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * "Mark as watched" button for the current next-unwatched episode.
 *
 * - Disabled while [state] is [MarkWatchedState.Loading] or [hasNextEpisode] is false.
 * - Shows a small spinner inline while [state] is [MarkWatchedState.Loading].
 * - Error / no-phones states are surfaced via one-shot toasts in [ShowDetailScreen];
 *   the button renders as Idle in those states (they reset back to Idle after the toast).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MarkWatchedButton(
    state: MarkWatchedState,
    hasNextEpisode: Boolean,
    onClick: () -> Unit,
) {
    val isLoading = state is MarkWatchedState.Loading
    OutlinedButton(
        onClick = onClick,
        enabled = !isLoading && hasNextEpisode,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(R.string.tv_mark_watched))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AvailableOnSection(
    state: ProviderListUiState,
    deepLinks: Map<Int, DeepLinkState>,
    onProviderClick: (ResolvedProvider) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is ProviderListUiState.Loading -> {
            Text(
                text = stringResource(R.string.tv_available_at),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.4f),
            )
        }

        is ProviderListUiState.Success -> {
            Text(
                text = stringResource(R.string.tv_available_at),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.4f),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.providers, key = { it.providerId }) { provider ->
                    val linkState = deepLinks[provider.providerId]
                    ProviderChip(
                        provider = provider,
                        deepLinkState = linkState,
                        onClick = { onProviderClick(provider) },
                    )
                }
            }
            val hasAnyDeepLink = deepLinks.values.any { it is DeepLinkState.Available }
            if (hasAnyDeepLink) {
                JustWatchAttributionBadge()
            }
        }

        is ProviderListUiState.Empty -> {
            Text(
                text = stringResource(R.string.tv_not_available_region),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.4f),
            )
        }

        is ProviderListUiState.Error -> {
            OutlinedButton(onClick = onRetry) {
                Text(
                    text = stringResource(R.string.tv_providers_retry),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ProviderLogo(provider: ResolvedProvider) {
    if (provider.logoPath != null) {
        AsyncImage(
            model = provider.logoPath,
            contentDescription = provider.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = provider.name.take(2).uppercase(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProviderChip(
    provider: ResolvedProvider,
    deepLinkState: DeepLinkState?,
    onClick: () -> Unit,
) {
    val borderColor = when {
        provider.isLastUsed -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val borderWidth = if (provider.isLastUsed) 2.dp else 0.dp
    val isEnabled = deepLinkState is DeepLinkState.Available ||
        deepLinkState == null ||
        (deepLinkState is DeepLinkState.Unavailable && provider.isInstalled)
    val focusedContainerColor = if (isEnabled) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        onClick = if (isEnabled) onClick else ({}),
        modifier = Modifier
            .width(88.dp)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp)),
        shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = focusedContainerColor,
        ),
        scale = if (isEnabled) CardDefaults.scale(focusedScale = 1.06f) else CardDefaults.scale(1f),
    ) {
        Box {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ProviderLogo(provider)
                val labelText = when {
                    deepLinkState is DeepLinkState.Unavailable && provider.isInstalled ->
                        stringResource(R.string.tv_provider_open_app)
                    deepLinkState is DeepLinkState.Unavailable ->
                        stringResource(R.string.tv_provider_no_deep_link)
                    else -> provider.name
                }
                Text(
                    text = labelText,
                    fontSize = 10.sp,
                    color = if (isEnabled) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (provider.isLastUsed) {
                    Text(
                        text = stringResource(R.string.tv_provider_last_used_label),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
            // Spinner overlay while deep link is being resolved
            if (deepLinkState is DeepLinkState.Loading) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun JustWatchAttributionBadge() {
    Text(
        text = stringResource(R.string.tv_justwatch_attribution),
        fontSize = 10.sp,
        color = Color.White.copy(alpha = 0.4f),
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun MetaChip(text: String, color: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ShowDetailEffects(
    viewModel: ShowDetailViewModel,
    enriched: EnrichedShowEntry,
    providerState: ProviderListUiState,
    watchNowFocus: FocusRequester,
) {
    LaunchedEffect(enriched.entry.show.ids.trakt) {
        viewModel.loadNextEpisode(enriched)
        viewModel.loadProviders(enriched)
        watchNowFocus.requestFocus()
    }
    LaunchedEffect(providerState) {
        if (providerState is ProviderListUiState.Success) {
            viewModel.loadDeepLinks(enriched)
        }
    }
}
