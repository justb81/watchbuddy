package com.justb81.watchbuddy.tv.ui.scrobble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.ScrobbleCandidate
import com.justb81.watchbuddy.tv.scrobbler.MediaSessionScrobbler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScrobbleViewModel @Inject constructor(
    private val scrobbler: MediaSessionScrobbler
) : ViewModel() {

    private val _pendingCandidate = MutableStateFlow<ScrobbleCandidate?>(null)
    val pendingCandidate: StateFlow<ScrobbleCandidate?> = _pendingCandidate.asStateFlow()

    /** Set of dismissed media titles to avoid re-prompting for the same episode. */
    private val dismissedTitles = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            scrobbler.pendingConfirmation.collect { candidate ->
                if (candidate.mediaTitle !in dismissedTitles) {
                    _pendingCandidate.value = candidate
                }
            }
        }
    }

    fun onCandidateDetected(candidate: ScrobbleCandidate) {
        if (candidate.mediaTitle !in dismissedTitles) {
            _pendingCandidate.value = candidate
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
        dismissedTitles.add(candidate.mediaTitle)
        _pendingCandidate.value = null
    }
}
