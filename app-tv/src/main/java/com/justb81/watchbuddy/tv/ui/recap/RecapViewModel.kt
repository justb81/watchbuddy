package com.justb81.watchbuddy.tv.ui.recap

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.tv.discovery.PhoneDiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

@Serializable
private data class RecapResponse(val html: String)

private val lenientJson = Json { ignoreUnknownKeys = true }

sealed class RecapUiState {
    object Idle : RecapUiState()
    data class Generating(val deviceName: String) : RecapUiState()
    data class Ready(val html: String) : RecapUiState()
    data class Fallback(val synopsis: String) : RecapUiState()
    data class Error(val message: String) : RecapUiState()
}

@HiltViewModel
class RecapViewModel @Inject constructor(
    application: Application,
    private val phoneDiscovery: PhoneDiscoveryManager,
    private val httpClient: OkHttpClient
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
                val deviceName = phone.capability?.deviceName ?: getApplication<Application>().getString(R.string.tv_default_device_name)
                _state.value = RecapUiState.Generating(deviceName)
                try {
                    @Suppress("DEPRECATION")
                    val host = phone.serviceInfo.host?.hostAddress ?: continue
                    val port = phone.serviceInfo.port
                    val url  = "http://$host:$port/recap/$traktShowId"

                    val response = httpClient.newCall(
                        Request.Builder()
                            .url(url)
                            .post("{}".toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()

                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val recap = lenientJson.decodeFromString<RecapResponse>(body)
                        _state.value = RecapUiState.Ready(recap.html)
                        return@launch
                    }
                } catch (e: Exception) {
                    // Failover to next phone
                    continue
                }
            }

            // All phones failed
            _state.value = RecapUiState.Fallback(fallbackSynopsis)
        }
    }

    fun reset() { _state.value = RecapUiState.Idle }
}
