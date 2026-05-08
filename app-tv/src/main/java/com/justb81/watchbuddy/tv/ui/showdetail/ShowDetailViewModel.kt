package com.justb81.watchbuddy.tv.ui.showdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.ResolvedProvider
import com.justb81.watchbuddy.core.progress.ShowProgressCalculator
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntent
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntentProvider
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

/**
 * Aggregated state for the "Watch Now" button derived from [ProviderListUiState] and
 * the per-provider [DeepLinkState] map.
 */
sealed class WatchNowState {
    /** Providers are still loading or the top provider's deep link is still resolving. */
    object Loading : WatchNowState()

    /** A usable JustWatch deep-link URL is available for the top provider. */
    data class Available(val url: String) : WatchNowState()

    /** The top provider resolved but JustWatch has no offer for this show/region. */
    object Unavailable : WatchNowState()

    /** No streaming providers are available (Empty or Error state). */
    object NoProvider : WatchNowState()
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
    private val intentProvider: PlaybackIntentProvider,
) : ViewModel() {

    private val _nextEpisode = MutableStateFlow(NextEpisodeUiState())
    val nextEpisode: StateFlow<NextEpisodeUiState> = _nextEpisode.asStateFlow()

    private val _providers = MutableStateFlow<ProviderListUiState>(ProviderListUiState.Loading)
    val providers: StateFlow<ProviderListUiState> = _providers.asStateFlow()

    private val _deepLinks = MutableStateFlow<Map<Int, DeepLinkState>>(emptyMap())
    val deepLinks: StateFlow<Map<Int, DeepLinkState>> = _deepLinks.asStateFlow()

    /**
     * Aggregated "Watch Now" button state derived from [providers] and [deepLinks].
     * Computed reactively — the UI observes this instead of deriving state from nullable fields.
     */
    val watchNowState: StateFlow<WatchNowState> = combine(_providers, _deepLinks) { providerState, deepLinks ->
        when (providerState) {
            is ProviderListUiState.Loading -> WatchNowState.Loading
            is ProviderListUiState.Empty -> WatchNowState.NoProvider
            is ProviderListUiState.Error -> WatchNowState.NoProvider
            is ProviderListUiState.Success -> {
                val topProvider = providerState.providers.firstOrNull()
                    ?: return@combine WatchNowState.NoProvider
                when (val linkState = deepLinks[topProvider.providerId]) {
                    is DeepLinkState.Loading, null -> WatchNowState.Loading
                    is DeepLinkState.Available -> WatchNowState.Available(linkState.url)
                    is DeepLinkState.Unavailable -> WatchNowState.Unavailable
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WatchNowState.Loading)

    private val inFlight = mutableMapOf<DeepLinkKey, Deferred<String?>>()

    /**
     * Fetches the next episode's still image and actual title from TMDB.
     * Silently no-ops when the TMDB ID or the phone's API key is unavailable.
     */
    fun loadNextEpisode(enriched: EnrichedShowEntry) {
        val tmdbId = enriched.entry.show.ids.tmdb ?: return
        val (nextSeason, nextEp) =
            ShowProgressCalculator.nextUnwatchedEpisodeNumbers(enriched.entry, enriched.tmdb) ?: return

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
     * Resolves the country code for watch-provider and JustWatch lookups.
     *
     * Cascade:
     *   1. Country code reported by the best-connected phone's locale (via [DeviceCapability.countryCode]).
     *   2. TV's own default locale country (may be empty on TVs with English-only firmware).
     *   3. Hard-coded "US" fallback.
     */
    private fun resolveCountryCode(): String =
        phoneDiscovery.getBestPhone()?.capability?.countryCode
            ?: Locale.getDefault().country.takeIf { it.length == 2 }
            ?: "US"

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
                val countryCode = resolveCountryCode()
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
            ShowProgressCalculator.nextUnwatchedEpisodeNumbers(enriched.entry, enriched.tmdb) ?: return
        val providerState = _providers.value
        val providerList = (providerState as? ProviderListUiState.Success)?.providers ?: return
        val countryCode = resolveCountryCode()
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
                _deepLinks.update { it + (provider.providerId to newState) }
                inFlight -= key
            }
        }
    }

    /**
     * Records [provider] as last-used for this show and captures a [PlaybackIntent] for Phase 0
     * scrobble hinting. Returns the JustWatch URL if already resolved, falling back to
     * [ResolvedProvider.tmdbPageUrl].
     */
    fun onProviderSelected(provider: ResolvedProvider, enriched: EnrichedShowEntry): String? {
        val tmdbId = enriched.entry.show.ids.tmdb
        if (tmdbId != null) {
            viewModelScope.launch { lastUsedRepo.recordUsed(tmdbId, provider.providerId) }
        }
        // Capture Watch-Now intent before launching the streaming app so Phase 0 can short-circuit
        // the scrobble cascade when the media session appears on the matching package.
        val pkgName = provider.packageName
        val nextEp = ShowProgressCalculator.nextUnwatchedEpisodeNumbers(enriched.entry, enriched.tmdb)
        if (pkgName != null && nextEp != null) {
            intentProvider.record(
                PlaybackIntent(
                    showIds = enriched.entry.show.ids,
                    showTitle = enriched.entry.show.title,
                    season = nextEp.first,
                    episode = nextEp.second,
                    providerPackageName = pkgName,
                    capturedAtMs = System.currentTimeMillis(),
                ),
            )
        }
        return when (val linkState = _deepLinks.value[provider.providerId]) {
            is DeepLinkState.Available -> linkState.url
            else -> provider.tmdbPageUrl
        }
    }
}
