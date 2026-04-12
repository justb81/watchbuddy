package com.justb81.watchbuddy.phone.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import com.justb81.watchbuddy.phone.settings.AppSettings
import com.justb81.watchbuddy.phone.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthMode { MANAGED, SELF_HOSTED, DIRECT }

data class SettingsUiState(
    val traktUsername: String?     = null,
    val tmdbConnected: Boolean     = false,
    val companionRunning: Boolean  = false,
    val authMode: AuthMode         = AuthMode.MANAGED,
    val customBackendUrl: String   = "",
    val directClientId: String     = "",
    val directClientSecret: String = "",
    val llmBackend: String         = "",
    val llmModelName: String?      = null,
    val llmDownloadProgress: Int?  = null,   // null = not downloading, 0-100 = progress
    val llmReady: Boolean          = false,
    val freeRamMb: Int             = 0,
    val saveSuccess: Boolean       = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val llmOrchestrator: LlmOrchestrator,
    private val tokenRepository: TokenRepository,
    private val settingsRepository: SettingsRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState(
        llmBackend = application.getString(R.string.settings_llm_detecting)
    ))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadPersistedSettings()
        detectLlm()
    }

    private fun loadPersistedSettings() {
        viewModelScope.launch {
            val saved = settingsRepository.settings.first()
            val clientSecret = settingsRepository.getClientSecret()
            _uiState.value = _uiState.value.copy(
                authMode = saved.authMode,
                customBackendUrl = saved.backendUrl,
                directClientId = saved.directClientId,
                directClientSecret = clientSecret,
                companionRunning = saved.companionEnabled
            )
        }
    }

    private fun detectLlm() {
        viewModelScope.launch {
            val config = llmOrchestrator.selectConfig()
            _uiState.value = _uiState.value.copy(
                llmBackend  = config.backend.name,
                llmModelName = config.modelVariant?.fileName,
                llmReady    = false  // true after download
            )
        }
    }

    fun setAuthMode(mode: AuthMode) {
        _uiState.value = _uiState.value.copy(authMode = mode)
    }

    fun setCustomBackendUrl(url: String) {
        _uiState.value = _uiState.value.copy(customBackendUrl = url)
    }

    fun setDirectClientId(id: String) {
        _uiState.value = _uiState.value.copy(directClientId = id)
    }

    fun setDirectClientSecret(secret: String) {
        _uiState.value = _uiState.value.copy(directClientSecret = secret)
    }

    fun saveAdvancedSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.saveSettings(
                AppSettings(
                    authMode = state.authMode,
                    backendUrl = state.customBackendUrl,
                    directClientId = state.directClientId,
                    companionEnabled = state.companionRunning
                )
            )
            settingsRepository.saveClientSecret(state.directClientSecret)
            _uiState.value = _uiState.value.copy(saveSuccess = true)
        }
    }

    fun clearSaveSuccess() {
        _uiState.value = _uiState.value.copy(saveSuccess = false)
    }

    fun toggleCompanionService() {
        val newState = !_uiState.value.companionRunning
        _uiState.value = _uiState.value.copy(companionRunning = newState)
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.saveSettings(current.copy(companionEnabled = newState))
        }
        // TODO: start/stop CompanionService
    }

    fun disconnectTrakt() {
        tokenRepository.clearTokens()
        _uiState.value = _uiState.value.copy(traktUsername = null)
    }

    fun downloadModel() {
        viewModelScope.launch {
            // TODO: trigger WorkManager model download job
            for (progress in 0..100 step 5) {
                _uiState.value = _uiState.value.copy(llmDownloadProgress = progress)
                kotlinx.coroutines.delay(100)
            }
            _uiState.value = _uiState.value.copy(
                llmDownloadProgress = null,
                llmReady = true
            )
        }
    }
}
