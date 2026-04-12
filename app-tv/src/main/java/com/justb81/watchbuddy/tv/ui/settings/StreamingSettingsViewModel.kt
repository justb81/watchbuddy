package com.justb81.watchbuddy.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.KNOWN_STREAMING_SERVICES
import com.justb81.watchbuddy.core.model.StreamingService
import com.justb81.watchbuddy.tv.data.StreamingPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StreamingSettingsUiState(
    val services: List<StreamingService> = KNOWN_STREAMING_SERVICES,
    val subscribedIds: Set<String> = emptySet(),
    val orderedIds: List<String> = emptyList()
)

@HiltViewModel
class StreamingSettingsViewModel @Inject constructor(
    private val repository: StreamingPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StreamingSettingsUiState())
    val uiState: StateFlow<StreamingSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.subscribedServiceIds.collect { ids ->
                _uiState.update {
                    it.copy(subscribedIds = ids.toSet(), orderedIds = ids)
                }
            }
        }
    }

    fun toggleService(serviceId: String) {
        val current = _uiState.value.orderedIds.toMutableList()
        if (serviceId in current) {
            current.remove(serviceId)
        } else {
            current.add(serviceId)
        }
        _uiState.update { it.copy(subscribedIds = current.toSet(), orderedIds = current) }
        viewModelScope.launch {
            repository.setSubscribedServices(current)
        }
    }

    fun moveServiceUp(serviceId: String) {
        val current = _uiState.value.orderedIds.toMutableList()
        val index = current.indexOf(serviceId)
        if (index > 0) {
            current.removeAt(index)
            current.add(index - 1, serviceId)
            _uiState.update { it.copy(orderedIds = current) }
            viewModelScope.launch {
                repository.setSubscribedServices(current)
            }
        }
    }

    fun moveServiceDown(serviceId: String) {
        val current = _uiState.value.orderedIds.toMutableList()
        val index = current.indexOf(serviceId)
        if (index in 0 until current.lastIndex) {
            current.removeAt(index)
            current.add(index + 1, serviceId)
            _uiState.update { it.copy(orderedIds = current) }
            viewModelScope.launch {
                repository.setSubscribedServices(current)
            }
        }
    }
}
