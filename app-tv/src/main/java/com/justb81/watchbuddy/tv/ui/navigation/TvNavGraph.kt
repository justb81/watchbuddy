package com.justb81.watchbuddy.tv.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.tv.ui.diagnostics.TvDiagnosticsScreen
import com.justb81.watchbuddy.tv.ui.home.TvHomeScreen
import com.justb81.watchbuddy.tv.ui.recap.RecapScreen
import com.justb81.watchbuddy.tv.ui.scrobble.ScrobbleOverlay
import com.justb81.watchbuddy.tv.ui.scrobble.ScrobbleViewModel
import com.justb81.watchbuddy.tv.ui.settings.TvSettingsScreen
import com.justb81.watchbuddy.tv.ui.showdetail.ShowDetailScreen

@Composable
fun TvNavGraph() {
    val navController = rememberNavController()

    // Shared state: currently selected show (passed between Home → Detail → Recap)
    var selectedEntry by remember { mutableStateOf<EnrichedShowEntry?>(null) }

    NavHost(
        navController    = navController,
        startDestination = TvRoute.Home.route
    ) {
        composable(TvRoute.Home.route) {
            TvHomeScreen(
                onShowClick = { enriched ->
                    selectedEntry = enriched
                    navController.navigate(TvRoute.ShowDetail.route)
                },
                onSettingsClick = {
                    navController.navigate(TvRoute.Settings.route)
                }
            )
        }

        composable(TvRoute.Settings.route) {
            TvSettingsScreen(
                onBack = { navController.popBackStack() },
                onDiagnosticsClick = {
                    navController.navigate(TvRoute.Diagnostics.route)
                }
            )
        }

        composable(TvRoute.ShowDetail.route) {
            val enriched = selectedEntry
            if (enriched != null) {
                ShowDetailScreen(
                    enriched     = enriched,
                    onRecapClick = { navController.navigate(TvRoute.Recap.route) },
                    onBack       = { navController.popBackStack() }
                )
            }
        }

        composable(TvRoute.Recap.route) {
            val enriched = selectedEntry
            if (enriched != null) {
                RecapScreen(
                    traktShowId      = enriched.entry.show.ids.trakt ?: 0,
                    showTitle        = enriched.entry.show.title,
                    fallbackSynopsis = stringResource(R.string.tv_no_description),
                    onClose          = { navController.popBackStack() },
                    onWatchNow       = {
                        // Pop back to detail, then trigger deep link
                        navController.popBackStack(TvRoute.ShowDetail.route, inclusive = false)
                    }
                )
            }
        }

        composable(TvRoute.Diagnostics.route) {
            TvDiagnosticsScreen(onBack = { navController.popBackStack() })
        }
    }

    // Scrobble overlay — isolated in its own host so ScrobbleViewModel (and its
    // transitive singletons: MediaSessionScrobbler → PhoneDiscoveryManager) are
    // only instantiated once the Nav tree is up and running. Previously this
    // viewModel was requested at the top of TvNavGraph, which meant any
    // exception inside those singletons' constructors would throw during the
    // very first composition and prevent the app from ever drawing a frame.
    ScrobbleOverlayHost()
}

@Composable
private fun ScrobbleOverlayHost() {
    val vm: ScrobbleViewModel = hiltViewModel()
    val pending by vm.pendingCandidate.collectAsState()
    pending?.let { candidate ->
        ScrobbleOverlay(
            candidate = candidate,
            onConfirm = { vm.confirmScrobble() },
            onDismiss = { vm.dismissScrobble() }
        )
    }
}
