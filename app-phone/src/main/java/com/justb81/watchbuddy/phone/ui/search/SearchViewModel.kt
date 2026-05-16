package com.justb81.watchbuddy.phone.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.trakt.TraktSearchResult
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.server.ShowRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<TraktSearchResult> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val trackedShowIds: Set<Int> = emptySet(),
    /** Non-null while an add-show operation is in flight for the given Trakt ID. */
    val addingShowId: Int? = null,
    /** Non-null after a show is successfully added; cleared after the snackbar is shown. */
    val addedShowTitle: String? = null,
    val addError: String? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    application: Application,
    private val showRepository: ShowRepository,
    private val tokenRefreshManager: TokenRefreshManager,
) : AndroidViewModel(application) {

    companion object {
        private const val DEBOUNCE_MS = 300L
        private const val MIN_QUERY_LENGTH = 2
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        observeTrackedShows()
    }

    private fun observeTrackedShows() {
        viewModelScope.launch {
            showRepository.shows.collect { shows ->
                val ids = shows.mapNotNull { it.entry.show.ids.trakt }.toSet()
                _uiState.update { it.copy(trackedShowIds = ids) }
            }
        }
    }

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query, error = null) }
        searchJob?.cancel()
        if (query.length < MIN_QUERY_LENGTH) {
            _uiState.update { it.copy(results = emptyList(), isLoading = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            performSearch(query)
        }
    }

    private suspend fun performSearch(query: String) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            val token = tokenRefreshManager.getValidAccessToken()
                ?: error(getApplication<Application>().getString(R.string.search_error_no_token))
            val results = showRepository.searchShows("Bearer $token", query)
            _uiState.update { it.copy(results = results, isLoading = false) }
        } catch (e: Exception) {
            val httpCode = (e as? retrofit2.HttpException)?.code()
            val errorMsg = if (httpCode == 401 || httpCode == 403) {
                getApplication<Application>().getString(R.string.home_sync_failed_auth)
            } else {
                getApplication<Application>().getString(R.string.search_error_failed, e.message)
            }
            _uiState.update { it.copy(isLoading = false, error = errorMsg) }
        }
    }

    fun addShow(show: TraktShow) {
        val traktId = show.ids.trakt ?: return
        if (_uiState.value.addingShowId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(addingShowId = traktId, addError = null) }
            try {
                val token = tokenRefreshManager.getValidAccessToken()
                    ?: error(getApplication<Application>().getString(R.string.search_error_no_token))
                showRepository.addShowToWatchlist("Bearer $token", show)
                _uiState.update {
                    it.copy(
                        addingShowId = null,
                        addedShowTitle = show.title,
                        trackedShowIds = it.trackedShowIds + traktId,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        addingShowId = null,
                        addError = getApplication<Application>().getString(
                            R.string.search_error_add_failed,
                            show.title
                        )
                    )
                }
            }
        }
    }

    fun consumeAddedShowTitle() {
        _uiState.update { it.copy(addedShowTitle = null) }
    }

    fun consumeAddError() {
        _uiState.update { it.copy(addError = null) }
    }
}
