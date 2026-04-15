package com.justb81.watchbuddy.phone.ui.showdetail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedSeason
import com.justb81.watchbuddy.core.model.TraktWatchedEpisode
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.core.trakt.SyncHistoryBody
import com.justb81.watchbuddy.core.trakt.SyncHistoryEpisodeItem
import com.justb81.watchbuddy.core.trakt.SyncHistorySeasonItem
import com.justb81.watchbuddy.core.trakt.SyncHistoryShowItem
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShowDetailUiState(
    val isLoading: Boolean = true,
    val show: TraktShow? = null,
    val posterUrl: String? = null,
    val overview: String? = null,
    val watchedSeasons: List<TraktWatchedSeason> = emptyList(),
    val error: String? = null,
    val toggleError: String? = null,
    val togglingEpisode: Pair<Int, Int>? = null  // (seasonNumber, episodeNumber)
)

@HiltViewModel
class ShowDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    application: Application,
    private val traktApi: TraktApiService,
    private val tokenRepository: TokenRepository,
    private val tmdbApiService: TmdbApiService,
    private val settingsRepository: SettingsRepository
) : AndroidViewModel(application) {

    val traktShowId: Int = checkNotNull(savedStateHandle["traktShowId"])

    private val _uiState = MutableStateFlow(ShowDetailUiState())
    val uiState: StateFlow<ShowDetailUiState> = _uiState.asStateFlow()

    init {
        loadShowDetail()
    }

    fun loadShowDetail() {
        viewModelScope.launch {
            _uiState.value = ShowDetailUiState(isLoading = true)
            try {
                val accessToken = tokenRepository.getAccessToken()
                    ?: throw IllegalStateException("No access token available")
                val watchedShows = traktApi.getWatchedShows("Bearer $accessToken")
                val entry = watchedShows.find { it.show.ids.trakt == traktShowId }
                    ?: throw IllegalStateException("Show not found in library")

                _uiState.value = ShowDetailUiState(
                    isLoading = false,
                    show = entry.show,
                    watchedSeasons = entry.seasons.sortedBy { it.number }
                )

                entry.show.ids.tmdb?.let { tmdbId -> loadTmdbDetails(tmdbId) }
            } catch (e: Exception) {
                _uiState.value = ShowDetailUiState(
                    isLoading = false,
                    error = getApplication<Application>().getString(R.string.error_generic)
                )
            }
        }
    }

    private suspend fun loadTmdbDetails(tmdbId: Int) {
        try {
            val tmdbKey = settingsRepository.getTmdbApiKey().first()
            if (tmdbKey.isBlank()) return
            val tmdbShow = tmdbApiService.getShow(tmdbId, tmdbKey)
            _uiState.update { state ->
                state.copy(
                    posterUrl = TmdbImageHelper.poster(tmdbShow.poster_path, 300),
                    overview = tmdbShow.overview
                )
            }
        } catch (_: Exception) {
            // TMDB poster/overview is non-critical; failure is silently ignored
        }
    }

    fun toggleEpisodeWatched(season: Int, episode: Int, currentlyWatched: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(togglingEpisode = season to episode, toggleError = null) }
            try {
                val accessToken = tokenRepository.getAccessToken()
                    ?: throw IllegalStateException("No access token available")
                val show = _uiState.value.show ?: return@launch

                val body = SyncHistoryBody(
                    shows = listOf(
                        SyncHistoryShowItem(
                            ids = show.ids,
                            seasons = listOf(
                                SyncHistorySeasonItem(
                                    number = season,
                                    episodes = listOf(SyncHistoryEpisodeItem(number = episode))
                                )
                            )
                        )
                    )
                )

                if (currentlyWatched) {
                    traktApi.removeFromHistory("Bearer $accessToken", body)
                } else {
                    traktApi.addToHistory("Bearer $accessToken", body)
                }

                _uiState.update { state ->
                    val updatedSeasons = if (currentlyWatched) {
                        state.watchedSeasons.mapNotNull { s ->
                            if (s.number != season) s
                            else {
                                val remaining = s.episodes.filter { it.number != episode }
                                if (remaining.isEmpty()) null else s.copy(episodes = remaining)
                            }
                        }
                    } else {
                        val seasonExists = state.watchedSeasons.any { it.number == season }
                        if (seasonExists) {
                            state.watchedSeasons.map { s ->
                                if (s.number != season) s
                                else s.copy(
                                    episodes = (s.episodes + TraktWatchedEpisode(number = episode))
                                        .sortedBy { it.number }
                                )
                            }
                        } else {
                            (state.watchedSeasons + TraktWatchedSeason(
                                number = season,
                                episodes = listOf(TraktWatchedEpisode(number = episode))
                            )).sortedBy { it.number }
                        }
                    }
                    state.copy(watchedSeasons = updatedSeasons, togglingEpisode = null)
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        togglingEpisode = null,
                        toggleError = getApplication<Application>()
                            .getString(R.string.show_detail_error_toggle)
                    )
                }
            }
        }
    }

    fun clearToggleError() {
        _uiState.update { it.copy(toggleError = null) }
    }
}
