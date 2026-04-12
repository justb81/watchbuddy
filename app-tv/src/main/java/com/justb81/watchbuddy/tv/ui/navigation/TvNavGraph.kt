package com.justb81.watchbuddy.tv.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.tv.ui.home.TvHomeScreen
import com.justb81.watchbuddy.tv.ui.recap.RecapScreen
import com.justb81.watchbuddy.tv.ui.scrobble.ScrobbleOverlay
import com.justb81.watchbuddy.tv.ui.scrobble.ScrobbleViewModel
import com.justb81.watchbuddy.tv.ui.settings.StreamingSettingsScreen
import com.justb81.watchbuddy.tv.ui.showdetail.ShowDetailScreen
import com.justb81.watchbuddy.tv.ui.userselect.UserSelectScreen

sealed class TvRoute(val route: String) {
    object Home       : TvRoute("tv_home")
    object UserSelect : TvRoute("tv_user_select")
    object ShowDetail : TvRoute("tv_show_detail")
    object Recap              : TvRoute("tv_recap")
    object StreamingSettings  : TvRoute("tv_streaming_settings")
}

@Composable
fun TvNavGraph(
    scrobbleViewModel: ScrobbleViewModel = hiltViewModel()
) {
    val navController = rememberNavController()

    // Shared state: currently selected show (passed between Home → Detail → Recap)
    var selectedEntry by remember { mutableStateOf<TraktWatchedEntry?>(null) }

    // Scrobble overlay state — driven by ScrobbleViewModel
    val pendingCandidate by scrobbleViewModel.pendingCandidate.collectAsState()

    NavHost(
        navController    = navController,
        startDestination = TvRoute.Home.route
    ) {
        composable(TvRoute.Home.route) {
            TvHomeScreen(
                onShowClick = { entry ->
                    selectedEntry = entry
                    navController.navigate(TvRoute.ShowDetail.route)
                },
                onUserSelectClick = {
                    navController.navigate(TvRoute.UserSelect.route)
                },
                onStreamingSettingsClick = {
                    navController.navigate(TvRoute.StreamingSettings.route)
                }
            )
        }

        composable(TvRoute.UserSelect.route) {
            UserSelectScreen(
                onConfirm = { selectedIds ->
                    // TODO: persist selected user IDs to session state
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(TvRoute.ShowDetail.route) {
            val entry = selectedEntry
            if (entry != null) {
                ShowDetailScreen(
                    entry        = entry,
                    onRecapClick = { navController.navigate(TvRoute.Recap.route) },
                    onBack       = { navController.popBackStack() }
                )
            }
        }

        composable(TvRoute.Recap.route) {
            val entry = selectedEntry
            if (entry != null) {
                RecapScreen(
                    traktShowId      = entry.show.ids.trakt ?: 0,
                    showTitle        = entry.show.title,
                    fallbackSynopsis = stringResource(R.string.tv_no_description),
                    onClose          = { navController.popBackStack() },
                    onWatchNow       = {
                        // Pop back to detail, then trigger deep link
                        navController.popBackStack(TvRoute.ShowDetail.route, inclusive = false)
                    }
                )
            }
        }

        composable(TvRoute.StreamingSettings.route) {
            StreamingSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }

    // Scrobble overlay — renders on top of everything
    pendingCandidate?.let { candidate ->
        ScrobbleOverlay(
            candidate = candidate,
            onConfirm = { scrobbleViewModel.confirmScrobble() },
            onDismiss = { scrobbleViewModel.dismissScrobble() }
        )
    }
}
