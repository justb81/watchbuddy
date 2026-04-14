package com.justb81.watchbuddy.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.tv.data.TvShowCache
import com.justb81.watchbuddy.tv.data.UserSessionRepository
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvHomeUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val shows: List<TraktWatchedEntry> = emptyList(),
    val selectedUserIds: Set<String> = emptySet(),
    val connectedPhones: Int = 0,
    val bestPhoneName: String? = null,
    val bestPhoneBackend: String? = null,
    val noPhoneConnected: Boolean = false,
    /** True when a phone was discovered via NSD but its API call failed (distinct from noPhoneConnected). */
    val phoneApiError: Boolean = false,
    val error: String? = null,
    /** True when there are more pages available on the phone API. */
    val canLoadMore: Boolean = false
)

@HiltViewModel
class TvHomeViewModel @Inject constructor(
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val phoneApiClientFactory: PhoneApiClientFactory,
    private val userSessionRepository: UserSessionRepository,
    private val tvShowCache: TvShowCache
) : ViewModel() {

    companion object {
        val PAGE_SIZE = PhoneApiService.PAGE_SIZE
    }

    private val _uiState = MutableStateFlow(TvHomeUiState())
    val uiState: StateFlow<TvHomeUiState> = _uiState.asStateFlow()

    private var cachedShows: List<TraktWatchedEntry>? = null
    private var cacheTimestamp: Long = 0L
    private var loadedOffset: Int = 0

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
                loadedOffset = 0
                doLoadShows(ids, append = false)
            }
        }
    }

    /** Refresh from the beginning (resets pagination). Called on retry and user change. */
    fun loadShows() {
        viewModelScope.launch {
            loadedOffset = 0
            doLoadShows(_uiState.value.selectedUserIds, append = false)
        }
    }

    /** Load the next page of shows and append to the existing list. */
    fun loadMoreShows() {
        val state = _uiState.value
        if (!state.canLoadMore || state.isLoadingMore || state.isLoading) return
        viewModelScope.launch {
            doLoadShows(state.selectedUserIds, append = true)
        }
    }

    private suspend fun doLoadShows(selectedUserIds: Set<String>, append: Boolean) {
        if (append) {
            _uiState.update { it.copy(isLoadingMore = true) }
        } else {
            _uiState.update {
                it.copy(isLoading = true, error = null, noPhoneConnected = false, phoneApiError = false)
            }
        }

        // Determine best phone before entering the try block so it is accessible in the catch.
        val bestPhone = phoneDiscovery.getBestPhone()
        val currentOffset = if (append) loadedOffset else 0

        try {
            if (bestPhone != null) {
                val api = phoneApiClientFactory.createClient(bestPhone.baseUrl)
                val newShows = api.getShows(offset = currentOffset, limit = PAGE_SIZE)

                val hasMore = newShows.size >= PAGE_SIZE
                loadedOffset = currentOffset + newShows.size

                val allShows = if (append) _uiState.value.shows + newShows else newShows

                cachedShows = allShows
                cacheTimestamp = System.currentTimeMillis()
                tvShowCache.updateShows(allShows)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        shows = allShows,
                        canLoadMore = hasMore
                    )
                }
            } else {
                val cached = getCachedShows()
                if (cached != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            shows = cached,
                            noPhoneConnected = true,
                            canLoadMore = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            noPhoneConnected = true,
                            canLoadMore = false
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // A phone was found (bestPhone != null) but its API call failed — this is different from
            // "no phone connected". Show phoneApiError so the UI can display the correct message.
            val phoneFound = bestPhone != null
            val cached = getCachedShows()
            if (cached != null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        shows = cached,
                        phoneApiError = phoneFound,
                        error = e.message,
                        canLoadMore = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        phoneApiError = phoneFound,
                        noPhoneConnected = !phoneFound,
                        error = e.message,
                        canLoadMore = false
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
