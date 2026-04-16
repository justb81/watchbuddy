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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException
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

    /** Trakt not configured — CLIENT_ID or TOKEN_BACKEND_URL is missing. */
    object NotConfigured : OnboardingState()
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    application: Application,
    private val traktApi: TraktApiService,
    /** Null when TOKEN_BACKEND_URL in BuildConfig is empty. */
    private val tokenProxy: TokenProxyService?,
    @Named("traktClientId") private val clientId: String,
    private val tokenRepository: TokenRepository
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Idle)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

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
            var consecutiveNetworkFailures = 0
            val maxAttempts = response.expires_in / response.interval
            var attempts = 0
            while (isActive && attempts < maxAttempts) {
                delay(response.interval * 1_000L)
                try {
                    val token = tokenProxy!!.exchangeDeviceCode(
                        ProxyTokenRequest(code = response.device_code)
                    )
                    tokenRepository.saveTokens(
                        accessToken  = token.access_token,
                        refreshToken = token.refresh_token,
                        expiresIn    = token.expires_in
                    )

                    // Verify the token with a profile fetch — keep this error path
                    // isolated so auth failures are never counted as network failures.
                    try {
                        val profile = traktApi.getProfile("Bearer ${token.access_token}")
                        countdownJob?.cancel()
                        _state.value = OnboardingState.Success(profile.username)
                        return@launch
                    } catch (profileEx: Exception) {
                        val code = (profileEx as? HttpException)?.code()
                        if (code == 401 || code == 403) {
                            countdownJob?.cancel()
                            _state.value = OnboardingState.Error(
                                getApplication<Application>().getString(
                                    R.string.onboarding_error_auth_failed
                                )
                            )
                            return@launch
                        }
                        // Transient profile fetch failure — treat as network error
                        consecutiveNetworkFailures++
                    }
                } catch (e: Exception) {
                    val httpCode = (e as? HttpException)?.code()
                    when (httpCode) {
                        400 -> {
                            // Pending — user hasn't authorized yet, keep polling
                            consecutiveNetworkFailures = 0
                        }
                        401, 403 -> {
                            // Auth failure — credentials are invalid, stop immediately
                            countdownJob?.cancel()
                            _state.value = OnboardingState.Error(
                                getApplication<Application>().getString(
                                    R.string.onboarding_error_auth_failed
                                )
                            )
                            return@launch
                        }
                        409, 410 -> {
                            // Code expired or already used
                            countdownJob?.cancel()
                            _state.value = OnboardingState.Error(
                                getApplication<Application>().getString(R.string.onboarding_code_expired)
                            )
                            return@launch
                        }
                        418 -> {
                            // User denied access
                            countdownJob?.cancel()
                            _state.value = OnboardingState.Error(
                                getApplication<Application>().getString(R.string.onboarding_error_denied)
                            )
                            return@launch
                        }
                        429 -> {
                            // Slow down — back off without counting as failure
                            delay(response.interval * 1_000L)
                        }
                        else -> {
                            consecutiveNetworkFailures++
                        }
                    }
                }
                if (consecutiveNetworkFailures >= 3) {
                    countdownJob?.cancel()
                    _state.value = OnboardingState.Error(
                        getApplication<Application>().getString(
                            R.string.onboarding_error_polling_network
                        )
                    )
                    return@launch
                }
                attempts++
            }
        }
    }
}
