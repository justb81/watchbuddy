package com.justb81.watchbuddy.tv.ui.showdetail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.StreamingService
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper

private const val TMDB_POSTER_WIDTH = 500

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
    val services by viewModel.availableServices.collectAsState(initial = emptyList())
    val watchNowFocus = remember { FocusRequester() }

    LaunchedEffect(entry.show.ids.trakt) {
        viewModel.loadNextEpisode(enriched)
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
            services = services,
            watchNowFocus = watchNowFocus,
            onWatchNow = {
                val deepLink = viewModel.resolveDeepLink(entry, services)
                if (deepLink != null) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onRecapClick = onRecapClick,
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
    services: List<StreamingService>,
    watchNowFocus: FocusRequester,
    onWatchNow: () -> Unit,
    onRecapClick: () -> Unit,
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
                onClick = onWatchNow,
                modifier = Modifier.focusRequester(watchNowFocus),
                colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(text = stringResource(R.string.tv_watch_now), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onRecapClick) { Text(stringResource(R.string.tv_recap)) }
        }
        if (services.isNotEmpty()) {
            Text(text = stringResource(R.string.tv_available_at), fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                services.take(4).forEach { service -> MetaChip(service.name, color = MaterialTheme.colorScheme.surface) }
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
