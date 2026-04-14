package com.justb81.watchbuddy.phone.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.network.TokenProxyServiceFactory
import com.justb81.watchbuddy.core.trakt.DeviceCodeRequest
import com.justb81.watchbuddy.core.trakt.DeviceCodeResponse
import com.justb81.watchbuddy.core.trakt.DeviceTokenRequest
import com.justb81.watchbuddy.core.trakt.ProxyTokenRequest
import com.justb81.watchbuddy.core.trakt.TokenProxyService
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import com.justb81.watchbuddy.phone.ui.settings.AuthMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
    object NotConfigured : OnboardingState()
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    application: Application,
    private val traktApi: TraktApiService,
    private val tokenProxy: TokenProxyService?,
    @param:Named("traktClientId") private val buildConfigClientId: String,
    private val tokenRepository: TokenRepository,
    private val settingsRepository: SettingsRepository,
    private val tokenProxyServiceFactory: TokenProxyServiceFactory
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Idle)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private var countdownJob: Job? = null
    private var pollingJob: Job? = null

    /**
     * Resolves the effective client ID based on the current auth mode.
     * Returns null if the required configuration for the active mode is missing.
     */
    private fun resolveClientId(authMode: AuthMode, backendUrl: String, directClientId: String): String? {
        return when (authMode) {
            AuthMode.MANAGED -> buildConfigClientId.takeIf {
                it.isNotBlank() && tokenProxy != null
            }
            AuthMode.SELF_HOSTED -> buildConfigClientId.takeIf {
                it.isNotBlank() && backendUrl.isNotBlank()
            }
            AuthMode.DIRECT -> directClientId.takeIf {
                it.isNotBlank() && settingsRepository.getClientSecret().isNotBlank()
            }
        }
    }

    fun requestDeviceCode() {
        viewModelScope.launch {
            _state.value = OnboardingState.LoadingCode
            try {
                val settings = settingsRepository.settings.first()
                val clientId = resolveClientId(
                    settings.authMode, settings.backendUrl, settings.directClientId
                )
                if (clientId == null) {
                    _state.value = OnboardingState.NotConfigured
                    return@launch
                }

                val response = traktApi.requestDeviceCode(DeviceCodeRequest(clientId))
                startCountdown(response)
                startPolling(response, settings.authMode, settings.backendUrl, clientId)
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

    private fun startPolling(
        response: DeviceCodeResponse,
        authMode: AuthMode,
        backendUrl: String,
        clientId: String
    ) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var attempts = 0
            val maxAttempts = response.expires_in / response.interval
            while (isActive && attempts < maxAttempts) {
                delay(response.interval * 1_000L)
                try {
                    val accessToken: String
                    val refreshToken: String
                    val expiresIn: Int

                    when (authMode) {
                        AuthMode.MANAGED -> {
                            val token = tokenProxy!!.exchangeDeviceCode(
                                ProxyTokenRequest(code = response.device_code)
                            )
                            accessToken = token.access_token
                            refreshToken = token.refresh_token
                            expiresIn = token.expires_in
                        }
                        AuthMode.SELF_HOSTED -> {
                            val proxy = tokenProxyServiceFactory.create(backendUrl)
                            val token = proxy.exchangeDeviceCode(
                                ProxyTokenRequest(code = response.device_code)
                            )
                            accessToken = token.access_token
                            refreshToken = token.refresh_token
                            expiresIn = token.expires_in
                        }
                        AuthMode.DIRECT -> {
                            val secret = settingsRepository.getClientSecret()
                            val token = traktApi.pollDeviceToken(
                                DeviceTokenRequest(
                                    code = response.device_code,
                                    client_id = clientId,
                                    client_secret = secret
                                )
                            )
                            accessToken = token.access_token
                            refreshToken = token.refresh_token
                            expiresIn = token.expires_in
                        }
                    }

                    tokenRepository.saveTokens(
                        accessToken  = accessToken,
                        refreshToken = refreshToken,
                        expiresIn    = expiresIn
                    )
                    val profile = traktApi.getProfile("Bearer $accessToken")
                    countdownJob?.cancel()
                    _state.value = OnboardingState.Success(profile.username)
                    return@launch
                } catch (_: Exception) {
                    // HTTP 400 = PIN not yet confirmed, 410 = expired — keep polling
                }
                attempts++
            }
        }
    }
}
