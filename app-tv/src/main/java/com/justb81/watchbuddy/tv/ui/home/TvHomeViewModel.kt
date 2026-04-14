package com.justb81.watchbuddy.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.tv.data.TvShowCache
import com.justb81.watchbuddy.tv.data.UserSessionRepository
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvHomeUiState(
    val isLoading: Boolean = true,
    val shows: List<TraktWatchedEntry> = emptyList(),
    val selectedUserIds: Set<String> = emptySet(),
    val connectedPhones: Int = 0,
    val bestPhoneName: String? = null,
    val bestPhoneBackend: String? = null,
    val noPhoneConnected: Boolean = false,
    /** True when a phone was discovered via NSD but its API call failed (distinct from noPhoneConnected). */
    val phoneApiError: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class TvHomeViewModel @Inject constructor(
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val phoneApiClientFactory: PhoneApiClientFactory,
    private val userSessionRepository: UserSessionRepository,
    private val tvShowCache: TvShowCache
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvHomeUiState())
    val uiState: StateFlow<TvHomeUiState> = _uiState.asStateFlow()

    private var cachedShows: List<TraktWatchedEntry>? = null
    private var cacheTimestamp: Long = 0L

    init {
        phoneDiscovery.startDiscovery()
        observePhones()
        observeSelectedUsers()
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

    private fun observeSelectedUsers() {
        viewModelScope.launch {
            userSessionRepository.selectedUserIds.collect { ids ->
                _uiState.update { it.copy(selectedUserIds = ids) }
                loadShows(ids)
            }
        }
    }

    fun loadShows() {
        viewModelScope.launch {
            loadShows(_uiState.value.selectedUserIds)
        }
    }

    private suspend fun loadShows(selectedUserIds: Set<String>) {
        _uiState.update { it.copy(isLoading = true, error = null, noPhoneConnected = false, phoneApiError = false) }

        // Determine best phone before entering the try block so it is accessible in the catch.
        val bestPhone = phoneDiscovery.getBestPhone()

        try {
            if (bestPhone != null) {
                val api = phoneApiClientFactory.createClient(bestPhone.baseUrl)
                val shows = api.getShows()
                cachedShows = shows
                cacheTimestamp = System.currentTimeMillis()
                tvShowCache.updateShows(shows)
                _uiState.update { it.copy(isLoading = false, shows = shows) }
            } else {
                val cached = getCachedShows()
                if (cached != null) {
                    _uiState.update { it.copy(isLoading = false, shows = cached, noPhoneConnected = true) }
                } else {
                    _uiState.update { it.copy(isLoading = false, noPhoneConnected = true) }
                }
            }
        } catch (e: Exception) {
            // A phone was found (bestPhone != null) but its API call failed — this is different from
            // "no phone connected". Show phoneApiError so the UI can display the correct message.
            val phoneFound = bestPhone != null
            val cached = getCachedShows()
            if (cached != null) {
                _uiState.update {
                    it.copy(isLoading = false, shows = cached, phoneApiError = phoneFound, error = e.message)
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        phoneApiError = phoneFound,
                        noPhoneConnected = !phoneFound,
                        error = e.message
                    )
                }
            }
        }
    }

    private fun getCachedShows(): List<TraktWatchedEntry>? {
        val ttl = 5 * 60 * 1000L // 5 minutes
        val cached = cachedShows
        return if (cached != null && System.currentTimeMillis() - cacheTimestamp < ttl) cached else null
    }

    override fun onCleared() {
        super.onCleared()
        phoneDiscovery.stopDiscovery()
    }
}
