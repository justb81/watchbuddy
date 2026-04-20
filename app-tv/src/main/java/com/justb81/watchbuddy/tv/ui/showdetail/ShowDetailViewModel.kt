package com.justb81.watchbuddy.tv.ui.showdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.KNOWN_STREAMING_SERVICES
import com.justb81.watchbuddy.core.model.StreamingService
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.progress.ShowProgressCalculator
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TMDB_STILL_WIDTH = 780

data class NextEpisodeUiState(
    val isLoading: Boolean = false,
    val stillUrl: String? = null,
    val episodeName: String? = null,
    val episodeCode: String? = null,
)

@HiltViewModel
class ShowDetailViewModel @Inject constructor(
    private val streamingPrefs: StreamingPreferencesRepository,
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val tmdbApi: TmdbApiService,
) : ViewModel() {

    /**
     * Returns the streaming services to display, filtered and ordered by user preferences.
     * Falls back to all known services if no preference is set.
     */
    val availableServices: Flow<List<StreamingService>> = streamingPrefs.subscribedServiceIds.map { ids ->
        if (ids.isEmpty()) {
            KNOWN_STREAMING_SERVICES
        } else {
            ids.mapNotNull { id -> KNOWN_STREAMING_SERVICES.find { it.id == id } }
        }
    }

    private val _nextEpisode = MutableStateFlow(NextEpisodeUiState())
    val nextEpisode: StateFlow<NextEpisodeUiState> = _nextEpisode.asStateFlow()

    /**
     * Fetches the next episode's still image and actual title from TMDB.
     * Uses [ShowProgressCalculator.nextEpisodeNumbers] to determine which episode to fetch,
     * preferring TMDB's [TmdbProgressHint.nextAired] over the naive Trakt +1 calculation.
     * Silently no-ops when the TMDB ID or the phone's API key is unavailable.
     */
    fun loadNextEpisode(enriched: EnrichedShowEntry) {
        val tmdbId = enriched.entry.show.ids.tmdb ?: return
        val (nextSeason, nextEp) =
            ShowProgressCalculator.nextEpisodeNumbers(enriched.entry, enriched.tmdb) ?: return

        viewModelScope.launch {
            _nextEpisode.value = NextEpisodeUiState(isLoading = true)
            val apiKey = phoneDiscovery.getBestPhone()?.capability?.tmdbApiKey
            if (apiKey == null) {
                _nextEpisode.value = NextEpisodeUiState(isLoading = false)
                return@launch
            }
            try {
                val ep = tmdbApi.getEpisode(tmdbId, nextSeason, nextEp, apiKey)
                _nextEpisode.value = NextEpisodeUiState(
                    isLoading = false,
                    stillUrl = TmdbImageHelper.still(ep.still_path, TMDB_STILL_WIDTH),
                    episodeName = ep.name.takeIf { it.isNotBlank() },
                    episodeCode = "S%02dE%02d".format(ep.season_number, ep.episode_number),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _nextEpisode.value = NextEpisodeUiState(isLoading = false)
            }
        }
    }

    /**
     * Resolves the best deep link for a show based on user's preferred streaming services.
     * Iterates through subscribed services in priority order and returns the first link that
     * can be generated with the available show IDs.  Services whose templates require a TMDB
     * numeric ID are skipped when [TraktIds.tmdb] is null, allowing slug-only services
     * (Joyn, Prime Video, ZDF) and no-variable services (WaipuTV) to work regardless.
     * Returns null only when no subscribed service can produce a valid link.
     */
    fun resolveDeepLink(
        entry: TraktWatchedEntry,
        subscribedServices: List<StreamingService>
    ): String? {
        val tmdbId = entry.show.ids.tmdb
        val slug   = entry.show.ids.slug ?: entry.show.title.lowercase().replace(" ", "-")

        val servicesToTry = subscribedServices.ifEmpty { KNOWN_STREAMING_SERVICES }

        for (service in servicesToTry) {
            val template = service.deepLinkTemplate
            val needsId  = template.contains("{tmdb_id}") || template.contains("{id}")
            if (needsId && tmdbId == null) continue

            return template
                .replace("{tmdb_id}", tmdbId?.toString() ?: "")
                .replace("{slug}", slug)
                .replace("{id}",     tmdbId?.toString() ?: "")
        }
        return null
    }
}
