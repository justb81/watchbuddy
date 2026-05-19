package com.justb81.watchbuddy.phone.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.core.trakt.TraktSearchResult
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.server.ShowRepository
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchResultItem(
    val result: TraktSearchResult,
    val posterUrl: String? = null,
    val firstAirYear: Int? = null,
    val lastAirYear: Int? = null,
    val status: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResultItem> = emptyList(),
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
    private val tmdbApiService: TmdbApiService,
    private val settingsRepository: SettingsRepository,
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SearchViewModel"
        private const val DEBOUNCE_MS = 300L
        private const val MIN_QUERY_LENGTH = 2
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val POSTER_WIDTH = 300
        private const val YEAR_PREFIX_LENGTH = 4
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
            val traktResults = showRepository.searchShows("Bearer $token", query)
            val items = traktResults.map { SearchResultItem(result = it) }
            _uiState.update { it.copy(results = items, isLoading = false) }
            enrichWithTmdb(traktResults)
        } catch (e: Exception) {
            DiagnosticLog.warn(TAG, "show search failed for query='$query'", e)
            val httpCode = (e as? retrofit2.HttpException)?.code()
            val errorMsg = if (httpCode == HTTP_UNAUTHORIZED || httpCode == HTTP_FORBIDDEN) {
                getApplication<Application>().getString(R.string.home_sync_failed_auth)
            } else {
                getApplication<Application>().getString(R.string.search_error_failed, e.message)
            }
            _uiState.update { it.copy(isLoading = false, error = errorMsg) }
        }
    }

    private suspend fun enrichWithTmdb(traktResults: List<TraktSearchResult>) {
        val apiKey = runCatching { settingsRepository.getTmdbApiKey().first() }.getOrDefault("")
        if (apiKey.isBlank()) return
        coroutineScope {
            traktResults.mapIndexed { index, result ->
                async {
                    val tmdbId = result.show?.ids?.tmdb ?: return@async
                    val tmdb = runCatching {
                        tmdbApiService.getShow(tmdbId, apiKey)
                    }.onFailure {
                        DiagnosticLog.warn(TAG, "TMDB enrichment failed for '${result.show?.title}'", it)
                    }.getOrNull() ?: return@async

                    val firstYear = tmdb.first_air_date?.take(YEAR_PREFIX_LENGTH)?.toIntOrNull()
                    val lastYear = tmdb.last_air_date?.take(YEAR_PREFIX_LENGTH)?.toIntOrNull()
                    val posterUrl = TmdbImageHelper.poster(tmdb.poster_path, POSTER_WIDTH)

                    _uiState.update { state ->
                        val updated = state.results.toMutableList()
                        if (index < updated.size && updated[index].result === result) {
                            updated[index] = updated[index].copy(
                                posterUrl = posterUrl,
                                firstAirYear = firstYear,
                                lastAirYear = lastYear,
                                status = tmdb.status,
                            )
                        }
                        state.copy(results = updated)
                    }
                }
            }.awaitAll()
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
                DiagnosticLog.warn(TAG, "addShow failed for '${show.title}'", e)
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
