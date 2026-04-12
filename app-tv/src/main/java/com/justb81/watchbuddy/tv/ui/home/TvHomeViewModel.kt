package com.justb81.watchbuddy.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.tv.data.TvShowCache
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.network.PhoneApiClientFactory
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
    val noPhoneConnected: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class TvHomeViewModel @Inject constructor(
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val phoneApiClientFactory: PhoneApiClientFactory,
    private val showCache: TvShowCache
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvHomeUiState())
    val uiState: StateFlow<TvHomeUiState> = _uiState.asStateFlow()

    private var cachedShows: List<TraktWatchedEntry> = emptyList()

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
                        bestPhoneBackend = best?.capability?.llmBackend?.name,
                        noPhoneConnected = phones.isEmpty()
                    )
                }
                if (phones.isNotEmpty() && _uiState.value.shows.isEmpty()) {
                    loadShows()
                }
            }
        }
    }

    fun loadShows() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val bestPhone = phoneDiscovery.getBestPhone()
                val shows: List<TraktWatchedEntry> = if (bestPhone != null && bestPhone.baseUrl.isNotBlank()) {
                    val client = phoneApiClientFactory.createClient(bestPhone.baseUrl)
                    client.getShows().also {
                        cachedShows = it
                        showCache.updateShows(it)
                    }
                } else if (cachedShows.isNotEmpty()) {
                    cachedShows
                } else {
                    _uiState.update { it.copy(noPhoneConnected = true) }
                    emptyList()
                }
                _uiState.update { it.copy(isLoading = false, shows = shows) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        shows = cachedShows,
                        error = e.message
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        phoneDiscovery.stopDiscovery()
    }
}
