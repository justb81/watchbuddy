package com.justb81.watchbuddy.tv.ui.scrobble

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.core.scrobbler.MediaSessionScrobbler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bridges [MediaSessionScrobbler] with the [ScrobbleOverlay] UI.
 *
 * Candidates with confidence 0.70–0.95 land here for user confirmation.
 * Dismissed episodes are remembered so the overlay is not shown again
 * for the same episode during this session.
 */
@HiltViewModel
class ScrobbleViewModel @Inject constructor(
    private val scrobbler: MediaSessionScrobbler
) : ViewModel() {

    private val _pendingCandidate = MutableStateFlow<ScrobbleCandidate?>(null)
    val pendingCandidate: StateFlow<ScrobbleCandidate?> = _pendingCandidate.asStateFlow()

    @VisibleForTesting
    internal val dismissedEpisodes = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            scrobbler.pendingConfirmation.collect { candidate ->
                val key = candidateKey(candidate)
                if (key !in dismissedEpisodes) {
                    _pendingCandidate.value = candidate
                }
            }
        }
    }

    fun confirmScrobble() {
        val candidate = _pendingCandidate.value ?: return
        _pendingCandidate.value = null
        viewModelScope.launch {
            scrobbler.autoScrobble(candidate)
        }
    }

    fun dismissScrobble() {
        val candidate = _pendingCandidate.value ?: return
        dismissedEpisodes.add(candidateKey(candidate))
        _pendingCandidate.value = null
    }

    override fun onCleared() {
        super.onCleared()
        dismissedEpisodes.clear()
    }

    private fun candidateKey(candidate: ScrobbleCandidate): String =
        "${candidate.matchedShow?.title}:S${candidate.matchedEpisode?.season}E${candidate.matchedEpisode?.number}"
}
