package com.justb81.watchbuddy.phone.ui.showdetail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.model.TraktShow
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.phone.server.EpisodeRepository
import com.justb81.watchbuddy.phone.server.ShowRepository
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
    /** Ordered: current season first, remaining seasons in ascending number order. */
    val seasons: List<SeasonUi> = emptyList(),
    val error: String? = null,
    val toggleError: String? = null,
    /** (seasonNumber, episodeNumber) currently being toggled, or null. */
    val togglingEpisode: Pair<Int, Int>? = null
)

data class SeasonUi(
    val number: Int,
    val episodes: List<EpisodeUi>,
    val expanded: Boolean,
    val watchedCount: Int,
    val totalCount: Int
)

data class EpisodeUi(
    val season: Int,
    val number: Int,
    val title: String?,
    val watched: Boolean
)

@HiltViewModel
class ShowDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    application: Application,
    private val showRepository: ShowRepository,
    private val episodeRepository: EpisodeRepository,
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
                val watchedEntry = findWatchedEntry()
                val seasons = episodeRepository.getSeasonsWithEpisodes(traktShowId.toString())
                val seasonUis = buildSeasonUis(seasons, watchedEntry)

                _uiState.value = ShowDetailUiState(
                    isLoading = false,
                    show = watchedEntry.show,
                    seasons = seasonUis
                )

                watchedEntry.show.ids.tmdb?.let { tmdbId -> loadTmdbDetails(tmdbId) }
            } catch (e: Exception) {
                _uiState.value = ShowDetailUiState(
                    isLoading = false,
                    error = getApplication<Application>().getString(R.string.error_generic)
                )
            }
        }
    }

    private suspend fun findWatchedEntry(): TraktWatchedEntry {
        val existing = showRepository.shows.value
            .firstOrNull { it.entry.show.ids.trakt == traktShowId }
        if (existing != null) return existing.entry
        // Cold cache — prime it via a normal fetch.
        showRepository.getShows()
        return showRepository.shows.value
            .firstOrNull { it.entry.show.ids.trakt == traktShowId }?.entry
            ?: throw IllegalStateException("Show not found in library")
    }

    private fun buildSeasonUis(
        seasons: List<TraktSeasonWithEpisodes>,
        entry: TraktWatchedEntry
    ): List<SeasonUi> {
        if (seasons.isEmpty()) return emptyList()

        val watchedIndex: Map<Int, Set<Int>> = entry.seasons.associate { ws ->
            ws.number to ws.episodes.map { it.number }.toSet()
        }

        val seasonUis = seasons
            .sortedBy { it.number }
            .map { season ->
                val watchedEpisodes = watchedIndex[season.number].orEmpty()
                val episodes = season.episodes
                    .sortedBy { it.number }
                    .map { ep ->
                        EpisodeUi(
                            season = season.number,
                            number = ep.number,
                            title = ep.title,
                            watched = ep.number in watchedEpisodes
                        )
                    }
                SeasonUi(
                    number = season.number,
                    episodes = episodes,
                    expanded = false,
                    watchedCount = episodes.count { it.watched },
                    totalCount = episodes.size
                )
            }

        val currentSeasonNumber = pickCurrentSeason(seasonUis)
        return reorder(seasonUis, currentSeasonNumber)
    }

    private fun pickCurrentSeason(seasons: List<SeasonUi>): Int? {
        if (seasons.isEmpty()) return null
        val anyWatched = seasons.any { it.watchedCount > 0 }
        return if (!anyWatched) {
            // Fresh show — default to the first non-special season, or the very
            // first season if there are no numbered seasons.
            seasons.firstOrNull { it.number >= 1 && it.totalCount > 0 }?.number
                ?: seasons.first().number
        } else {
            val latestWatchedSeason = seasons
                .filter { it.number >= 1 && it.watchedCount > 0 }
                .maxOfOrNull { it.number } ?: return seasons.first().number
            // Prefer the lowest season at or above latestWatchedSeason that still
            // has unwatched episodes. Fall back to the latest watched season
            // itself when the show is fully caught up.
            seasons
                .firstOrNull { it.number >= latestWatchedSeason && it.watchedCount < it.totalCount }
                ?.number
                ?: latestWatchedSeason
        }
    }

    private fun reorder(seasons: List<SeasonUi>, currentSeasonNumber: Int?): List<SeasonUi> {
        if (currentSeasonNumber == null) return seasons
        val (head, tail) = seasons.partition { it.number == currentSeasonNumber }
        return head.map { it.copy(expanded = true) } + tail
    }

    fun toggleSeasonExpanded(seasonNumber: Int) {
        _uiState.update { state ->
            state.copy(
                seasons = state.seasons.map { s ->
                    if (s.number == seasonNumber) s.copy(expanded = !s.expanded) else s
                }
            )
        }
    }

    fun toggleEpisodeWatched(episode: EpisodeUi) {
        val show = _uiState.value.show ?: return
        if (_uiState.value.togglingEpisode != null) return
        val wasWatched = episode.watched

        // Optimistic flip + pending marker.
        _uiState.update { state ->
            state.copy(
                togglingEpisode = episode.season to episode.number,
                toggleError = null,
                seasons = state.seasons.map { s -> flipEpisodeInSeason(s, episode) }
            )
        }

        viewModelScope.launch {
            val result = if (wasWatched) {
                episodeRepository.markEpisodeUnwatched(show.ids, episode.season, episode.number)
            } else {
                episodeRepository.markEpisodeWatched(show.ids, episode.season, episode.number)
            }

            result.fold(
                onSuccess = {
                    showRepository.updateLocalWatched(
                        traktShowId = traktShowId,
                        season = episode.season,
                        episode = episode.number,
                        watched = !wasWatched
                    )
                    _uiState.update { it.copy(togglingEpisode = null) }
                },
                onFailure = {
                    // Revert optimistic flip.
                    _uiState.update { state ->
                        state.copy(
                            togglingEpisode = null,
                            toggleError = getApplication<Application>()
                                .getString(R.string.show_detail_error_toggle),
                            seasons = state.seasons.map { s -> flipEpisodeInSeason(s, episode) }
                        )
                    }
                }
            )
        }
    }

    private fun flipEpisodeInSeason(season: SeasonUi, target: EpisodeUi): SeasonUi {
        if (season.number != target.season) return season
        val updated = season.episodes.map { ep ->
            if (ep.number == target.number) ep.copy(watched = !ep.watched) else ep
        }
        return season.copy(
            episodes = updated,
            watchedCount = updated.count { it.watched }
        )
    }

    fun clearToggleError() {
        _uiState.update { it.copy(toggleError = null) }
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
            // TMDB poster/overview is non-critical; failure is silently ignored.
        }
    }
}
