package com.justb81.watchbuddy.phone.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val shows: List<TraktWatchedEntry> = emptyList(),
    val lastSyncTime: String? = null,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val traktApi: TraktApiService,
    private val tokenRepository: TokenRepository,
    private val settingsRepository: SettingsRepository
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
                    lastSyncTime = getApplication<Application>().getString(R.string.home_just_now)
                )
            } catch (e: Exception) {
                val httpCode = (e as? HttpException)?.code()
                val errorMessage = if (httpCode == 401 || httpCode == 403) {
                    getApplication<Application>().getString(R.string.home_sync_failed_auth)
                } else {
                    getApplication<Application>().getString(R.string.home_sync_failed, e.message)
                }
                _uiState.value = _uiState.value.copy(isLoading = false, error = errorMessage)
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
