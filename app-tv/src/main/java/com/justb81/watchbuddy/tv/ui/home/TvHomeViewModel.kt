package com.justb81.watchbuddy.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.AvatarSource
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.progress.ShowProgress
import com.justb81.watchbuddy.core.progress.ShowProgressCalculator
import com.justb81.watchbuddy.tv.data.PersistedShowCacheRepository
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import com.justb81.watchbuddy.tv.data.TvShowCache
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneApiService
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * Snapshot of a phone currently paired with this TV. Derived straight from
 * [PhoneDiscoveryManager.discoveredPhones] — no manual selection, no user
 * picker (issue #353). Rendered as a read-only chip strip on the home
 * header so the household can see who the TV is scrobbling for.
 */
data class ActiveViewer(
    val deviceId: String,
    val displayName: String,
    val avatarUrl: String?,
    val avatarSource: AvatarSource
)

data class TvHomeUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val shows: List<EnrichedShowEntry> = emptyList(),
    /** Shows watched within the last 30 days, sorted by last-watched DESC. */
    val continueWatching: List<EnrichedShowEntry> = emptyList(),
    /** All other shows (not in continueWatching), sorted alphabetically. */
    val allShows: List<EnrichedShowEntry> = emptyList(),
    /** Progress keyed by Trakt id. */
    val progress: Map<Int, ShowProgress> = emptyMap(),
    /** True for shows where the next unwatched episode is S(n+1)E01, keyed by Trakt id. */
    val hasNewSeason: Map<Int, Boolean> = emptyMap(),
    val connectedPhones: Int = 0,
    val activeViewers: List<ActiveViewer> = emptyList(),
    val noPhoneConnected: Boolean = false,
    /** True when a phone was discovered via NSD but its API call failed (distinct from noPhoneConnected). */
    val phoneApiError: Boolean = false,
    val error: String? = null,
    /** True when there are more pages available on the phone API. */
    val canLoadMore: Boolean = false,
    /** True when the displayed show list comes from the on-device persistent cache while the phone is offline. */
    val isShowingStaleCache: Boolean = false,
    /**
     * True while BLE discovery is running but no phone has been found yet.
     * The connected-user section shows a pending indicator instead of the
     * "no phone connected" label so the user knows a refresh is coming.
     */
    val isDiscoveryPending: Boolean = false,
)

private sealed interface FailureReason {
    data object NoPhone : FailureReason
    data class ApiError(val phoneFound: Boolean, val message: String?) : FailureReason
}

@HiltViewModel
class TvHomeViewModel @Inject constructor(
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val phoneApiClientFactory: PhoneApiClientFactory,
    private val tvShowCache: TvShowCache,
    private val preferencesRepository: StreamingPreferencesRepository,
    private val persistedShowCacheRepository: PersistedShowCacheRepository,
) : ViewModel() {

    companion object {
        val PAGE_SIZE = PhoneApiService.PAGE_SIZE

        /** Shows last watched within this window appear in "Continue Watching". */
        val CONTINUE_WATCHING_WINDOW: Duration = Duration.ofDays(30)

        /** Maximum age of the persisted cache before it is considered too stale to display. */
        val FALLBACK_CACHE_TTL: Duration = Duration.ofHours(1)
    }

    private val _uiState = MutableStateFlow(TvHomeUiState())
    val uiState: StateFlow<TvHomeUiState> = _uiState.asStateFlow()

    // In-memory layer of the resilience cache. After a successful fetch this mirrors what
    // was written to PersistedShowCacheRepository. On ViewModel recreation the persisted
    // store is the authoritative source; this field is populated from there on first load.
    private var fallbackCache: List<EnrichedShowEntry>? = null
    private var fallbackCacheTimestamp: Long = 0L
    private var loadedOffset: Int = 0

    init {
        observeDiscoveryPreference()
        observePhones()
        observeDiscoveryActive()
        // Initial load — re-triggered whenever the best phone changes.
        // If discovery is already active and no phone has been found, the
        // pending indicator is shown immediately and the first real load is
        // deferred to when observePhones detects the first phone.
        loadShows()
    }

    /**
     * Drive [PhoneDiscoveryManager] from the user's "Phone discovery" toggle.
     * Discovery lifecycle is now owned by the preference (and, when autostart
     * is on, by [TvDiscoveryService]) — not by this ViewModel's activity
     * scope — so we intentionally do NOT call stopDiscovery in onCleared.
     */
    private fun observeDiscoveryPreference() {
        viewModelScope.launch {
            preferencesRepository.isPhoneDiscoveryEnabled.collect { enabled ->
                runCatching { phoneDiscovery.setEnabled(enabled) }
            }
        }
    }

    private fun observePhones() {
        viewModelScope.launch {
            var previousBestDeviceId: String? = null
            phoneDiscovery.discoveredPhones.collect { phones ->
                val viewers = phones.mapNotNull { phone ->
                    phone.capability?.let { cap -> cap.toActiveViewer() }
                }
                val bestDeviceId = phones.firstOrNull()?.capability?.deviceId
                _uiState.update {
                    it.copy(
                        connectedPhones = phones.size,
                        activeViewers = viewers,
                        // Clear the pending indicator as soon as a phone is found.
                        isDiscoveryPending = it.isDiscoveryPending && phones.isEmpty(),
                    )
                }
                // Refresh the show list whenever the best phone changes — this
                // includes the transition from null → first phone (discovery
                // finds a phone for the first time) so the stale-cache view is
                // replaced promptly without waiting for the heartbeat.
                if (bestDeviceId != previousBestDeviceId) {
                    previousBestDeviceId = bestDeviceId
                    loadedOffset = 0
                    doLoadShows(append = false)
                }
            }
        }
    }

    /**
     * Mirrors [PhoneDiscoveryManager.discoveryActive] into [TvHomeUiState.isDiscoveryPending].
     * While discovery is running and no phone has been found yet we show a pending indicator
     * rather than the "no phone connected" label so the user knows discovery is in progress.
     * Once a phone is found [observePhones] clears the flag; once discovery stops without
     * finding any phone the flag is cleared here so the permanent "no phone" state appears.
     */
    private fun observeDiscoveryActive() {
        viewModelScope.launch {
            phoneDiscovery.discoveryActive.collect { active ->
                _uiState.update { state ->
                    val hasPendingDiscovery = active && state.connectedPhones == 0
                    state.copy(isDiscoveryPending = hasPendingDiscovery)
                }
            }
        }
    }

    /** Refresh from the beginning (resets pagination). Called on retry and user change. */
    fun loadShows() {
        viewModelScope.launch {
            loadedOffset = 0
            doLoadShows(append = false)
        }
    }

    /** Load the next page of shows and append to the existing list. */
    fun loadMoreShows() {
        val state = _uiState.value
        if (!state.canLoadMore || state.isLoadingMore || state.isLoading) return
        viewModelScope.launch {
            doLoadShows(append = true)
        }
    }

    private suspend fun doLoadShows(append: Boolean) {
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
                val api = phoneApiClientFactory.createClient(bestPhone.baseUrl, bestPhone.bearerToken)
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
                persistedShowCacheRepository.save(allShows)
                tvShowCache.updateEnrichedShows(allShows)

                val (continueWatching, otherShows) = partitionShows(allShows)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        shows = allShows,
                        continueWatching = continueWatching,
                        allShows = otherShows,
                        progress = computeProgress(allShows),
                        hasNewSeason = computeHasNewSeason(allShows),
                        canLoadMore = hasMore,
                        isShowingStaleCache = false,
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

    private suspend fun handleLoadFailure(reason: FailureReason) {
        val cached = getFallbackCache()
        _uiState.update {
            when (reason) {
                is FailureReason.NoPhone -> {
                    // If discovery is still running we defer the "no phone" state — the
                    // pending indicator already signals that a phone may appear soon.
                    val discoveryStillPending = it.isDiscoveryPending
                    when {
                        cached != null -> {
                            val (cw, others) = partitionShows(cached)
                            it.copy(
                                isLoading = false,
                                isLoadingMore = false,
                                shows = cached,
                                continueWatching = cw,
                                allShows = others,
                                progress = computeProgress(cached),
                                hasNewSeason = computeHasNewSeason(cached),
                                noPhoneConnected = !discoveryStillPending,
                                canLoadMore = false,
                                isShowingStaleCache = true,
                            )
                        }
                        else -> it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            noPhoneConnected = !discoveryStillPending,
                            canLoadMore = false,
                            isShowingStaleCache = false,
                        )
                    }
                }
                is FailureReason.ApiError -> when {
                    cached != null -> {
                        val (cw, others) = partitionShows(cached)
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            shows = cached,
                            continueWatching = cw,
                            allShows = others,
                            progress = computeProgress(cached),
                            hasNewSeason = computeHasNewSeason(cached),
                            phoneApiError = reason.phoneFound,
                            error = reason.message,
                            canLoadMore = false,
                            isShowingStaleCache = true,
                        )
                    }
                    else -> it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        phoneApiError = reason.phoneFound,
                        noPhoneConnected = !reason.phoneFound,
                        error = reason.message,
                        canLoadMore = false,
                        isShowingStaleCache = false,
                    )
                }
            }
        }
    }

    internal fun partitionShows(
        shows: List<EnrichedShowEntry>,
        now: Instant = Instant.now()
    ): Pair<List<EnrichedShowEntry>, List<EnrichedShowEntry>> {
        val cutoff = now.minus(CONTINUE_WATCHING_WINDOW)
        // Completed shows are hidden on TV only; phone HomeScreen keeps showing them (#362).
        val active = shows.filter { !ShowProgressCalculator.isCompleted(it.entry, it.tmdb) }
        val (continueWatching, others) = active.partition { entry ->
            val lastWatched = ShowProgressCalculator.latestWatchedInstant(entry.entry)
            lastWatched != null && lastWatched.isAfter(cutoff)
        }
        return continueWatching to others.sortedBy { it.entry.show.title.lowercase() }
    }

    private fun computeProgress(shows: List<EnrichedShowEntry>): Map<Int, ShowProgress> =
        shows.mapNotNull { enriched ->
            enriched.entry.show.ids.trakt?.let { id ->
                id to ShowProgressCalculator.compute(enriched.entry, enriched.tmdb)
            }
        }.toMap()

    private fun computeHasNewSeason(shows: List<EnrichedShowEntry>): Map<Int, Boolean> =
        shows.mapNotNull { enriched ->
            enriched.entry.show.ids.trakt?.let { id ->
                id to ShowProgressCalculator.hasNewSeasonAvailable(enriched.entry, enriched.tmdb)
            }
        }.toMap()

    private suspend fun getFallbackCache(): List<EnrichedShowEntry>? {
        val ttlMs = FALLBACK_CACHE_TTL.toMillis()
        val now = System.currentTimeMillis()

        val inMemory = fallbackCache
        if (inMemory != null && now - fallbackCacheTimestamp < ttlMs) {
            return inMemory
        }

        val persisted = persistedShowCacheRepository.load()
        if (persisted != null && now - persisted.savedAtMs < ttlMs) {
            fallbackCache = persisted.shows
            fallbackCacheTimestamp = persisted.savedAtMs
            return persisted.shows
        }

        return null
    }

}

internal fun DeviceCapability.toActiveViewer(): ActiveViewer = ActiveViewer(
    deviceId = deviceId,
    displayName = userName,
    avatarUrl = userAvatarUrl,
    avatarSource = avatarSource
)

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
