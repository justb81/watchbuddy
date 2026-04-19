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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.justb81.watchbuddy.core.trakt.isServerMisconfigured
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Named

enum class NotConfiguredReason {
    /** MANAGED mode: the build-time TRAKT_CLIENT_ID is empty. */
    MANAGED_MISSING_CLIENT_ID,
    /** MANAGED mode: the compiled-in token proxy is not available. */
    MANAGED_MISSING_BACKEND,
    /** SELF_HOSTED mode: the user has not entered a backend URL. */
    SELF_HOSTED_MISSING_URL,
    /** SELF_HOSTED mode: the build-time TRAKT_CLIENT_ID is empty. */
    SELF_HOSTED_MISSING_CLIENT_ID,
    /** DIRECT mode: either the Client ID or Client Secret is missing. */
    DIRECT_MISSING_CREDENTIALS,
}

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
    data class NotConfigured(val reason: NotConfiguredReason) : OnboardingState()
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

    // Holds the active device code for the current OAuth session within this process lifetime.
    // Not persisted across process death; if the process is killed, the user restarts OAuth.
    private var currentDeviceCode: DeviceCodeResponse? = null

    // Terminal polling errors keyed by HTTP status code.
    private val httpErrorMessages = mapOf(
        401 to R.string.onboarding_error_auth_failed,
        403 to R.string.onboarding_error_auth_failed,
        409 to R.string.onboarding_code_expired,
        410 to R.string.onboarding_code_expired,
        418 to R.string.onboarding_error_denied,
    )

    private fun failPolling(message: String) {
        countdownJob?.cancel()
        currentDeviceCode = null
        _state.value = OnboardingState.Error(message)
    }

    /**
     * Resolves the effective client ID based on the current auth mode.
     * Returns a [Pair] of (clientId, reason): if clientId is non-null the config is complete;
     * if null, reason describes why it is missing.
     */
    private fun resolveClientId(
        authMode: AuthMode,
        backendUrl: String,
        directClientId: String
    ): Pair<String?, NotConfiguredReason?> = when (authMode) {
        AuthMode.MANAGED -> when {
            buildConfigClientId.isBlank() -> null to NotConfiguredReason.MANAGED_MISSING_CLIENT_ID
            tokenProxy == null -> null to NotConfiguredReason.MANAGED_MISSING_BACKEND
            else -> buildConfigClientId to null
        }
        AuthMode.SELF_HOSTED -> when {
            backendUrl.isBlank() -> null to NotConfiguredReason.SELF_HOSTED_MISSING_URL
            buildConfigClientId.isBlank() && directClientId.isBlank() ->
                null to NotConfiguredReason.SELF_HOSTED_MISSING_CLIENT_ID
            else -> (buildConfigClientId.takeIf { it.isNotBlank() } ?: directClientId) to null
        }
        AuthMode.DIRECT -> {
            val secret = settingsRepository.getClientSecret()
            if (directClientId.isBlank() || secret.isBlank()) {
                null to NotConfiguredReason.DIRECT_MISSING_CREDENTIALS
            } else {
                directClientId to null
            }
        }
    }

    fun requestDeviceCode() {
        // Cancel stale jobs before starting fresh, so the old polling
        // coroutine cannot race and overwrite the new state.
        pollingJob?.cancel()
        countdownJob?.cancel()
        viewModelScope.launch {
            _state.value = OnboardingState.LoadingCode
            try {
                val settings = settingsRepository.settings.first()
                val (clientId, reason) = resolveClientId(
                    settings.authMode, settings.backendUrl, settings.directClientId
                )
                if (clientId == null) {
                    _state.value = OnboardingState.NotConfigured(reason!!)
                    return@launch
                }

                val response = currentDeviceCode ?: run {
                    val fresh = traktApi.requestDeviceCode(DeviceCodeRequest(clientId))
                    currentDeviceCode = fresh
                    fresh
                }

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
            currentDeviceCode = null
            _state.value = OnboardingState.Error(
                getApplication<Application>().getString(R.string.onboarding_code_expired)
            )
        }
    }

    private suspend fun startPolling(
        response: DeviceCodeResponse,
        authMode: AuthMode,
        backendUrl: String,
        clientId: String
    ) {
        pollingJob?.cancelAndJoin()
        pollingJob = viewModelScope.launch {
            var attempts = 0
            var consecutiveNetworkFailures = 0
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
                    currentDeviceCode = null
                    _state.value = OnboardingState.Success(profile.username)
                    return@launch
                } catch (e: Exception) {
                    val httpCode = (e as? HttpException)?.code()
                    when (httpCode) {
                        400 -> consecutiveNetworkFailures = 0
                        429 -> delay(response.interval * 1_000L)
                        503 -> {
                            val msg = if ((e as? HttpException)?.isServerMisconfigured() == true) {
                                getApplication<Application>().getString(
                                    R.string.onboarding_error_server_misconfigured
                                )
                            } else {
                                getApplication<Application>().getString(
                                    R.string.onboarding_error_polling_network
                                )
                            }
                            failPolling(msg)
                            return@launch
                        }
                        else -> {
                            val errorRes = httpErrorMessages[httpCode]
                            if (errorRes != null) {
                                failPolling(getApplication<Application>().getString(errorRes))
                                return@launch
                            }
                            consecutiveNetworkFailures++
                            if (consecutiveNetworkFailures >= 3) {
                                failPolling(
                                    getApplication<Application>().getString(
                                        R.string.onboarding_error_polling_network
                                    )
                                )
                                return@launch
                            }
                        }
                    }
                }
                attempts++
            }
        }
    }
}
