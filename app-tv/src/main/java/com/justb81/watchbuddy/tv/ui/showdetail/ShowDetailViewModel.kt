package com.justb81.watchbuddy.tv.ui.showdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.ResolvedProvider
import com.justb81.watchbuddy.core.model.TraktIds
import com.justb81.watchbuddy.core.model.TraktSeasonWithEpisodes
import com.justb81.watchbuddy.core.progress.ShowProgressCalculator
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntent
import com.justb81.watchbuddy.core.scrobbler.PlaybackIntentProvider
import com.justb81.watchbuddy.core.tmdb.TmdbApiService
import com.justb81.watchbuddy.core.tmdb.TmdbImageHelper
import com.justb81.watchbuddy.tv.data.JustWatchDeepLinkRepository
import com.justb81.watchbuddy.tv.data.LastUsedProviderRepository
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.data.WatchProvidersRepository
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import com.justb81.watchbuddy.tv.discovery.WatchedToggleRequest
import com.justb81.watchbuddy.tv.ui.components.ConnectedUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

/** UI state for the season+episode list loaded from the phone's /shows/{id}/seasons endpoint. */
sealed interface EpisodeListUiState {
    /** Initial state — no load has been triggered yet. */
    object Idle : EpisodeListUiState

    /** Fetching seasons from the phone. */
    object Loading : EpisodeListUiState

    /**
     * Seasons successfully loaded.
     *
     * @param seasons    Full season+episode structure.
     * @param watchedSet Set of (season, episode) pairs already watched by this user.
     */
    data class Success(
        val seasons: List<TraktSeasonWithEpisodes>,
        val watchedSet: Set<Pair<Int, Int>>,
    ) : EpisodeListUiState

    /** Phone unreachable or returned an error. */
    object Error : EpisodeListUiState
}

/** One-shot events emitted when a toggle operation partially or fully fails. */
sealed interface EpisodeToggleEvent {
    /** Every selected phone failed — the UI reverts the optimistic toggle. */
    data class AllFailed(val season: Int, val episode: Int) : EpisodeToggleEvent

    /** At least one phone succeeded but some failed. */
    data class PartialFailed(
        val season: Int,
        val episode: Int,
        val failedUserNames: List<String>,
    ) : EpisodeToggleEvent
}

private data class DeepLinkKey(
    val tmdbShowId: Int,
    val season: Int,
    val episode: Int,
    val providerId: Int,
    val countryCode: String,
)

@HiltViewModel
class ShowDetailViewModel @Suppress("LongParameterList") @Inject constructor(
    private val watchProviders: WatchProvidersRepository,
    private val lastUsedRepo: LastUsedProviderRepository,
    private val streamingPrefs: StreamingPreferencesRepository,
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val tmdbApi: TmdbApiService,
    private val justWatchRepo: JustWatchDeepLinkRepository,
    private val intentProvider: PlaybackIntentProvider,
    private val clientFactory: PhoneApiClientFactory,
) : ViewModel() {

    private val _nextEpisode = MutableStateFlow(NextEpisodeUiState())
    val nextEpisode: StateFlow<NextEpisodeUiState> = _nextEpisode.asStateFlow()

    private val _providers = MutableStateFlow<ProviderListUiState>(ProviderListUiState.Loading)
    val providers: StateFlow<ProviderListUiState> = _providers.asStateFlow()

    private val _deepLinks = MutableStateFlow<Map<Int, DeepLinkState>>(emptyMap())
    val deepLinks: StateFlow<Map<Int, DeepLinkState>> = _deepLinks.asStateFlow()

    private val _episodeList = MutableStateFlow<EpisodeListUiState>(EpisodeListUiState.Idle)
    val episodeList: StateFlow<EpisodeListUiState> = _episodeList.asStateFlow()

    private val _episodeToggleEvents = MutableSharedFlow<EpisodeToggleEvent>()
    val episodeToggleEvents: SharedFlow<EpisodeToggleEvent> = _episodeToggleEvents.asSharedFlow()

    /**
     * When true, [toggleEpisodeWatched] skips showing the scope picker and uses
     * all connected users. Set via [onDontAskAgainSet].
     */
    var skipScopePickerThisSession: Boolean = false
        private set

    fun onDontAskAgainSet() {
        skipScopePickerThisSession = true
    }

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

    /**
     * Returns all currently-discovered phones as [ConnectedUser] objects.
     * Used by the UI to populate the scope picker dialog.
     */
    fun connectedUsers(): List<ConnectedUser> =
        phoneDiscovery.discoveredPhones.value
            .mapNotNull { phone ->
                val name = phone.capability?.userName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ConnectedUser(id = phone.baseUrl, displayName = name)
            }

    /**
     * Fetches all seasons + episodes for [enriched] from the best available phone and
     * derives the initial [watchedSet] from the Trakt history embedded in the entry.
     */
    fun loadEpisodeList(enriched: EnrichedShowEntry) {
        val showId = enriched.entry.show.ids.trakt?.toString() ?: return
        viewModelScope.launch {
            _episodeList.value = EpisodeListUiState.Loading
            val phone = phoneDiscovery.getBestPhone()
            if (phone == null) {
                _episodeList.value = EpisodeListUiState.Error
                return@launch
            }
            try {
                val client = clientFactory.createClient(phone.baseUrl, phone.bearerToken)
                val seasons = client.getSeasons(showId)
                val watchedSet = buildWatchedSet(enriched)
                _episodeList.value = EpisodeListUiState.Success(seasons, watchedSet)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _episodeList.value = EpisodeListUiState.Error
            }
        }
    }

    /**
     * Toggles the watched state of a single episode across all phones whose IDs are
     * in [selectedUserIds].
     *
     * - Optimistically updates the UI state before the network calls.
     * - On all-failure: emits [EpisodeToggleEvent.AllFailed] and reverts the toggle.
     * - On partial failure: emits [EpisodeToggleEvent.PartialFailed] but keeps the
     *   successful state (majority wins).
     */
    fun toggleEpisodeWatched(
        showIds: TraktIds,
        season: Int,
        episode: Int,
        markAsWatched: Boolean,
        selectedUserIds: Set<String>,
    ) {
        viewModelScope.launch {
            // Optimistic update
            applyEpisodeToggle(season, episode, markAsWatched)

            val allPhones = phoneDiscovery.discoveredPhones.first()
            val targetPhones = allPhones.filter { it.baseUrl in selectedUserIds }

            if (targetPhones.isEmpty()) {
                _episodeToggleEvents.emit(EpisodeToggleEvent.AllFailed(season, episode))
                revertEpisodeToggle(season, episode, markAsWatched)
                return@launch
            }

            data class PhoneResult(val phone: PhoneDiscoveryManager.DiscoveredPhone, val success: Boolean)

            val results = coroutineScope {
                targetPhones.map { phone ->
                    async {
                        val success = runCatching {
                            val client = clientFactory.createClient(phone.baseUrl, phone.bearerToken)
                            val req = WatchedToggleRequest(showIds = showIds, season = season, episode = episode)
                            val response = if (markAsWatched) client.markWatched(req) else client.markUnwatched(req)
                            response.isSuccessful
                        }.getOrDefault(false)
                        PhoneResult(phone, success)
                    }
                }.awaitAll()
            }

            val failed = results.filter { !it.success }
            when {
                failed.size == results.size -> {
                    // All phones failed — revert
                    _episodeToggleEvents.emit(EpisodeToggleEvent.AllFailed(season, episode))
                    revertEpisodeToggle(season, episode, markAsWatched)
                }
                failed.isNotEmpty() -> {
                    // Partial failure — keep optimistic state, notify user
                    val failedNames = failed.mapNotNull { it.phone.capability?.userName }
                    _episodeToggleEvents.emit(
                        EpisodeToggleEvent.PartialFailed(season, episode, failedNames)
                    )
                }
                // else: all succeeded — nothing more to do
            }
        }
    }

    private fun applyEpisodeToggle(season: Int, episode: Int, markAsWatched: Boolean) {
        val current = _episodeList.value as? EpisodeListUiState.Success ?: return
        val key = season to episode
        val newSet = if (markAsWatched) current.watchedSet + key else current.watchedSet - key
        _episodeList.value = current.copy(watchedSet = newSet)
    }

    private fun revertEpisodeToggle(season: Int, episode: Int, wasMarkingWatched: Boolean) {
        // revert means undoing the optimistic toggle
        applyEpisodeToggle(season, episode, !wasMarkingWatched)
    }

    private fun buildWatchedSet(enriched: EnrichedShowEntry): Set<Pair<Int, Int>> =
        enriched.entry.seasons.flatMapTo(mutableSetOf()) { season ->
            season.episodes.map { ep -> season.number to ep.number }
        }
}
