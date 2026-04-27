package com.justb81.watchbuddy.tv.ui.showdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.ResolvedProvider
import com.justb81.watchbuddy.core.progress.ShowProgressCalculator
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.tv.data.JustWatchDeepLinkRepository
import com.justb81.watchbuddy.tv.data.LastUsedProviderRepository
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.data.WatchProvidersRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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

/** Per-provider JustWatch deep link resolution state. */
sealed interface DeepLinkState {
    /** JustWatch lookup is in progress. */
    object Loading : DeepLinkState

    /** A usable deep link URL was found. */
    data class Available(val url: String) : DeepLinkState

    /** JustWatch has no offer for this provider in the current region. */
    object Unavailable : DeepLinkState
}

private data class DeepLinkKey(
    val tmdbShowId: Int,
    val season: Int,
    val episode: Int,
    val providerId: Int,
    val countryCode: String,
)

@HiltViewModel
class ShowDetailViewModel @Inject constructor(
    private val watchProviders: WatchProvidersRepository,
    private val lastUsedRepo: LastUsedProviderRepository,
    private val streamingPrefs: StreamingPreferencesRepository,
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val tmdbApi: TmdbApiService,
    private val justWatchRepo: JustWatchDeepLinkRepository,
) : ViewModel() {

    private val _nextEpisode = MutableStateFlow(NextEpisodeUiState())
    val nextEpisode: StateFlow<NextEpisodeUiState> = _nextEpisode.asStateFlow()

    private val _providers = MutableStateFlow<ProviderListUiState>(ProviderListUiState.Loading)
    val providers: StateFlow<ProviderListUiState> = _providers.asStateFlow()

    private val _deepLinks = MutableStateFlow<Map<Int, DeepLinkState>>(emptyMap())
    val deepLinks: StateFlow<Map<Int, DeepLinkState>> = _deepLinks.asStateFlow()

    private val inFlight = mutableMapOf<DeepLinkKey, Deferred<String?>>()

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
     * Resolves JustWatch deep links for all providers in the current [providers] list.
     *
     * Per-provider resolution is done in parallel. In-flight dedup via [inFlight] prevents
     * duplicate JustWatch calls for the same key across screen recompositions.
     */
    fun loadDeepLinks(enriched: EnrichedShowEntry) {
        val tmdbId = enriched.entry.show.ids.tmdb ?: return
        val (nextSeason, nextEp) =
            ShowProgressCalculator.nextEpisodeNumbers(enriched.entry, enriched.tmdb) ?: return
        val providerState = _providers.value
        val providerList = (providerState as? ProviderListUiState.Success)?.providers ?: return
        val countryCode = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        val showTitle = enriched.entry.show.title

        // Set all providers to Loading
        _deepLinks.value = providerList.associate { it.providerId to DeepLinkState.Loading }

        for (provider in providerList) {
            val key = DeepLinkKey(tmdbId, nextSeason, nextEp, provider.providerId, countryCode)
            val deferred = inFlight.getOrPut(key) {
                viewModelScope.async {
                    try {
                        justWatchRepo.resolveDeepLink(
                            tmdbShowId = tmdbId,
                            season = nextSeason,
                            episode = nextEp,
                            providerId = provider.providerId,
                            countryCode = countryCode,
                            showTitle = showTitle,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            viewModelScope.launch {
                val url = deferred.await()
                val newState = if (url != null) DeepLinkState.Available(url) else DeepLinkState.Unavailable
                _deepLinks.value = _deepLinks.value + (provider.providerId to newState)
                inFlight.remove(key)
            }
        }
    }

    /**
     * Records [provider] as last-used for this show. Returns the JustWatch URL if
     * already resolved, falling back to [ResolvedProvider.tmdbPageUrl].
     */
    fun onProviderSelected(provider: ResolvedProvider, enriched: EnrichedShowEntry): String? {
        val tmdbId = enriched.entry.show.ids.tmdb
        if (tmdbId != null) {
            viewModelScope.launch { lastUsedRepo.recordUsed(tmdbId, provider.providerId) }
        }
        val linkState = _deepLinks.value[provider.providerId]
        return when (linkState) {
            is DeepLinkState.Available -> linkState.url
            else -> provider.tmdbPageUrl
        }
    }

    /**
     * Returns the JustWatch URL for the top-ranked provider (used by the "Watch now" button),
     * or null when no deep link is resolved yet.
     */
    fun resolveTopProviderDeepLink(): String? {
        val providerState = _providers.value as? ProviderListUiState.Success ?: return null
        val topProvider = providerState.providers.firstOrNull() ?: return null
        val linkState = _deepLinks.value[topProvider.providerId]
        return (linkState as? DeepLinkState.Available)?.url
    }
}
