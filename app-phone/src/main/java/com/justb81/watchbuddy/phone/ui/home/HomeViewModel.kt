package com.justb81.watchbuddy.phone.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionService
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val traktApi: TraktApiService,
    private val tokenRepository: TokenRepository,
    private val settingsRepository: SettingsRepository,
    private val tmdbApiService: TmdbApiService
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadShows()
        startCompanionIfEnabled()
    }

    private fun startCompanionIfEnabled() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.companionEnabled) {
                CompanionService.start(getApplication<Application>())
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
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error     = getApplication<Application>().getString(R.string.home_sync_failed, e.message)
                )
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
