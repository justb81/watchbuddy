package com.justb81.watchbuddy.phone.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.ScrobbleDisplayEvent
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionService
import com.justb81.watchbuddy.service.CompanionStateManager
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import retrofit2.HttpException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val shows: List<TraktWatchedEntry> = emptyList(),
    val posterUrls: Map<Int, String?> = emptyMap(),  // key: TMDB ID
    val lastSyncTime: String? = null,
    val error: String? = null,
    val canWatch: Boolean = false,
    val isWatchingTv: Boolean = false,
    val latestScrobbleEvent: ScrobbleDisplayEvent? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val traktApi: TraktApiService,
    private val tokenRepository: TokenRepository,
    private val settingsRepository: SettingsRepository,
    private val tmdbApiService: TmdbApiService,
    private val companionStateManager: CompanionStateManager
) : AndroidViewModel(application) {

    companion object {
        /** Hide scrobble events older than 30 minutes. */
        private const val SCROBBLE_DISPLAY_TTL_MS = 30 * 60_000L
    }

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadShows()
        checkServiceConnections()
        observeCompanionState()
        observeScrobbleEvents()
    }

    private fun checkServiceConnections() {
        viewModelScope.launch {
            try {
                val traktOk = tokenRepository.isTokenValid()
                val tmdbOk = settingsRepository.getTmdbApiKey().first().isNotBlank()
                _uiState.update { it.copy(canWatch = traktOk && tmdbOk) }
            } catch (_: Exception) {
                // Keystore unavailable — default to not ready
            }
        }
    }

    private fun observeCompanionState() {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val wasWatching = _uiState.value.isWatchingTv
                _uiState.update { it.copy(isWatchingTv = settings.companionEnabled) }
                if (settings.companionEnabled && !wasWatching) {
                    CompanionService.start(getApplication<Application>())
                }
            }
        }
    }

    private fun observeScrobbleEvents() {
        viewModelScope.launch {
            companionStateManager.lastScrobbleEvent.collect { event ->
                val displayEvent = if (event != null &&
                    System.currentTimeMillis() - event.timestamp < SCROBBLE_DISPLAY_TTL_MS
                ) event else null
                _uiState.update { it.copy(latestScrobbleEvent = displayEvent) }
            }
        }
    }

    fun toggleWatchingTv(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCompanionEnabled(enabled)
            if (enabled) {
                CompanionService.start(getApplication<Application>())
            } else {
                CompanionService.stop(getApplication<Application>())
            }
        }
    }

    fun loadShows() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val accessToken = tokenRepository.getAccessToken()
                    ?: throw IllegalStateException("No access token available")
                val shows = traktApi.getWatchedShows("Bearer $accessToken")
                _uiState.value = _uiState.value.copy(
                    isLoading    = false,
                    shows        = shows,
                    posterUrls   = emptyMap(),
                    lastSyncTime = getApplication<Application>().getString(R.string.home_just_now)
                )
                loadPosters(shows)
            } catch (e: Exception) {
                val httpCode = (e as? HttpException)?.code()
                val errorMsg = if (httpCode == 401 || httpCode == 403) {
                    getApplication<Application>().getString(R.string.home_sync_failed_auth)
                } else {
                    getApplication<Application>().getString(R.string.home_sync_failed, e.message)
                }
                _uiState.value = _uiState.value.copy(isLoading = false, error = errorMsg)
            }
        }
    }

    private fun loadPosters(shows: List<TraktWatchedEntry>) {
        viewModelScope.launch {
            val tmdbKey = settingsRepository.getTmdbApiKey().first()
            if (tmdbKey.isBlank()) return@launch
            shows.forEach { entry ->
                val tmdbId = entry.show.ids.tmdb ?: return@forEach
                launch {
                    try {
                        val tmdbShow = tmdbApiService.getShow(tmdbId, tmdbKey)
                        val posterUrl = TmdbImageHelper.poster(tmdbShow.poster_path, 300)
                        _uiState.update { state ->
                            state.copy(posterUrls = state.posterUrls + (tmdbId to posterUrl))
                        }
                    } catch (_: Exception) {
                        // Poster loading failure is silent; show renders without image
                    }
                }
            }
        }
    }

    fun sync() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            loadShows()
            _uiState.value = _uiState.value.copy(isSyncing = false)
        }
    }
}
