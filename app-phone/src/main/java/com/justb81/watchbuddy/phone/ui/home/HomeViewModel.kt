package com.justb81.watchbuddy.phone.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.model.AmbiguousCandidate
import com.justb81.watchbuddy.core.model.AmbiguousScrobbleEvent
import com.justb81.watchbuddy.core.model.EnrichedShowEntry
import com.justb81.watchbuddy.core.model.ScrobbleDisplayEvent
import com.justb81.watchbuddy.core.progress.ShowProgress
import com.justb81.watchbuddy.core.progress.ShowProgressCalculator
import com.justb81.watchbuddy.core.trakt.ScrobbleBody
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRefreshManager
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.network.WifiStateProvider
import com.justb81.watchbuddy.phone.server.ShowRepository
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.service.CompanionService
import com.justb81.watchbuddy.service.CompanionStateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val shows: List<EnrichedShowEntry> = emptyList(),
    /** Shows watched within the last 30 days, sorted by last-watched DESC. */
    val continueWatching: List<EnrichedShowEntry> = emptyList(),
    /** All other shows (not in continueWatching), sorted alphabetically. */
    val allShows: List<EnrichedShowEntry> = emptyList(),
    /** Progress keyed by Trakt id. */
    val progress: Map<Int, ShowProgress> = emptyMap(),
    /** True for shows where the next unwatched episode is S(n+1)E01, keyed by Trakt id. */
    val hasNewSeason: Map<Int, Boolean> = emptyMap(),
    val lastSyncTime: String? = null,
    val error: String? = null,
    val canWatch: Boolean = false,
    // Default true so a cold-start render doesn't flash a "no Wi-Fi" reason
    // before the first WifiStateProvider emission arrives.
    val isOnWifi: Boolean = true,
    val isWatchingTv: Boolean = false,
    val latestScrobbleEvent: ScrobbleDisplayEvent? = null,
    /** Non-null while an ambiguous scrobble prompt is awaiting the user's selection. */
    val pendingAmbiguousPrompt: AmbiguousScrobbleEvent? = null,
) {
    val canStartCompanion: Boolean get() = canWatch && isOnWifi
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val showRepository: ShowRepository,
    private val tokenRepository: TokenRepository,
    private val tokenRefreshManager: TokenRefreshManager,
    private val traktApiService: TraktApiService,
    private val settingsRepository: SettingsRepository,
    private val companionStateManager: CompanionStateManager,
    private val wifiStateProvider: WifiStateProvider
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "HomeViewModel"

        /** Hide scrobble events older than 30 minutes. */
        private const val SCROBBLE_DISPLAY_TTL_MS = 30 * 60_000L

        /** Shows last watched within this window appear in "Continue Watching". */
        val CONTINUE_WATCHING_WINDOW: Duration = Duration.ofDays(30)
    }

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        assertTokenAccessible()
        observeShows()
        loadShows()
        checkServiceConnections()
        observeCompanionState()
        observeScrobbleEvents()
        observeWifiState()
        observePendingPrompt()
    }

    private fun assertTokenAccessible() {
        runCatching { tokenRepository.getAccessToken() }
            .onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = getApplication<Application>().getString(R.string.home_sync_failed, e.message)
                    )
                }
            }
    }

    private fun observeShows() {
        viewModelScope.launch {
            showRepository.shows.collect { shows ->
                val progressMap = shows.mapNotNull { enriched ->
                    enriched.entry.show.ids.trakt?.let { traktId ->
                        traktId to ShowProgressCalculator.compute(enriched.entry, enriched.tmdb)
                    }
                }.toMap()
                val hasNewSeasonMap = shows.mapNotNull { enriched ->
                    enriched.entry.show.ids.trakt?.let { traktId ->
                        traktId to ShowProgressCalculator.hasNewSeasonAvailable(enriched.entry, enriched.tmdb)
                    }
                }.toMap()
                val (continueWatching, allShows) = partitionShows(shows)
                _uiState.update {
                    it.copy(
                        shows = shows,
                        progress = progressMap,
                        hasNewSeason = hasNewSeasonMap,
                        continueWatching = continueWatching,
                        allShows = allShows
                    )
                }
            }
        }
    }

    private fun checkServiceConnections() {
        viewModelScope.launch {
            try {
                val traktOk = tokenRepository.isTokenValid()
                val tmdbOk = settingsRepository.getTmdbApiKey().first().isNotBlank()
                _uiState.update { it.copy(canWatch = traktOk && tmdbOk) }
            } catch (_: Exception) {
                // Keystore unavailable — default to not ready
            }
        }
    }

    private fun observeCompanionState() {
        viewModelScope.launch {
            flow { emitAll(settingsRepository.settings) }
                .catch { e ->
                    _uiState.update {
                        it.copy(error = getApplication<Application>().getString(R.string.home_sync_failed, e.message))
                    }
                }
                .collect { settings ->
                    val wasWatching = _uiState.value.isWatchingTv
                    _uiState.update { it.copy(isWatchingTv = settings.companionEnabled) }
                    if (settings.companionEnabled && !wasWatching) {
                        CompanionService.start(getApplication<Application>())
                    }
                }
        }
    }

    private fun observeScrobbleEvents() {
        viewModelScope.launch {
            companionStateManager.lastScrobbleEvent.collect { event ->
                val displayEvent = if (event != null &&
                    System.currentTimeMillis() - event.timestamp < SCROBBLE_DISPLAY_TTL_MS
                ) event else null
                _uiState.update { it.copy(latestScrobbleEvent = displayEvent) }
            }
        }
    }

    private fun observeWifiState() {
        viewModelScope.launch {
            wifiStateProvider.isOnWifi.collect { onWifi ->
                val wasWatching = _uiState.value.isWatchingTv
                _uiState.update { it.copy(isOnWifi = onWifi) }
                // Auto-stop a running companion when Wi-Fi drops: without it the
                // NSD advertisement binds to nothing and the foreground
                // notification lingers on a non-functional state.
                if (!onWifi && wasWatching) {
                    toggleWatchingTv(false)
                }
            }
        }
    }

    fun toggleWatchingTv(enabled: Boolean) {
        // Hard-gate start requests when Wi-Fi is missing. The UI disables the
        // switch, but onboarding from a notification action or future entry
        // points must also respect the gate.
        if (enabled && !_uiState.value.isOnWifi) {
            DiagnosticLog.event(TAG, "toggleWatchingTv=on refused (no Wi-Fi)")
            return
        }
        DiagnosticLog.event(
            TAG,
            "toggleWatchingTv=${if (enabled) "on" else "off"} canWatch=${_uiState.value.canWatch}"
        )
        viewModelScope.launch {
            settingsRepository.setCompanionEnabled(enabled)
            if (enabled) {
                CompanionService.start(getApplication<Application>())
            } else {
                CompanionService.stop(getApplication<Application>())
            }
        }
    }

    fun loadShows() {
        viewModelScope.launch { fetchShows() }
    }

    fun sync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            showRepository.invalidateCache()
            try {
                fetchShows()
            } finally {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    private fun observePendingPrompt() {
        viewModelScope.launch {
            companionStateManager.pendingPrompt.collect { event ->
                _uiState.update { it.copy(pendingAmbiguousPrompt = event) }
            }
        }
    }

    /**
     * The user tapped [candidate] in the ambiguous-scrobble prompt for [event].
     * Scrobbles to Trakt and resolves the prompt so the TV stops re-dispatching it.
     */
    fun selectCandidate(event: AmbiguousScrobbleEvent, candidate: AmbiguousCandidate) {
        val show = candidate.show
        val episode = candidate.episode ?: return
        val traktId = show.ids.trakt ?: return
        viewModelScope.launch {
            try {
                val token = tokenRefreshManager.getValidAccessToken() ?: return@launch
                val scrobbleBody = ScrobbleBody(
                    show = show,
                    episode = episode,
                    progress = event.tick.progress * 100f,
                )
                traktApiService.scrobbleStart("Bearer $token", scrobbleBody)
                DiagnosticLog.event(TAG, "ambiguous prompt resolved: ${show.title} S${episode.season}E${episode.number}")
            } catch (e: Exception) {
                DiagnosticLog.warn(TAG, "selectCandidate scrobble failed", e)
            } finally {
                companionStateManager.resolvePrompt(event.sessionKey, traktId)
            }
        }
    }

    internal fun partitionShows(
        shows: List<EnrichedShowEntry>,
        now: Instant = Instant.now()
    ): Pair<List<EnrichedShowEntry>, List<EnrichedShowEntry>> {
        val cutoff = now.minus(CONTINUE_WATCHING_WINDOW)
        // Completed shows always fall through to "All Shows" — the shelf is a
        // "watch next" prompt and a finished show has nothing left to watch.
        // (#398. TV hides completed shows entirely — #362.)
        val (completed, active) = shows.partition {
            ShowProgressCalculator.isCompleted(it.entry, it.tmdb)
        }
        val (continueWatching, olderActive) = active.partition { entry ->
            val lastWatched = ShowProgressCalculator.latestWatchedInstant(entry.entry)
            lastWatched != null && lastWatched.isAfter(cutoff)
        }
        val allShows = (olderActive + completed).sortedBy { it.entry.show.title.lowercase() }
        return continueWatching to allShows
    }

    private suspend fun fetchShows() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        try {
            val token = tokenRepository.getAccessToken()
                ?: throw IllegalStateException("No access token available")
            showRepository.getShows() // Result flows into observeShows() via StateFlow.
            _uiState.update {
                it.copy(
                    isLoading = false,
                    lastSyncTime = getApplication<Application>().getString(R.string.home_just_now)
                )
            }
        } catch (e: Exception) {
            val httpCode = (e as? retrofit2.HttpException)?.code()
            val errorMsg = if (httpCode == 401 || httpCode == 403) {
                getApplication<Application>().getString(R.string.home_sync_failed_auth)
            } else {
                getApplication<Application>().getString(R.string.home_sync_failed, e.message)
            }
            _uiState.update { it.copy(isLoading = false, error = errorMsg) }
        }
    }
}
