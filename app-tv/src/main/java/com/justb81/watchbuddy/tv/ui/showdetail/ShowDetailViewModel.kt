package com.justb81.watchbuddy.tv.ui.showdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.ResolvedProvider
import com.justb81.watchbuddy.core.model.TraktWatchedEntry
import com.justb81.watchbuddy.core.progress.ShowProgressCalculator
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.tv.data.LastUsedProviderRepository
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.data.WatchProvidersRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

private const val TMDB_STILL_WIDTH = 780

data class NextEpisodeUiState(
    val isLoading: Boolean = false,
    val stillUrl: String? = null,
    val episodeName: String? = null,
    val episodeCode: String? = null,
)

sealed interface ProviderListUiState {
    object Loading : ProviderListUiState
    data class Success(val providers: List<ResolvedProvider>) : ProviderListUiState

    /** TMDB returned zero providers for this region. */
    data class Empty(val tmdbPageUrl: String?) : ProviderListUiState

    /** Network or API error — the user can retry. */
    object Error : ProviderListUiState
}

@HiltViewModel
class ShowDetailViewModel @Inject constructor(
    private val watchProviders: WatchProvidersRepository,
    private val lastUsedRepo: LastUsedProviderRepository,
    private val streamingPrefs: StreamingPreferencesRepository,
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val tmdbApi: TmdbApiService,
) : ViewModel() {

    private val _nextEpisode = MutableStateFlow(NextEpisodeUiState())
    val nextEpisode: StateFlow<NextEpisodeUiState> = _nextEpisode.asStateFlow()

    private val _providers = MutableStateFlow<ProviderListUiState>(ProviderListUiState.Loading)
    val providers: StateFlow<ProviderListUiState> = _providers.asStateFlow()

    /**
     * Fetches the next episode's still image and actual title from TMDB.
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
     * Fetches TMDB watch providers for [enriched], applies installed-app filter and
     * last-used ordering, then updates [providers]. Safe to call multiple times (retry).
     */
    fun loadProviders(enriched: EnrichedShowEntry) {
        val tmdbId = enriched.entry.show.ids.tmdb ?: run {
            _providers.value = ProviderListUiState.Empty(null)
            return
        }
        viewModelScope.launch {
            _providers.value = ProviderListUiState.Loading
            val apiKey = phoneDiscovery.getBestPhone()?.capability?.tmdbApiKey
            if (apiKey == null) {
                _providers.value = ProviderListUiState.Error
                return@launch
            }
            try {
                val countryCode = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
                val showNonInstalled = streamingPrefs.getShowNonInstalledProviders()
                val (resolved, pageUrl) = watchProviders.getResolvedProviders(
                    tmdbId, countryCode, apiKey, showNonInstalled
                )
                _providers.value = if (resolved.isEmpty()) {
                    ProviderListUiState.Empty(pageUrl)
                } else {
                    ProviderListUiState.Success(resolved)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _providers.value = ProviderListUiState.Error
            }
        }
    }

    /**
     * Records [provider] as last-used for this show and returns the deep link URL.
     * Falls back to [ResolvedProvider.tmdbPageUrl] if no template is available.
     * Returns null only when both template and page URL are absent.
     */
    fun onProviderSelected(provider: ResolvedProvider, entry: TraktWatchedEntry): String? {
        val tmdbId = entry.show.ids.tmdb
        if (tmdbId != null) {
            viewModelScope.launch { lastUsedRepo.recordUsed(tmdbId, provider.providerId) }
        }
        return resolveProviderDeepLink(provider, entry)
    }

    private fun resolveProviderDeepLink(provider: ResolvedProvider, entry: TraktWatchedEntry): String? {
        val template = provider.deepLinkTemplate ?: return provider.tmdbPageUrl
        val tmdbId = entry.show.ids.tmdb
        val slug = entry.show.ids.slug ?: entry.show.title.lowercase().replace(" ", "-")

        val needsId = template.contains("{tmdb_id}") || template.contains("{id}")
        if (needsId && tmdbId == null) return provider.tmdbPageUrl

        return template
            .replace("{tmdb_id}", tmdbId?.toString() ?: "")
            .replace("{slug}", slug)
            .replace("{id}", tmdbId?.toString() ?: "")
    }

    /**
     * Resolves the best deep link for the primary (first) provider.
     * Used by the "Watch now" button which launches the top-ranked provider.
     */
    fun resolveDeepLink(entry: TraktWatchedEntry): String? {
        val state = _providers.value
        if (state is ProviderListUiState.Success) {
            state.providers.firstOrNull()?.let { return resolveProviderDeepLink(it, entry) }
        }
        return null
    }
}
