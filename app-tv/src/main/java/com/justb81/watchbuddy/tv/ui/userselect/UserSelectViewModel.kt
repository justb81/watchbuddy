package com.justb81.watchbuddy.tv.ui.userselect

import androidx.lifecycle.ViewModel
import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class UserSelectUiState(
    val availableUsers: List<DeviceCapability> = emptyList(),
    val selectedUserIds: Set<String> = emptySet()
)

@HiltViewModel
class UserSelectViewModel @Inject constructor(
    private val phoneDiscovery: PhoneDiscoveryManager
) : ViewModel() {

    val uiState: StateFlow<UserSelectUiState> = phoneDiscovery.discoveredPhones
        .map { phones ->
            UserSelectUiState(
                availableUsers  = phones.mapNotNull { it.capability },
                selectedUserIds = phones.mapNotNull { it.capability?.deviceId }.toSet()
            )
        }
        .stateIn(
            scope         = kotlinx.coroutines.GlobalScope,
            started       = SharingStarted.WhileSubscribed(5_000),
            initialValue  = UserSelectUiState()
        )

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

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
}
