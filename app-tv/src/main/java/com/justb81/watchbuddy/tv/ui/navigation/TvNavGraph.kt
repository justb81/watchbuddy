package com.justb81.watchbuddy.tv.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.tv.ui.home.TvHomeScreen
import com.justb81.watchbuddy.tv.ui.recap.RecapScreen
import com.justb81.watchbuddy.tv.ui.scrobble.ScrobbleOverlay
import com.justb81.watchbuddy.tv.ui.showdetail.ShowDetailScreen
import com.justb81.watchbuddy.tv.ui.userselect.UserSelectScreen
import com.justb81.watchbuddy.core.model.ScrobbleCandidate

sealed class TvRoute(val route: String) {
    object Home       : TvRoute("tv_home")
    object UserSelect : TvRoute("tv_user_select")
    object ShowDetail : TvRoute("tv_show_detail")
    object Recap      : TvRoute("tv_recap")
}

@Composable
fun TvNavGraph() {
    val navController = rememberNavController()

    // Shared state: currently selected show (passed between Home → Detail → Recap)
    var selectedEntry by remember { mutableStateOf<TraktWatchedEntry?>(null) }

    // Scrobble overlay state — shown on top of any screen
    var scrobbleCandidate by remember { mutableStateOf<ScrobbleCandidate?>(null) }

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
    }

    // Scrobble overlay — renders on top of everything
    scrobbleCandidate?.let { candidate ->
        ScrobbleOverlay(
            candidate = candidate,
            onConfirm = {
                // TODO: call scrobbler.autoScrobble(candidate)
                scrobbleCandidate = null
            },
            onDismiss = { scrobbleCandidate = null }
        )
    }
}
