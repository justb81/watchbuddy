package com.justb81.watchbuddy.phone.ui.diagnostics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.justb81.watchbuddy.phone.llm.LlmEventLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class LlmEventDetailUiState(
    val event: LlmEventLog.LlmEvent? = null,
    val notFound: Boolean = false,
)

@HiltViewModel
class LlmEventDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    llmEventLog: LlmEventLog,
) : ViewModel() {

    private val eventId: Long = checkNotNull(savedStateHandle["eventId"])

    private val _uiState = MutableStateFlow(LlmEventDetailUiState())
    val uiState: StateFlow<LlmEventDetailUiState> = _uiState.asStateFlow()

    init {
        val event = llmEventLog.findById(eventId)
        _uiState.value = LlmEventDetailUiState(
            event = event,
            notFound = event == null,
        )
    }
}
