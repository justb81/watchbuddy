package com.justb81.watchbuddy.tv.ui.scrobble

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.tv.ui.theme.extendedColors
import kotlinx.coroutines.delay

/**
 * Non-blocking overlay shown in the bottom-right corner when the scrobbler
 * detects a show with confidence < 90%.
 *
 * Auto-dismisses after 15 seconds if the user takes no action.
 * Focused by default so D-pad navigation works immediately.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ScrobbleOverlay(
    candidate: ScrobbleCandidate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val showTitle   = candidate.matchedShow?.title ?: candidate.mediaTitle
    val episodeText = candidate.matchedEpisode?.let {
        stringResource(R.string.tv_scrobble_episode, it.season, it.number)
    }

    var secondsLeft by remember { mutableIntStateOf(15) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
        onConfirm() // Auto-confirm on countdown expiry
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd
    ) {
        AnimatedVisibility(
            visible  = true,
            enter    = slideInHorizontally { it } + fadeIn(),
            exit     = slideOutHorizontally { it } + fadeOut()
        ) {
            // Box replaces tv.Surface + NonInteractiveSurfaceDefaults (not available in tv-material 1.0.0)
            Box(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 0.dp,
                                            topEnd   = 0.dp,  bottomEnd   = 0.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Trakt logo / label
                    Text(
                        text     = "Trakt",
                        fontSize = 11.sp,
                        color    = MaterialTheme.extendedColors.traktRed,
                        fontWeight = FontWeight.Bold
                    )

                    // Question
                    Text(
                        text       = stringResource(R.string.tv_scrobble_question, showTitle),
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = Color.White
                    )

                    episodeText?.let {
                        Text(
                            text     = it,
                            fontSize = 13.sp,
                            color    = Color.White.copy(alpha = 0.6f)
                        )
                    }

                    // Auto-dismiss countdown bar (material3 LinearProgressIndicator)
                    LinearProgressIndicator(
                        progress = { secondsLeft / 15f },
                        modifier = Modifier.fillMaxWidth(),
                        color    = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )

                    // Buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick  = onConfirm,
                            modifier = Modifier.weight(1f),
                            colors   = ButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(stringResource(R.string.tv_scrobble_yes), fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick  = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.tv_scrobble_no), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
