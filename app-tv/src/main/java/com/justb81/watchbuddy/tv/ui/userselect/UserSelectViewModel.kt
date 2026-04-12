package com.justb81.watchbuddy.tv.ui.userselect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.tv.data.UserSessionRepository
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UserSelectUiState(
    val availableUsers: List<DeviceCapability> = emptyList(),
    val selectedUserIds: Set<String> = emptySet()
)

@HiltViewModel
class UserSelectViewModel @Inject constructor(
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val userSessionRepository: UserSessionRepository
) : ViewModel() {

    val uiState: StateFlow<UserSelectUiState> = phoneDiscovery.discoveredPhones
        .map { phones ->
            UserSelectUiState(
                availableUsers  = phones.mapNotNull { it.capability },
                selectedUserIds = phones.mapNotNull { it.capability?.deviceId }.toSet()
            )
        }
        .stateIn(
            scope         = viewModelScope,
            started       = SharingStarted.WhileSubscribed(5_000),
            initialValue  = UserSelectUiState()
        )

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    init {
        // Pre-select previously persisted user IDs
        viewModelScope.launch {
            userSessionRepository.selectedUserIds.first().let { persisted ->
                if (persisted.isNotEmpty()) {
                    _selectedIds.value = persisted
                }
            }
        }
    }

    fun toggleUser(deviceId: String) {
        _selectedIds.value = if (_selectedIds.value.contains(deviceId)) {
            _selectedIds.value - deviceId
        } else {
            _selectedIds.value + deviceId
        }
    }

    fun selectAll() {
        _selectedIds.value = uiState.value.availableUsers.map { it.deviceId }.toSet()
    }

    fun persistSelection() {
        viewModelScope.launch {
            userSessionRepository.setSelectedUsers(_selectedIds.value)
        }
    }
}
