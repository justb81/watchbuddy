package com.justb81.watchbuddy.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvHomeUiState(
    val isLoading: Boolean = true,
    val shows: List<TraktWatchedEntry> = emptyList(),
    val connectedPhones: Int = 0,
    val bestPhoneName: String? = null,
    val bestPhoneBackend: String? = null,
    val error: String? = null
)

@HiltViewModel
class TvHomeViewModel @Inject constructor(
    private val traktApi: TraktApiService,
    private val phoneDiscovery: PhoneDiscoveryManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvHomeUiState())
    val uiState: StateFlow<TvHomeUiState> = _uiState.asStateFlow()

    init {
        phoneDiscovery.startDiscovery()
        observePhones()
        loadShows()
    }

    private fun observePhones() {
        viewModelScope.launch {
            phoneDiscovery.discoveredPhones.collect { phones ->
                val best = phones.firstOrNull()
                _uiState.update {
                    it.copy(
                        connectedPhones = phones.size,
                        bestPhoneName   = best?.capability?.deviceName,
                        bestPhoneBackend = best?.capability?.llmBackend?.name
                    )
                }
            }
        }
    }

    private fun loadShows() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // TV fetches shows from the best connected phone's /shows endpoint
                val bestPhone = phoneDiscovery.getBestPhone()
                val shows: List<TraktWatchedEntry> = if (bestPhone != null) {
                    // TODO: fetch via phone HTTP API
                    emptyList()
                } else {
                    // Fallback: direct Trakt API (needs token on TV — not ideal)
                    emptyList()
                }
                _uiState.update { it.copy(isLoading = false, shows = shows) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        phoneDiscovery.stopDiscovery()
    }
}
