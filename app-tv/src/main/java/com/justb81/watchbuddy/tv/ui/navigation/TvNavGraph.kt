package com.justb81.watchbuddy.tv.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.tv.data.UserSessionRepository
import com.justb81.watchbuddy.tv.scrobbler.MediaSessionScrobbler
import com.justb81.watchbuddy.tv.ui.home.TvHomeScreen
import com.justb81.watchbuddy.tv.ui.recap.RecapScreen
import com.justb81.watchbuddy.tv.ui.scrobble.ScrobbleOverlay
import com.justb81.watchbuddy.tv.ui.showdetail.ShowDetailScreen
import com.justb81.watchbuddy.tv.ui.userselect.UserSelectScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class TvRoute(val route: String) {
    object Home       : TvRoute("tv_home")
    object UserSelect : TvRoute("tv_user_select")
    object ShowDetail : TvRoute("tv_show_detail")
    object Recap      : TvRoute("tv_recap")
}

@HiltViewModel
class ScrobbleViewModel @Inject constructor(
    private val scrobbler: MediaSessionScrobbler
) : ViewModel() {

    private val _pendingCandidate = MutableStateFlow<ScrobbleCandidate?>(null)
    val pendingCandidate: StateFlow<ScrobbleCandidate?> = _pendingCandidate.asStateFlow()

    private val dismissedEpisodes = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            scrobbler.pendingConfirmation.collect { candidate ->
                val key = "${candidate.matchedShow?.title}:${candidate.matchedEpisode?.season}:${candidate.matchedEpisode?.number}"
                if (key !in dismissedEpisodes) {
                    _pendingCandidate.value = candidate
                }
            }
        }
    }

    fun confirmScrobble() {
        val candidate = _pendingCandidate.value ?: return
        _pendingCandidate.value = null
        viewModelScope.launch {
            scrobbler.autoScrobble(candidate)
        }
    }

    fun dismissScrobble() {
        val candidate = _pendingCandidate.value ?: return
        val key = "${candidate.matchedShow?.title}:${candidate.matchedEpisode?.season}:${candidate.matchedEpisode?.number}"
        dismissedEpisodes.add(key)
        _pendingCandidate.value = null
    }
}

@HiltViewModel
class UserSessionViewModel @Inject constructor(
    private val userSessionRepository: UserSessionRepository
) : ViewModel() {
    fun saveSelectedUsers(ids: Set<String>) {
        viewModelScope.launch {
            userSessionRepository.setSelectedUsers(ids)
        }
    }
}

@Composable
fun TvNavGraph() {
    val navController = rememberNavController()
    val scrobbleViewModel: ScrobbleViewModel = hiltViewModel()
    val userSessionViewModel: UserSessionViewModel = hiltViewModel()

    var selectedEntry by remember { mutableStateOf<TraktWatchedEntry?>(null) }

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
                }
            )
        }

        composable(TvRoute.UserSelect.route) {
            UserSelectScreen(
                onConfirm = { selectedIds ->
                    userSessionViewModel.saveSelectedUsers(selectedIds.toSet())
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
                        navController.popBackStack(TvRoute.ShowDetail.route, inclusive = false)
                    }
                )
            }
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
