package com.justb81.watchbuddy.phone.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.core.trakt.DeviceCodeResponse
import com.justb81.watchbuddy.core.trakt.TraktApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class OnboardingState {
    object Idle : OnboardingState()
    object LoadingCode : OnboardingState()
    data class WaitingForPin(
        val userCode: String,
        val verificationUrl: String,
        val expiresInSeconds: Int,
        val deviceCode: String
    ) : OnboardingState()
    object Polling : OnboardingState()
    data class Success(val username: String) : OnboardingState()
    data class Error(val message: String) : OnboardingState()
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val traktApi: TraktApiService
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Idle)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    // Placeholder — replace with actual secure storage read (Android Keystore via SettingsRepository)
    // In production this value is injected via the Trakt auth backend or direct credentials mode.
    private val clientId: String = "YOUR_TRAKT_CLIENT_ID"

    private var countdownJob: Job? = null
    private var pollingJob: Job? = null

    fun requestDeviceCode() {
        viewModelScope.launch {
            _state.value = OnboardingState.LoadingCode
            try {
                val response = traktApi.requestDeviceCode(
                    com.justb81.watchbuddy.core.trakt.DeviceCodeRequest(clientId)
                )
                startCountdown(response)
                startPolling(response)
            } catch (e: Exception) {
                _state.value = OnboardingState.Error("Fehler beim Laden des Codes: ${e.message}")
            }
        }
    }

    private fun startCountdown(response: DeviceCodeResponse) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var remaining = response.expires_in
            while (remaining > 0) {
                _state.value = OnboardingState.WaitingForPin(
                    userCode         = response.user_code,
                    verificationUrl  = response.verification_url,
                    expiresInSeconds = remaining,
                    deviceCode       = response.device_code
                )
                delay(1_000)
                remaining--
            }
            _state.value = OnboardingState.Error("Code abgelaufen. Bitte erneut versuchen.")
        }
    }

    private fun startPolling(response: DeviceCodeResponse) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            repeat(response.expires_in / response.interval) {
                delay(response.interval * 1_000L)
                try {
                    val token = traktApi.pollDeviceToken(
                        com.justb81.watchbuddy.core.trakt.DeviceTokenRequest(
                            code          = response.device_code,
                            client_id     = clientId,
                            client_secret = "" // injected from backend proxy / NDK secret
                        )
                    )
                    // TODO: store token in Android Keystore
                    val profile = traktApi.getProfile("Bearer ${token.access_token}")
                    countdownJob?.cancel()
                    pollingJob?.cancel()
                    _state.value = OnboardingState.Success(profile.username)
                } catch (_: Exception) {
                    // 400 = still waiting, 410 = expired — keep polling
                }
            }
        }
    }

}
