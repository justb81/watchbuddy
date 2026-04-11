package com.justb81.watchbuddy.phone.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.TraktApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val traktApi: TraktApiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init { loadShows() }

    fun loadShows() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // TODO: pull token from Keystore
                val shows = traktApi.getWatchedShows("Bearer TODO")
                _uiState.value = _uiState.value.copy(
                    isLoading    = false,
                    shows        = shows,
                    lastSyncTime = "Gerade eben"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error     = "Synchronisierung fehlgeschlagen: ${e.message}"
                )
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
