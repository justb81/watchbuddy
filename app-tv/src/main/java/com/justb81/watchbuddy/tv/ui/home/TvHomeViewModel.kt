package com.justb81.watchbuddy.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.progress.ShowProgress
import com.justb81.watchbuddy.core.progress.ShowProgressCalculator
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.data.TvShowCache
import com.justb81.watchbuddy.tv.data.UserSessionRepository
import com.justb81.watchbuddy.tv.discovery.TvDiscoveryService
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvHomeUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val shows: List<EnrichedShowEntry> = emptyList(),
    /** Progress keyed by Trakt id. */
    val progress: Map<Int, ShowProgress> = emptyMap(),
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

private sealed interface FailureReason {
    data object NoPhone : FailureReason
    data class ApiError(val phoneFound: Boolean, val message: String?) : FailureReason
}

@HiltViewModel
class TvHomeViewModel @Inject constructor(
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val phoneApiClientFactory: PhoneApiClientFactory,
    private val userSessionRepository: UserSessionRepository,
    private val tvShowCache: TvShowCache,
    private val streamingPreferencesRepository: StreamingPreferencesRepository,
) : ViewModel() {

    companion object {
        val PAGE_SIZE = PhoneApiService.PAGE_SIZE
    }

    private val _uiState = MutableStateFlow(TvHomeUiState())
    val uiState: StateFlow<TvHomeUiState> = _uiState.asStateFlow()

    // TTL-aware resilience cache: retains EnrichedShowEntry (with TMDB data) for offline fallback.
    // Separate from TvShowCache which stores raw TraktWatchedEntry for scrobble fuzzy-matching.
    private var fallbackCache: List<EnrichedShowEntry>? = null
    private var fallbackCacheTimestamp: Long = 0L
    private var loadedOffset: Int = 0

    init {
        observeDiscoveryEnabled()
        observePhones()
        observeSelectedUsers()
    }

    private fun observeDiscoveryEnabled() {
        viewModelScope.launch {
            streamingPreferencesRepository.isPhoneDiscoveryEnabled.collect { enabled ->
                runCatching { phoneDiscovery.setEnabled(enabled) }
            }
        }
    }

    private fun observePhones() {
        viewModelScope.launch {
            phoneDiscovery.discoveredPhones.collect { phones ->
                val best = phones.firstOrNull()
                _uiState.update {
                    it.copy(
                        connectedPhones = phones.size,
                        bestPhoneName   = best?.capability?.userName,
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

        val bestPhone = phoneDiscovery.getBestPhone()
        val currentOffset = if (append) loadedOffset else 0

        try {
            if (bestPhone != null) {
                val api = phoneApiClientFactory.createClient(bestPhone.baseUrl)
                val newShows = api.getShows(offset = currentOffset, limit = PAGE_SIZE)

                val hasMore = newShows.size >= PAGE_SIZE
                loadedOffset = currentOffset + newShows.size

                val allShows = if (append) {
                    (_uiState.value.shows + newShows).sortedByLastWatched()
                } else {
                    newShows.sortedByLastWatched()
                }

                fallbackCache = allShows
                fallbackCacheTimestamp = System.currentTimeMillis()
                tvShowCache.updateShows(allShows.map { it.entry })

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        shows = allShows,
                        progress = computeProgress(allShows),
                        canLoadMore = hasMore
                    )
                }
            } else {
                handleLoadFailure(FailureReason.NoPhone)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleLoadFailure(FailureReason.ApiError(phoneFound = bestPhone != null, message = e.message))
        }
    }

    private fun handleLoadFailure(reason: FailureReason) {
        val cached = getFallbackCache()
        _uiState.update {
            when (reason) {
                is FailureReason.NoPhone -> when {
                    cached != null -> it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        shows = cached,
                        progress = computeProgress(cached),
                        noPhoneConnected = true,
                        canLoadMore = false
                    )
                    else -> it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        noPhoneConnected = true,
                        canLoadMore = false
                    )
                }
                is FailureReason.ApiError -> when {
                    cached != null -> it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        shows = cached,
                        progress = computeProgress(cached),
                        phoneApiError = reason.phoneFound,
                        error = reason.message,
                        canLoadMore = false
                    )
                    else -> it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        phoneApiError = reason.phoneFound,
                        noPhoneConnected = !reason.phoneFound,
                        error = reason.message,
                        canLoadMore = false
                    )
                }
            }
        }
    }

    private fun computeProgress(shows: List<EnrichedShowEntry>): Map<Int, ShowProgress> =
        shows.mapNotNull { enriched ->
            enriched.entry.show.ids.trakt?.let { id ->
                id to ShowProgressCalculator.compute(enriched.entry, enriched.tmdb)
            }
        }.toMap()

    private fun getFallbackCache(): List<EnrichedShowEntry>? {
        val ttl = 5 * 60 * 1000L // 5 minutes
        val cached = fallbackCache
        return if (cached != null && System.currentTimeMillis() - fallbackCacheTimestamp < ttl) cached else null
    }

    override fun onCleared() {
        super.onCleared()
        // Leave discovery running when the background service owns the lifecycle.
        if (!TvDiscoveryService.isRunning) {
            phoneDiscovery.stopDiscovery()
        }
    }
}

/**
 * Defensive DESC-by-last-watched sort applied on top of the phone's already-sorted
 * pagination, so older phone builds (or a future phone-side regression) still yield
 * a consistent order on the TV grid. Tie-break by title to match [ShowRepository].
 */
internal fun List<EnrichedShowEntry>.sortedByLastWatched(): List<EnrichedShowEntry> =
    sortedWith(
        compareByDescending<EnrichedShowEntry> {
            ShowProgressCalculator.latestWatchedInstant(it.entry)
        }.thenBy { it.entry.show.title.lowercase() }
    )
