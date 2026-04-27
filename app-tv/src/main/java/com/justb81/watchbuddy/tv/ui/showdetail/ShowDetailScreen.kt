package com.justb81.watchbuddy.tv.ui.showdetail

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.ResolvedProvider
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper

private const val TMDB_POSTER_WIDTH = 500

private data class ShowDetailActions(
    val onWatchNow: () -> Unit,
    val onProviderClick: (ResolvedProvider) -> Unit,
    val onRetryProviders: () -> Unit,
    val onRecapClick: () -> Unit,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ShowDetailScreen(
    enriched: EnrichedShowEntry,
    onRecapClick: () -> Unit,
    onBack: () -> Unit,
    viewModel: ShowDetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val entry = enriched.entry
    val nextEpisodeUi by viewModel.nextEpisode.collectAsState()
    val providerState by viewModel.providers.collectAsState()
    val deepLinks by viewModel.deepLinks.collectAsState()
    val watchNowFocus = remember { FocusRequester() }

    LaunchedEffect(entry.show.ids.trakt) {
        viewModel.loadNextEpisode(enriched)
        viewModel.loadProviders(enriched)
        watchNowFocus.requestFocus()
    }

    // Trigger deep link resolution once providers are loaded
    LaunchedEffect(providerState) {
        if (providerState is ProviderListUiState.Success) {
            viewModel.loadDeepLinks(enriched)
        }
    }

    val fallbackCode = remember(entry) {
        val lastSeason = entry.seasons.maxByOrNull { it.number }
        val lastEpisode = lastSeason?.episodes?.maxByOrNull { it.number }
        "S%02dE%02d".format(lastSeason?.number ?: 1, (lastEpisode?.number ?: 0) + 1)
    }
    val episodeCode = nextEpisodeUi.episodeCode ?: fallbackCode
    val episodeTitle = nextEpisodeUi.episodeName
    val imageUrl = nextEpisodeUi.stillUrl ?: TmdbImageHelper.poster(enriched.posterPath, TMDB_POSTER_WIDTH)

    val topProviderDeepLink = viewModel.resolveTopProviderDeepLink()
    val topProviderState = (providerState as? ProviderListUiState.Success)
        ?.providers?.firstOrNull()
        ?.let { deepLinks[it.providerId] }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ShowDetailImagePanel(imageUrl, modifier = Modifier.align(Alignment.CenterStart))
        ShowDetailGradient()
        ShowDetailContent(
            enriched = enriched,
            episodeTitle = episodeTitle,
            episodeCode = episodeCode,
            providerState = providerState,
            deepLinks = deepLinks,
            topProviderDeepLinkState = topProviderState,
            watchNowFocus = watchNowFocus,
            actions = ShowDetailActions(
                onWatchNow = {
                    val firstProvider = (providerState as? ProviderListUiState.Success)
                        ?.providers?.firstOrNull()
                    launchProvider(context, firstProvider, topProviderDeepLink)
                },
                onProviderClick = { provider ->
                    val link = viewModel.onProviderSelected(provider, enriched)
                    launchProvider(context, provider, link)
                },
                onRetryProviders = { viewModel.loadProviders(enriched) },
                onRecapClick = onRecapClick,
            ),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(32.dp)
        ) {
            Text(stringResource(R.string.tv_back_arrow))
        }
    }
}

/**
 * Three-stage launch cascade for a streaming provider:
 *   1. Deep-link intent (with [ResolvedProvider.packageName] targeted when known).
 *   2. If the provider is detected as installed, fall through to the app's main
 *      activity via [android.content.pm.PackageManager.getLaunchIntentForPackage].
 *   3. Otherwise open [ResolvedProvider.tmdbPageUrl] in the system browser.
 */
private fun launchProvider(context: Context, provider: ResolvedProvider?, deepLink: String?) {
    val pm = context.packageManager

    if (deepLink != null && deepLink.isNotBlank()) {
        val uri = deepLink.toUri()
        val targetedIntent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        provider?.packageName?.let { targetedIntent.setPackage(it) }
        if (targetedIntent.resolveActivity(pm) != null) {
            context.startActivity(targetedIntent)
            return
        }
        if (provider?.packageName != null) {
            val untargetedIntent = Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (untargetedIntent.resolveActivity(pm) != null) {
                context.startActivity(untargetedIntent)
                return
            }
        }
    }

    val installedPackage = provider?.takeIf { it.isInstalled }?.packageName
    if (installedPackage != null) {
        pm.getLaunchIntentForPackage(installedPackage)?.let { launchIntent ->
            context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
    }

    provider?.tmdbPageUrl?.let { pageUrl ->
        val fallback = Intent(Intent.ACTION_VIEW, pageUrl.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (fallback.resolveActivity(pm) != null) {
            context.startActivity(fallback)
        }
    }
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
private fun ShowDetailContent(
    enriched: EnrichedShowEntry,
    episodeTitle: String?,
    episodeCode: String,
    providerState: ProviderListUiState,
    deepLinks: Map<Int, DeepLinkState>,
    topProviderDeepLinkState: DeepLinkState?,
    watchNowFocus: FocusRequester,
    actions: ShowDetailActions,
    modifier: Modifier = Modifier,
) {
    val entry = enriched.entry
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
        Text(
            text = episodeTitle ?: stringResource(R.string.tv_next_episode),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(text = episodeCode, fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WatchNowButton(
                deepLinkState = topProviderDeepLinkState,
                onClick = actions.onWatchNow,
                focusRequester = watchNowFocus,
            )
            OutlinedButton(onClick = actions.onRecapClick) { Text(stringResource(R.string.tv_recap)) }
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
    deepLinkState: DeepLinkState?,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
) {
    when (deepLinkState) {
        is DeepLinkState.Loading -> {
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
        is DeepLinkState.Available -> {
            Button(
                onClick = onClick,
                modifier = Modifier.focusRequester(focusRequester),
                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(text = stringResource(R.string.tv_watch_now), fontWeight = FontWeight.Bold)
            }
        }
        is DeepLinkState.Unavailable -> {
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.focusRequester(focusRequester),
                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(text = stringResource(R.string.tv_watch_now_unavailable), fontWeight = FontWeight.Bold)
            }
        }
        null -> {
            Button(
                onClick = onClick,
                modifier = Modifier.focusRequester(focusRequester),
                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(text = stringResource(R.string.tv_watch_now), fontWeight = FontWeight.Bold)
            }
        }
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
    val isEnabled = deepLinkState is DeepLinkState.Available || deepLinkState == null
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
                val labelText = when (deepLinkState) {
                    is DeepLinkState.Unavailable -> stringResource(R.string.tv_provider_no_deep_link)
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
