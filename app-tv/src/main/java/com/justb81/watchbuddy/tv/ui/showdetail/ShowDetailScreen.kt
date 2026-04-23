package com.justb81.watchbuddy.tv.ui.showdetail

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.ResolvedProvider
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
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
    val watchNowFocus = remember { FocusRequester() }

    LaunchedEffect(entry.show.ids.trakt) {
        viewModel.loadNextEpisode(enriched)
        viewModel.loadProviders(enriched)
        watchNowFocus.requestFocus()
    }

    val fallbackCode = remember(entry) {
        val lastSeason = entry.seasons.maxByOrNull { it.number }
        val lastEpisode = lastSeason?.episodes?.maxByOrNull { it.number }
        "S%02dE%02d".format(lastSeason?.number ?: 1, (lastEpisode?.number ?: 0) + 1)
    }
    val episodeCode = nextEpisodeUi.episodeCode ?: fallbackCode
    val episodeTitle = nextEpisodeUi.episodeName
    val imageUrl = nextEpisodeUi.stillUrl ?: TmdbImageHelper.poster(enriched.posterPath, TMDB_POSTER_WIDTH)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ShowDetailImagePanel(imageUrl, modifier = Modifier.align(Alignment.CenterStart))
        ShowDetailGradient()
        ShowDetailContent(
            entry = entry,
            episodeTitle = episodeTitle,
            episodeCode = episodeCode,
            providerState = providerState,
            watchNowFocus = watchNowFocus,
            actions = ShowDetailActions(
                onWatchNow = {
                    val firstProvider = (providerState as? ProviderListUiState.Success)
                        ?.providers?.firstOrNull()
                    val deepLink = viewModel.resolveDeepLink(entry)
                    launchProvider(context, firstProvider, deepLink)
                },
                onProviderClick = { provider ->
                    val link = viewModel.onProviderSelected(provider, entry)
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
 *
 * Stops at the first stage that resolves to an activity.
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

    if (provider?.isInstalled == true && provider.packageName != null) {
        pm.getLaunchIntentForPackage(provider.packageName)?.let { launchIntent ->
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
    entry: TraktWatchedEntry,
    episodeTitle: String?,
    episodeCode: String,
    providerState: ProviderListUiState,
    watchNowFocus: FocusRequester,
    actions: ShowDetailActions,
    modifier: Modifier = Modifier,
) {
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
            MetaChip(stringResource(R.string.tv_watched_episodes, entry.seasons.sumOf { it.episodes.size }))
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
            Button(
                onClick = actions.onWatchNow,
                modifier = Modifier.focusRequester(watchNowFocus),
                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(text = stringResource(R.string.tv_watch_now), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = actions.onRecapClick) { Text(stringResource(R.string.tv_recap)) }
        }

        AvailableOnSection(
            state = providerState,
            onProviderClick = actions.onProviderClick,
            onRetry = actions.onRetryProviders,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AvailableOnSection(
    state: ProviderListUiState,
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
                    ProviderChip(provider = provider, onClick = { onProviderClick(provider) })
                }
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
    onClick: () -> Unit,
) {
    val borderColor = when {
        provider.isLastUsed -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val borderWidth = if (provider.isLastUsed) 2.dp else 0.dp

    Card(
        onClick = onClick,
        modifier = Modifier
            .width(88.dp)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp)),
        shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
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
            Text(
                text = provider.name,
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.8f),
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
    }
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
