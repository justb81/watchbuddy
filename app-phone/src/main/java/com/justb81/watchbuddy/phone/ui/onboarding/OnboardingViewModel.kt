package com.justb81.watchbuddy.phone.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.trakt.DeviceCodeRequest
import com.justb81.watchbuddy.core.trakt.DeviceCodeResponse
import com.justb81.watchbuddy.core.trakt.ProxyTokenRequest
import com.justb81.watchbuddy.core.trakt.TokenProxyService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

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

    /** Trakt nicht konfiguriert — CLIENT_ID oder TOKEN_BACKEND_URL fehlt. */
    object NotConfigured : OnboardingState()
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    application: Application,
    private val traktApi: TraktApiService,
    /** Null, wenn TOKEN_BACKEND_URL in BuildConfig leer ist. */
    private val tokenProxy: TokenProxyService?,
    @Named("traktClientId") private val clientId: String,
    private val tokenRepository: TokenRepository
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Idle)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /**
     * True, wenn sowohl CLIENT_ID als auch Token-Proxy konfiguriert sind.
     * Wird vom UI genutzt, um den Trakt-Login-Button ein-/auszublenden.
     */
    val isTraktConfigured: Boolean
        get() = clientId.isNotBlank() && tokenProxy != null

    init {
        if (!isTraktConfigured) {
            _state.value = OnboardingState.NotConfigured
        }
    }

    private var countdownJob: Job? = null
    private var pollingJob: Job? = null

    fun requestDeviceCode() {
        if (!isTraktConfigured) {
            _state.value = OnboardingState.NotConfigured
            return
        }
        viewModelScope.launch {
            _state.value = OnboardingState.LoadingCode
            try {
                val response = traktApi.requestDeviceCode(DeviceCodeRequest(clientId))
                startCountdown(response)
                startPolling(response)
            } catch (e: Exception) {
                _state.value = OnboardingState.Error(
                    getApplication<Application>().getString(
                        R.string.onboarding_error_loading_code, e.message
                    )
                )
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
            _state.value = OnboardingState.Error(
                getApplication<Application>().getString(R.string.onboarding_code_expired)
            )
        }
    }

    private fun startPolling(response: DeviceCodeResponse) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            repeat(response.expires_in / response.interval) {
                delay(response.interval * 1_000L)
                try {
                    // Token-Austausch läuft ausschließlich über den Proxy —
                    // der client_secret verlässt niemals die APK.
                    val token = tokenProxy!!.exchangeDeviceCode(
                        ProxyTokenRequest(code = response.device_code)
                    )
                    tokenRepository.saveTokens(
                        accessToken  = token.access_token,
                        refreshToken = token.refresh_token,
                        expiresIn    = token.expires_in
                    )
                    val profile = traktApi.getProfile("Bearer ${token.access_token}")
                    countdownJob?.cancel()
                    pollingJob?.cancel()
                    _state.value = OnboardingState.Success(profile.username)
                } catch (_: Exception) {
                    // HTTP 400 = PIN noch nicht bestätigt, 410 = abgelaufen — weiter pollen
                }
            }
        }
    }
}
