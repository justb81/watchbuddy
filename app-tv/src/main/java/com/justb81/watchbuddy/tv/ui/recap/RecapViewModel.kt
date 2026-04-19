package com.justb81.watchbuddy.tv.ui.recap

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.tv.discovery.PhoneApiClientFactory
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RecapUiState {
    object Idle : RecapUiState()
    data class Generating(val deviceName: String) : RecapUiState()
    data class Ready(val html: String) : RecapUiState()
    data class Fallback(
        val synopsis: String,
        /** True when phones were tried but all failed; false when no phones were available at all. */
        val allPhonesFailed: Boolean = false
    ) : RecapUiState()
    data class Error(val message: String) : RecapUiState()
}

@HiltViewModel
class RecapViewModel @Inject constructor(
    application: Application,
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val phoneApiClientFactory: PhoneApiClientFactory
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<RecapUiState>(RecapUiState.Idle)
    val state: StateFlow<RecapUiState> = _state.asStateFlow()

    /**
     * Requests a recap from the best available phone.
     * Failover: best → next → next → fallback synopsis
     */
    fun requestRecap(traktShowId: Int, fallbackSynopsis: String) {
        viewModelScope.launch {
            val phones = phoneDiscovery.discoveredPhones.value
                .filter { it.capability?.isAvailable == true }
                .sortedByDescending { it.score }

            if (phones.isEmpty()) {
                _state.value = RecapUiState.Fallback(fallbackSynopsis)
                return@launch
            }

            for (phone in phones) {
                val deviceName = phone.capability?.deviceName
                    ?: getApplication<Application>().getString(R.string.tv_default_device_name)
                _state.value = RecapUiState.Generating(deviceName)
                try {
                    val recap = phoneApiClientFactory.createClient(phone.baseUrl).getRecap(traktShowId)
                    _state.value = RecapUiState.Ready(recap.html)
                    return@launch
                } catch (e: Exception) {
                    // Failover to next phone
                    continue
                }
            }

            // All phones were tried but all failed — distinguish from the "no phones" early return above.
            _state.value = RecapUiState.Fallback(fallbackSynopsis, allPhonesFailed = true)
        }
    }

    fun reset() { _state.value = RecapUiState.Idle }
}
